package com.aurora.chat.ui.profile

import androidx.compose.runtime.produceState
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.CaptchaData
import com.aurora.chat.ui.components.CaptchaGenerator
import com.aurora.chat.ui.components.CaptchaView
import com.aurora.chat.ui.theme.AuroraPrimary
import com.aurora.chat.util.DeviceTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============ 仿 Zerynth Relay 的弹窗配色（深色青绿卡片 + 青色主按钮）============
private val ZBg = Color(0xFF062A35)
private val ZAccent = Color(0xFF2DD4BF)
private val ZDim = Color(0xFF8AA0A8)
private val ZDanger = Color(0xFFEF4444)

@Composable
fun LoginScreen(
    onLoginSuccess: (String, String, Long) -> Unit
) {
    var isPasswordResetMode by remember { mutableStateOf(false) }
    var isRegisterMode by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // 设备指纹计算较重，移 IO，避免登录首屏组合期阻塞
    val fingerprint by produceState("") {
        withContext(Dispatchers.IO) { value = DeviceTracker.getFingerprint(context) }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // 跨卸载痕迹写入不再强制申请"所有文件访问"权限（易在部分机型/新版系统上无法授权，
    // 且普通用户不知道去哪开启）。仅在已具备该权限时尽力补写痕迹，未授权则静默跳过。
    LaunchedEffect(Unit) {
        val fp = fingerprint
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) DeviceTracker.ensureTraces(fp)
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            DeviceTracker.ensureTraces(fp)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(100.dp))
            Text(
                text = when {
                    isPasswordResetMode -> "密码找回"
                    isRegisterMode -> "注册账号"
                    else -> "用户登录"
                },
                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(20.dp))
            Spacer(modifier = Modifier.height(28.dp))

            if (isPasswordResetMode) {
                PasswordResetForm(onBack = { isPasswordResetMode = false }, onToast = { toast(it) })
            } else if (isRegisterMode) {
                RegisterForm(
                    fingerprint = fingerprint,
                    onSwitch = { isRegisterMode = false },
                    onRegisterSuccess = { email, username, userId ->
                        hideKeyboard(); toast("注册成功"); onLoginSuccess(email, username, userId)
                    },
                    onToast = { toast(it) }
                )
            } else {
                EmailLoginForm(
                    onSwitch = { isRegisterMode = true },
                    onLoginSuccess = { email, username, userId -> hideKeyboard(); toast("登录成功"); onLoginSuccess(email, username, userId) },
                    onResetPassword = { isPasswordResetMode = true },
                    onToast = { toast(it) }
                )
            }
        }
    }
}

@Composable
private fun RowScope.SegmentButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
            .background(if (selected) AuroraPrimary else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = if (selected) Color.White else Color(0xFF6B7280))
    }
}

