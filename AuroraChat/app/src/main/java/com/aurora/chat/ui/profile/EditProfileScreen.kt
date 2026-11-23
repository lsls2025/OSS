package com.aurora.chat.ui.profile
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.runtime.produceState

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.aurora.chat.ui.components.decodeSampledFile
import com.aurora.chat.ui.components.TokenInsufficientCard
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextOverflow
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
    var userEmail by remember { mutableStateOf("") }
    var tokenBalance by remember { mutableStateOf(-1L) }
    var showTokenCard by remember { mutableStateOf(false) }

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
                    // loadAvatar 内部会把首次从服务器拿到的头像写进本地缓存文件，
                    // 下次进来直接读本地，不再依赖网络
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
        avatarUploadState = AvatarUploadState(fraction = 0f, uploading = true, success = null)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    // 本地落盘 + 内存缓存：改到 IO 线程做，且单独兜住——本地失败也不能拖垮上传
                    try {
                        val file = com.aurora.chat.data.local.LocalStorage.getMyAvatarFile(context, userId)
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { out -> cropped.compress(Bitmap.CompressFormat.PNG, 100, out) }
                        // 同步内存缓存，回到「我的」页首帧就是新头像
                        com.aurora.chat.data.local.AvatarCache.cacheUserAvatar(userId, cropped)
                    } catch (t: Throwable) {
                        com.aurora.chat.ErrorReporter.warn(
                            "Avatar", "本地头像落盘失败：${t.javaClass.simpleName}: ${t.message}", t
                        )
                    }
                    // 传入内容不可变，先复制一份再做压缩上传，避免进度回调期间原 bitmap 被回收；
                    // 复制失败（内存紧张）时退回原图，不能让整个上传流程断掉
                    val toUpload = try {
                        cropped.copy(Bitmap.Config.ARGB_8888, false) ?: cropped
                    } catch (t: Throwable) {
                        cropped
                    }
                    com.aurora.chat.data.repository.ChatRepository.uploadAvatarBitmapWithProgress(context, toUpload) { sent, total ->
                        val f = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 1f
                        // 进度回调在 IO 线程，切主线程更新 Compose 状态
                        scope.launch { avatarUploadState?.fraction = f }
                    }
                } catch (t: Throwable) {
                    com.aurora.chat.ErrorReporter.warn(
                        "Avatar", "头像上传异常：${t.javaClass.simpleName}: ${t.message}", t
                    )
                    false
                }
            }
            // 结果反馈：成功/失败都要明确提醒
            avatarUploadState = AvatarUploadState(fraction = 1f, uploading = false, success = ok)
        }
    }

    fun saveName(name: String) {
        // 先本地、后服务器（与「我的」页一致）：断网时改名也不会丢
        userName = name
        com.aurora.chat.data.local.LocalStorage.saveUserProfile(context, userId, name, userSignature)
        com.aurora.chat.data.api.AuroraApi.currentUserName = name
        scope.launch {
            try {
                val result = com.aurora.chat.data.api.AuroraApi.updateProfile(name, userSignature)
                if (!result.success) {
                    Toast.makeText(context, "昵称已存本地，服务器同步失败：${result.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "昵称已存本地，服务器同步失败：${e.localizedMessage ?: "网络错误"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveSignature(sig: String) {
        userSignature = sig
        com.aurora.chat.data.local.LocalStorage.saveUserProfile(context, userId, userName, sig)
        com.aurora.chat.data.api.AuroraApi.currentUserSignature = sig
        scope.launch {
            try {
                val result = com.aurora.chat.data.api.AuroraApi.updateSignature(sig)
                if (!result.success) {
                    Toast.makeText(context, "签名已存本地，服务器同步失败：${result.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "签名已存本地，服务器同步失败：${e.localizedMessage ?: "网络错误"}", Toast.LENGTH_SHORT).show()
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
    // 底色实色铺满全屏：本页是盖在「我的」页之上的子页，底色必须不透明，
    // 否则滑入过程与边缘会透出背后壁纸的颜色。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appCardBg())
    ) {
        // 顶部栏：自己延伸到状态栏底下（background 在 statusBarsPadding 之前），
        // 状态栏区域同样是实心白，不透出背后内容。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(com.aurora.chat.ui.theme.appSurface())
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onBack() },
                    contentAlignment = Alignment.Center
                ) { Text("‹", fontSize = 26.sp, color = com.aurora.chat.ui.theme.appTextPrimary()) }
                Spacer(Modifier.width(4.dp))
                Text("个人资料", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 板块 1：头像 ----------
            ProfileCard {
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFD1D5DB))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showSourceDialog = true },
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
                            painter = painterResource(com.aurora.chat.R.drawable.ic_profile_filled),
                            contentDescription = "默认头像",
                            modifier = Modifier.fillMaxSize().padding(14.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "点击更换头像",
                    fontSize = 12.sp,
                    color = com.aurora.chat.ui.theme.appTextHint(),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(20.dp))
            }

            // ---------- 板块 2：资料（可改） ----------
            ProfileCard {
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

            // ---------- 板块 3：账号信息（只读） ----------
            ProfileCard {
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
                EditRow(
                    label = "Token 余额",
                    value = if (tokenBalance >= 0L) tokenBalance.toString() else "加载中…",
                    onClick = { showTokenCard = true }
                )
            }

            Spacer(Modifier.height(1.dp).navigationBarsPadding())
        }
    }

    // ==================== 弹窗 ====================

    if (showSourceDialog) {
        Dialog(onDismissRequest = { showSourceDialog = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("选择头像", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.height(20.dp))
                Text(
                    "相册上传", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary(),
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
                    "拍照上传", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary(),
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
                            fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { st.fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (st.fraction < 1f) Color(0xFF3B82F6) else Color(0xFF22C55E)
                        )
                    } else if (st.success == true) {
                        Text("头像已成功上传，好友将看到你的新头像。", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
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
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("修改昵称", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
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
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("修改签名", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
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
                    color = if (editSignatureText.length > 25) Color(0xFFD97706) else com.aurora.chat.ui.theme.appTextHint(),
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
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("绑定邮箱", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
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

    if (showTokenCard) {
        val exhausted = tokenBalance <= 0L
        TokenInsufficientCard(
            title = if (exhausted) "token余额已耗尽" else "充值余额",
            onRecharge = { showTokenCard = false },
            onDismiss = { showTokenCard = false }
        )
    }
}

/** 板块卡片：白底 + 14dp 圆角（与群聊信息/好友资料同一套视觉值）。 */
@Composable
private fun ProfileCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(com.aurora.chat.ui.theme.appSurface())
    ) {
        content()
    }
}

/** 可点击的编辑行：左标题、右当前值 + 右箭头。 */
@Composable
private fun EditRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
        Spacer(Modifier.width(12.dp))
        // 值独占标签右侧的全部剩余空间并右对齐：之前给值也加了 weight(1f)，
        // 会和上面的弹性 Spacer 各分一半 → 值只占半屏（名字看着偏左），
        // 长签名/邮箱超出半屏就被截断成"被吞掉"。
        Text(
            value,
            fontSize = 14.sp,
            color = com.aurora.chat.ui.theme.appTextHint(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        Text("›", fontSize = 16.sp, color = Color(0xFFC7CBD1))
    }
}

/** 只读信息行。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
    }
}

/** 板块内行分隔线：0.5dp 且左侧内缩，不顶到卡片圆角边缘。 */
@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.5.dp)
            .background(com.aurora.chat.ui.theme.appCardBg())
    )
}

private fun formatRegisterDate(createdAtSeconds: Long): String {
    if (createdAtSeconds <= 0) return "未知"
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(createdAtSeconds * 1000))
}
