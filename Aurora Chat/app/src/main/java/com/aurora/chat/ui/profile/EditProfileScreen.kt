package com.aurora.chat.ui.profile

import androidx.compose.runtime.produceState

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.aurora.chat.ui.components.decodeSampledFile
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 头像上传状态（驱动上传进度/结果弹窗）
 * @param fraction  真实字节进度 0f~1f
 * @param uploading 是否仍在传输
 * @param success   null=进行中；true=成功；false=失败
 */
private class AvatarUploadState(
    var fraction: Float = 0f,
    var uploading: Boolean = true,
    var success: Boolean? = null
)

/**
 * 编辑资料：从右侧滑入的独立全屏界面。
 * 结构自上而下由若干区块组成：
 *   1) 头像区（点击弹窗选择拍照/相册，逻辑与资料页一致）
 *   2) 资料与设置（昵称、个性签名、绑定邮箱可改；ID 不显示、不可改）
 *   3) 账号信息（注册时间、在平台、在线时间，只读）
 */
@Composable
fun EditProfileScreen(
    userId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---------- 头像 ----------
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var cropImageUri by remember { mutableStateOf<Uri?>(null) }
    var showCrop by remember { mutableStateOf(false) }
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var avatarUploadState by remember { mutableStateOf<AvatarUploadState?>(null) }

    // ---------- 资料 ----------
    var userName by remember { mutableStateOf("") }
    var userSignature by remember { mutableStateOf("") }
    var userCreatedAt by remember { mutableStateOf(0L) }
    // 邮箱以服务端资料为准（AuroraApi.currentUserEmail 只在登录流程写入，重进 App 后会是空值）
    var userEmail by remember { mutableStateOf("") }
    // Token 余额：不做独立入口，作为账号只读信息展示
    var tokenBalance by remember { mutableStateOf(-1L) }

    var showNameDialog by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var showEmailDialog by remember { mutableStateOf(false) }
    var editNameText by remember { mutableStateOf("") }
    var editSignatureText by remember { mutableStateOf("") }

    // ---------- 邮箱绑定 ----------
    var emailText by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
    var emailSending by remember { mutableStateOf(false) }
    var emailBinding by remember { mutableStateOf(false) }
    var codeCountdown by remember { mutableStateOf(0) }

    // 载入资料
    LaunchedEffect(userId) {
        withContext(Dispatchers.IO) {
            try {
                val info = com.aurora.chat.data.repository.ChatRepository.getUserInfo(userId)
                if (info.success && info.data != null) {
                    userName = info.data!!.username
                    userSignature = info.data!!.signature
                    userCreatedAt = info.data!!.createdAt
                    if (info.data!!.email.isNotEmpty()) userEmail = info.data!!.email
                    OnlineTimeTracker.setServerBase(info.data!!.onlineTimeSeconds)
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
                    val bmp = com.aurora.chat.data.repository.ChatRepository.loadAvatar(context, userId)
                    if (bmp != null) avatarBitmap = bmp
                } catch (_: Exception) {}
            }
            // Token 余额（接口把数据包在 data 字段里，需先解出来；与活动中心逻辑一致）
            try {
                val res = com.aurora.chat.data.api.AuroraApi.tokenBalance()
                val d = res.data
                if (res.success && d != null) {
                    val inner = if (d.has("data")) d.optJSONObject("data") ?: d else d
                    if (inner.has("token_balance")) tokenBalance = inner.getLong("token_balance")
                }
            } catch (_: Exception) {}
        }
        OnlineTimeTracker.start(context)
    }

    // 验证码倒计时
    LaunchedEffect(codeCountdown) {
        if (codeCountdown > 0) {
            delay(1000)
            codeCountdown--
        }
    }

    // ---------- 选择器 ----------
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { cropImageUri = it; showCrop = true }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success && cameraPhotoUri != null) { cropImageUri = cameraPhotoUri; showCrop = true }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { val uri = createTempImageUri(context); cameraPhotoUri = uri; cameraLauncher.launch(uri) }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) galleryLauncher.launch("image/*")
    }

    // ---------- 保存动作 ----------
    fun uploadAvatar(cropped: Bitmap) {
        avatarBitmap = cropped                       // 秒更新本地显示，不等上传完成
        // 立即落盘本地副本（即使网络失败也不丢已选头像）
        try {
            val file = com.aurora.chat.data.local.LocalStorage.getMyAvatarFile(context, userId)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out -> cropped.compress(Bitmap.CompressFormat.PNG, 100, out) }
        } catch (_: Exception) {}
        // 传入内容不可变，先复制一份再做压缩上传，避免进度回调期间原 bitmap 被回收
        val toUpload = cropped.copy(Bitmap.Config.ARGB_8888, false) ?: cropped
        avatarUploadState = AvatarUploadState(fraction = 0f, uploading = true, success = null)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                com.aurora.chat.data.repository.ChatRepository.uploadAvatarBitmapWithProgress(context, toUpload) { sent, total ->
                    val f = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 1f
                    // 进度回调在 IO 线程，切主线程更新 Compose 状态
                    scope.launch { avatarUploadState?.fraction = f }
                }
            }
            // 结果反馈：成功/失败都要明确提醒
            avatarUploadState = AvatarUploadState(fraction = 1f, uploading = false, success = ok)
        }
    }

    fun saveName(name: String) {
        scope.launch {
            try {
                val result = com.aurora.chat.data.api.AuroraApi.updateProfile(name, userSignature)
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
                val result = com.aurora.chat.data.api.AuroraApi.updateSignature(sig)
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

    fun sendBindCode() {
        if (emailText.isBlank()) {
            Toast.makeText(context, "请输入邮箱", Toast.LENGTH_SHORT).show()
            return
        }
        emailSending = true
        scope.launch {
            try {
                val r = com.aurora.chat.data.api.AuroraApi.sendBindCode(emailText.trim())
                if (r.success) {
                    Toast.makeText(context, "验证码已发送", Toast.LENGTH_SHORT).show()
                    codeCountdown = 60
                } else {
                    Toast.makeText(context, r.message.ifEmpty { "发送失败" }, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "发送失败: ${e.localizedMessage ?: "网络错误"}", Toast.LENGTH_SHORT).show()
            } finally {
                emailSending = false
            }
        }
    }

    fun bindEmail() {
        if (emailCode.isBlank()) {
            Toast.makeText(context, "请输入验证码", Toast.LENGTH_SHORT).show()
            return
        }
        emailBinding = true
        scope.launch {
            try {
                val r = com.aurora.chat.data.api.AuroraApi.bindEmail(emailText.trim(), emailCode.trim())
                if (r.success) {
                    userEmail = emailText.trim()
                    com.aurora.chat.data.api.AuroraApi.currentUserEmail = userEmail
                    Toast.makeText(context, "绑定成功", Toast.LENGTH_SHORT).show()
                    showEmailDialog = false
                } else {
                    Toast.makeText(context, r.message.ifEmpty { "绑定失败" }, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "绑定失败: ${e.localizedMessage ?: "网络错误"}", Toast.LENGTH_SHORT).show()
            } finally {
                emailBinding = false
            }
        }
    }

    // ==================== 界面 ====================
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F6F8))
            .statusBarsPadding()
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(Color.White)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) { Text("‹", fontSize = 28.sp, color = Color(0xFF1A1D29)) }
            Spacer(Modifier.width(8.dp))
            Text("编辑资料", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ---------- 区块 1：头像 ----------
            SectionHeader("头像")
            Column(
                modifier = Modifier.fillMaxWidth().background(Color.White),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(22.dp))
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFD1D5DB))
                        .clickable { showSourceDialog = true },
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
                            painter = painterResource(com.aurora.chat.R.drawable.ic_profile),
                            contentDescription = "默认头像",
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("点击更换头像", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                Spacer(Modifier.height(22.dp))
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 区块 2：资料与设置（ID 不显示、不可改） ----------
            SectionHeader("资料与设置")
            Column(modifier = Modifier.fillMaxWidth().background(Color.White)) {
                EditRow(
                    label = "昵称",
                    value = userName.ifEmpty { "未设置" },
                    onClick = { editNameText = userName; showNameDialog = true }
                )
                RowDivider()
                EditRow(
                    label = "个性签名",
                    value = userSignature.ifEmpty { "未设置" },
                    onClick = { editSignatureText = userSignature; showSignatureDialog = true }
                )
                RowDivider()
                EditRow(
                    label = "绑定邮箱",
                    value = userEmail.ifEmpty { "未绑定" },
                    onClick = {
                        emailText = userEmail
                        emailCode = ""
                        showEmailDialog = true
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 区块 3：账号信息（只读） ----------
            SectionHeader("账号信息")
            Column(modifier = Modifier.fillMaxWidth().background(Color.White)) {
                InfoRow("注册时间", formatRegisterDate(userCreatedAt))
                RowDivider()
                InfoRow("在平台", formatDaysOnPlatform(userCreatedAt))
                RowDivider()
                // 在线时间本地状态 + 计时器：produceState 使每秒更新仅重组本行，不再触发整屏重组
                val onlineTimeSec by produceState(OnlineTimeTracker.restoreFromPrefs(context)) {
                    while (true) {
                        delay(1000)
                        value = OnlineTimeTracker.getCurrentTotalSeconds(context)
                    }
                }
                InfoRow("在线时间", formatOnlineTime(onlineTimeSec))
                RowDivider()
                InfoRow("Token 余额", if (tokenBalance >= 0L) tokenBalance.toString() else "加载中…")
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.height(1.dp).navigationBarsPadding())
        }
    }

    // ==================== 弹窗 ====================

    if (showSourceDialog) {
        Dialog(onDismissRequest = { showSourceDialog = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("选择头像", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(20.dp))
                Text(
                    "相册上传", fontSize = 16.sp, color = Color(0xFF1F2937),
                    modifier = Modifier.fillMaxWidth().clickable {
                        showSourceDialog = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                                galleryLauncher.launch("image/*")
                            else storagePermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                        } else {
                            galleryLauncher.launch("image/*")
                        }
                    }.padding(vertical = 12.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "拍照上传", fontSize = 16.sp, color = Color(0xFF1F2937),
                    modifier = Modifier.fillMaxWidth().clickable {
                        showSourceDialog = false
                        if (context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            val uri = createTempImageUri(context)
                            cameraPhotoUri = uri
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }.padding(vertical = 12.dp)
                )
            }
        }
    }

    if (showCrop && cropImageUri != null) {
        CropPreviewDialog(
            imageUri = cropImageUri!!,
            context = context,
            onCropConfirm = { cropped ->
                showCrop = false
                cropImageUri = null
                uploadAvatar(cropped)
            },
            onDismiss = { showCrop = false; cropImageUri = null }
        )
    }

    // 头像上传进度 + 结果弹窗
    avatarUploadState?.let { st ->
        AlertDialog(
            onDismissRequest = { if (!st.uploading) avatarUploadState = null },
            title = {
                Text(
                    text = when {
                        st.uploading -> "正在上传头像"
                        st.success == true -> "上传成功"
                        else -> "上传失败"
                    },
                    fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    if (st.uploading) {
                        // 进行中：展示真实字节百分比
                        Text(
                            "已上传 ${(st.fraction * 100).toInt()}%　(基于实际传输字节)",
                            fontSize = 14.sp, color = Color(0xFF6B7280)
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { st.fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (st.fraction < 1f) Color(0xFF3B82F6) else Color(0xFF22C55E)
                        )
                    } else if (st.success == true) {
                        Text("头像已成功上传，好友将看到你的新头像。", fontSize = 14.sp, color = Color(0xFF1F2937))
                    } else {
                        Text("头像上传失败，请检查网络后重试。已选择的头像已保存在本地。", fontSize = 14.sp, color = Color(0xFFDC2626))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { avatarUploadState = null }) {
                    Text(if (st.uploading) "后台上传" else "知道了")
                }
            }
        )
    }

    if (showNameDialog) {
        Dialog(onDismissRequest = { showNameDialog = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("修改昵称", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = editNameText,
                    onValueChange = { editNameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showNameDialog = false }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { saveName(editNameText); showNameDialog = false }) { Text("保存") }
                }
            }
        }
    }

    if (showSignatureDialog) {
        Dialog(onDismissRequest = { showSignatureDialog = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("修改签名", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = editSignatureText,
                    onValueChange = { if (it.length <= 30) editSignatureText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    "${editSignatureText.length}/30",
                    fontSize = 12.sp,
                    color = if (editSignatureText.length > 25) Color(0xFFD97706) else Color(0xFF9CA3AF),
                    modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                    textAlign = TextAlign.End
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showSignatureDialog = false }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { saveSignature(editSignatureText); showSignatureDialog = false }) { Text("保存") }
                }
            }
        }
    }

    if (showEmailDialog) {
        Dialog(onDismissRequest = { showEmailDialog = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("绑定邮箱", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = emailText,
                    onValueChange = { emailText = it },
                    label = { Text("邮箱") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = emailCode,
                        onValueChange = { emailCode = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("验证码") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { sendBindCode() },
                        enabled = !emailSending && codeCountdown == 0
                    ) {
                        Text(if (codeCountdown > 0) "${codeCountdown}s" else "发送")
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showEmailDialog = false }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { bindEmail() }, enabled = !emailBinding) { Text("确定") }
                }
            }
        }
    }
}

/** 区块标题（灰色小字）。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = Color(0xFF9CA3AF),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** 可点击的编辑行：左标题、右当前值。 */
@Composable
private fun EditRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = Color(0xFF1F2937))
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 14.sp,
            color = Color(0xFF9CA3AF),
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

/** 只读信息行。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = Color(0xFF1F2937))
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = Color(0xFF9CA3AF))
    }
}

/** 区块内行分隔线。 */
@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFFF0F1F3))
    )
}

private fun formatRegisterDate(createdAtSeconds: Long): String {
    if (createdAtSeconds <= 0) return "未知"
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(createdAtSeconds * 1000))
}