// ===================== 邮箱登录 =====================
@Composable
private fun EmailLoginForm(
    onSwitch: () -> Unit,
    onLoginSuccess: (String, String, Long) -> Unit,
    onResetPassword: () -> Unit,
    onToast: (String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var failCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun validate(): Boolean {
        if (email.isBlank()) { onToast("用户名/QQ号未填写"); return false }
        if (password.isBlank()) { onToast("密码未填写"); return false }
        if (password.length < 8) { onToast("密码不能少于8位"); return false }
        return true
    }

    TextInput("用户名 / QQ号", email, { email = it }, "请输入用户名或QQ号")
    Spacer(modifier = Modifier.height(12.dp))
    PasswordInput(value = password, onValueChange = { password = it }, failCount = failCount, onResetPassword = onResetPassword)
    Spacer(modifier = Modifier.height(20.dp))
    Button(onClick = {
        if (validate() && !isLoading) {
            isLoading = true
            scope.launch {
                val result = ChatRepository.login(email, password)
                isLoading = false
                if (result.success) {
                    val d = result.data
                    if (d != null) com.aurora.chat.data.local.LoginHistoryManager.recordLogin(ctx, d.email, d.username, d.id, password, d.qqNumber)
                    onLoginSuccess(email, result.data?.username ?: "", result.data?.id ?: 0)
                } else { onToast(result.message); failCount++ }
            }
        }
    }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AuroraPrimary), enabled = !isLoading) {
        if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text("登录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
    Spacer(modifier = Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text("没有账号？", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text("注册", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AuroraPrimary,
            modifier = Modifier.clickable(enabled = !isLoading) { onSwitch() })
    }
}

// ===================== 注册表单（左标签右输入框，验证码来自服务端 SVG）=====================
@Composable
private fun RegisterForm(
    fingerprint: String,
    onSwitch: () -> Unit,
    onRegisterSuccess: (String, String, Long) -> Unit,
    onToast: (String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var qq by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun isValidQQ(s: String) = s.all { it.isDigit() } && s.length in 5..11

    // 用户名
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("用户名", fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.width(72.dp))
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            placeholder = { Text("请输入用户名", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                focusedBorderColor = AuroraPrimary,
                cursorColor = AuroraPrimary
            )
        )
    }
    Spacer(Modifier.height(12.dp))

    // QQ 号
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("QQ 号", fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.width(72.dp))
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = qq,
            onValueChange = { qq = it.filter { c -> c.isDigit() }.take(11) },
            placeholder = { Text("请输入QQ号", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                focusedBorderColor = AuroraPrimary,
                cursorColor = AuroraPrimary
            )
        )
    }
    Spacer(modifier = Modifier.height(12.dp))

    // 密码
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("密码", fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.width(72.dp))
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            placeholder = { Text("请输入密码", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            trailingIcon = {
                Box(Modifier.size(24.dp).clickable { passwordVisible = !passwordVisible }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(22.dp)) {
                        val c = center; val eyeW = size.width * 0.68f; val eyeH = size.height * 0.40f; val sw = 1.6.dp.toPx(); val col = Color(0xFF6B7280)
                        drawOval(color = col, topLeft = Offset(c.x - eyeW/2f, c.y - eyeH/2f), size = Size(eyeW, eyeH), style = Stroke(width = sw))
                        drawCircle(color = col, radius = eyeH * 0.30f, center = c)
                        if (!passwordVisible) drawLine(color = col, start = Offset(c.x - eyeW*0.48f, c.y - eyeH*0.65f), end = Offset(c.x + eyeW*0.48f, c.y + eyeH*0.65f), strokeWidth = sw*1.2f)
                    }
                }
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                focusedBorderColor = AuroraPrimary,
                cursorColor = AuroraPrimary
            )
        )
    }
    Spacer(Modifier.height(12.dp))

    if (errMsg.isNotBlank()) {
        Text(errMsg, fontSize = 12.sp, color = Color(0xFFEF4444))
        Spacer(Modifier.height(8.dp))
    }

    Button(onClick = {
        errMsg = ""
        if (username.isBlank()) { errMsg = "请输入用户名"; return@Button }
        if (!isValidQQ(qq)) { errMsg = "请输入有效的 QQ 号码"; return@Button }
        if (password.length < 8) { errMsg = "密码不能少于 8 位"; return@Button }
        if (!DeviceTracker.canRegisterMore()) {
            errMsg = "当前设备已达注册上限（最多 ${DeviceTracker.MAX_ACCOUNTS_PER_DEVICE} 个账号）"
            return@Button
        }
        isLoading = true
        scope.launch {
            val avatarBytes = runCatching { ChatRepository.fetchQQAvatar(qq) }.getOrNull()
            // 注册必须携带设备指纹（后端已移除图形验证码，缺失指纹即拒绝非法调用）
            val result = ChatRepository.qqLoginWithCaptcha(qq, username, "", "", fingerprint)
            withContext(Dispatchers.Main) {
                isLoading = false
                if (result.success) {
                    DeviceTracker.recordRegistration(fingerprint)
                    val d = result.data
                    val userId = d?.id ?: 0
                    if (avatarBytes != null && userId > 0) {
                        val bmp = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.size)
                        if (bmp != null) ChatRepository.uploadAvatarBitmap(ctx, bmp)
                    }
                    onRegisterSuccess(d?.email ?: "", d?.username ?: username, userId)
                } else {
                    errMsg = result.message
                }
            }
        }
    }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AuroraPrimary), enabled = !isLoading) {
        if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text("注册", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
    Spacer(modifier = Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text("已有账号？", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text("登录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AuroraPrimary,
            modifier = Modifier.clickable(enabled = !isLoading) { onSwitch() })
    }
}

// ===================== 密码找回 =====================
@Composable
private fun PasswordResetForm(onBack: () -> Unit, onToast: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var sendingCode by remember { mutableStateOf(false) }
    var codeCountdown by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(codeCountdown) { if (codeCountdown > 0) { delay(1000); codeCountdown-- } }

    fun validate(): Boolean {
        if (email.isBlank()) { onToast("用户名/QQ号未填写"); return false }
        if (code.isBlank()) { onToast("验证码未填写"); return false }
        if (newPassword.isBlank()) { onToast("新密码未填写"); return false }
        if (newPassword.length < 8) { onToast("密码不能少于8位"); return false }
        return true
    }

    TextInput("用户名 / QQ号", email, { email = it }, "请输入用户名或QQ号")
    Spacer(modifier = Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextFieldZ(value = code, onValueChange = { code = it }, placeholder = "输入6位验证码", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            if (email.isBlank()) { onToast("请输入用户名/QQ号"); return@Button }
            sendingCode = true
            scope.launch { val r = ChatRepository.sendResetCode(email); sendingCode = false
                if (r.success) { onToast("验证码已发送"); codeCountdown = 60 } else onToast(r.message) }
        }, enabled = codeCountdown == 0 && !sendingCode && !isLoading, shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AuroraPrimary)) {
            Text(if (codeCountdown > 0) "${codeCountdown}s" else "发送验证码", fontSize = 13.sp, color = Color.White)
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    TextInput("新密码", newPassword, { newPassword = it }, "请输入新密码", isPassword = true)
    Spacer(modifier = Modifier.height(20.dp))
    Button(onClick = {
        if (validate()) { isLoading = true
            scope.launch { val r = ChatRepository.resetPassword(email, code, newPassword); isLoading = false
                if (r.success) { onToast("密码重置成功"); onBack() } else onToast(r.message) } }
    }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AuroraPrimary), enabled = !isLoading) {
        Text("重置密码", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
    Spacer(modifier = Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text("想起密码了？", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text("返回登录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AuroraPrimary, modifier = Modifier.clickable { onBack() })
    }
}

// ===================== 通用输入框（白底，登录页用）=====================
@Composable
private fun TextInput(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String,
                      isPassword: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(value = value, onValueChange = onValueChange, placeholder = { Text(placeholder, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType), singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), focusedBorderColor = AuroraPrimary, cursorColor = AuroraPrimary))
    }
}

// ===================== 弹窗内输入框（深色青绿主题）=====================
@Composable
private fun TextInputZ(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String,
                       keyboardType: KeyboardType = KeyboardType.Text) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = ZDim)
        Spacer(Modifier.height(4.dp))
        OutlinedTextFieldZ(value = value, onValueChange = onValueChange, placeholder = placeholder, keyboardType = keyboardType)
    }
}

@Composable
private fun OutlinedTextFieldZ(
    value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(), keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(value = value, onValueChange = onValueChange, placeholder = { Text(placeholder, fontSize = 13.sp, color = ZDim) },
        modifier = modifier, shape = RoundedCornerShape(10.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, color = Color.White),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = Color(0x40FFFFFF), focusedBorderColor = ZAccent, cursorColor = ZAccent,
            unfocusedContainerColor = Color(0x0DFFFFFF), focusedContainerColor = Color(0x0DFFFFFF)
        ))
}

// ===================== 密码输入（登录页眼形图标）=====================
@Composable
private fun PasswordInput(value: String, onValueChange: (String) -> Unit, failCount: Int, onResetPassword: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("密码", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            if (failCount >= 2) { Spacer(Modifier.width(6.dp)); Text("忘记密码?", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Text("重置", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AuroraPrimary, modifier = Modifier.clickable { onResetPassword() }) }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(value = value, onValueChange = onValueChange, placeholder = { Text("请输入密码", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true,
            trailingIcon = { Box(Modifier.size(24.dp).clickable { visible = !visible }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp)) {
                    val c = center; val eyeW = size.width * 0.68f; val eyeH = size.height * 0.40f; val sw = 1.6.dp.toPx(); val col = Color(0xFF6B7280)
                    drawOval(color = col, topLeft = Offset(c.x - eyeW/2f, c.y - eyeH/2f), size = Size(eyeW, eyeH), style = Stroke(width = sw))
                    drawCircle(color = col, radius = eyeH * 0.30f, center = c)
                    if (!visible) drawLine(color = col, start = Offset(c.x - eyeW*0.48f, c.y - eyeH*0.65f), end = Offset(c.x + eyeW*0.48f, c.y + eyeH*0.65f), strokeWidth = sw*1.2f)
                }
            } },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), focusedBorderColor = AuroraPrimary, cursorColor = AuroraPrimary))
    }
}