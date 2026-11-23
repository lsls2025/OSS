package com.aurora.chat.ui.qr
import com.aurora.chat.ui.theme.LocalAppColors

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.components.GroupAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanScreen(
    currentUserId: Long,
    onBack: () -> Unit,
    onScanned: (Long, String) -> Unit,  // (目标用户ID, 用户名) 相机实时扫码成功时调用
    onPickedImage: (ByteArray?) -> Unit = {},  // 从相册选图后立即回调（由上层解析并切入资料页）
    onGroupScanned: (Long, String) -> Unit = { _, _ -> }  // (群ID, 群名) 扫到群二维码并加入成功后调用
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    // 保存相机提供者，离开界面时解绑，避免退出后相机仍在后台空跑
    val cameraProviderState = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProviderState.value?.unbindAll() }
            cameraProviderState.value = null
        }
    }
    // 退场前主动停掉相机：避免实时预览在滑出动画中一边平移一边刷新而拖影
    fun stopCamera() {
        runCatching { cameraProviderState.value?.unbindAll() }
    }
    // 返回（含侧滑返回手势）：先停相机再退出
    BackHandler {
        stopCamera()
        onBack()
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    // 授权结果回调：用户点「允许」后立即置为已授权，重组即自动开启预览，无需退出重进
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }
    // 用 AtomicBoolean 做并发防重入，避免在相机后台线程读写 Compose 状态
    val handled = remember { AtomicBoolean(false) }
    val resolving = remember { AtomicBoolean(false) }

    // 扫码登录授权状态：null = 无，否则为待授权的登录二维码内容
    var loginQrPending by remember { mutableStateOf<String?>(null) }
    // 是否已上报"已扫描"（避免重复上报）
    var loginQrScanned by remember { mutableStateOf(false) }
    // 第三方授权二维码待确认信息：null = 无；扫到 qrauth?sid= 前缀后由 scan 上报结果填充
    var qrAuthPending by remember { mutableStateOf<QrAuthPending?>(null) }
    var groupPending by remember { mutableStateOf<GroupQrPending?>(null) }
    // 非站内二维码（网址 / 纯文本 / 电话 / 邮件 / 短信等）走通用结果卡
    var genericPending by remember { mutableStateOf<String?>(null) }

    fun requestCamera() {
        runCatching { permissionLauncher.launch(Manifest.permission.CAMERA) }
    }

    // 解析二维码文本 → 调服务端验证 → 主线程回调
    fun resolveAndReport(text: String?) {
        if (text.isNullOrEmpty() || handled.get() || !resolving.compareAndSet(false, true)) return
        scope.launch {
            try {
                // 桌面端"扫码登录"二维码分流
                if (text.contains("qrlogin?sid=")) {
                    // 上报"已扫描"（需登录）
                    val scanResult = AuroraApi.scanLoginQr(text)
                    if (!scanResult.success) {
                        toast(context, scanResult.message.ifEmpty { "上报失败，请重试" })
                        return@launch
                    }
                    // 弹出授权确认框（含安全提示 3 秒）
                    if (handled.compareAndSet(false, true)) {
                        loginQrPending = text
                        loginQrScanned = false
                    }
                    return@launch
                }
                // 第三方"二维码授权"分流：先上报已扫描，再展示应用名与申请权限确认框
                if (text.contains("qrauth?sid=")) {
                    val scanResult = AuroraApi.scanQrAuth(text)
                    if (!scanResult.success) {
                        toast(context, scanResult.message.ifEmpty { "上报失败，请重试" })
                        return@launch
                    }
                    val d = scanResult.data ?: JSONObject()
                    if (handled.compareAndSet(false, true)) {
                        qrAuthPending = QrAuthPending(
                            qrContent = text,
                            appName = d.optString("app_name").ifEmpty { "第三方应用" },
                            scopes = d.optString("scopes"),
                        )
                    }
                    return@launch
                }
                // 明显不是站内码的内容（纯文本 / tel / mailto / sms / 其它 scheme）本地直接出通用结果卡，
                // 不再白打一次 resolveQrCode；http(s) 链接仍先交给服务端判定，失败再落到通用卡。
                val normalized = text.trim()
                val lowerText = normalized.lowercase()
                val looksOffline = !lowerText.startsWith("http://") &&
                    !lowerText.startsWith("https://") &&
                    !lowerText.startsWith("aurora://") &&
                    normalized.toLongOrNull() == null
                if (looksOffline) {
                    if (handled.compareAndSet(false, true)) genericPending = normalized
                    return@launch
                }
                val result = withContext(Dispatchers.IO) { AuroraApi.resolveQrCode(text) }
                if (result.success && result.data != null) {
                    val d = result.data!!
                    // 群二维码：展示群信息确认卡，加入成功后由上层切入群聊
                    if (d.isGroup && d.groupId > 0) {
                        if (handled.compareAndSet(false, true)) {
                            groupPending = GroupQrPending(
                                groupId = d.groupId,
                                groupName = d.groupName.ifEmpty { "群聊${d.groupId}" },
                                displayId = d.displayId,
                                memberCount = d.memberCount,
                                isMember = d.isMember
                            )
                        }
                        return@launch
                    }
                    // 扫到自己/别人都正常跳转资料页（用户想看看自己资料完全合理，不拦截）
                    if (d.userId > 0) {
                        if (handled.compareAndSet(false, true)) {
                            onScanned(d.userId, d.username)
                        }
                    } else {
                        toast(context, d.message.ifEmpty { "无效用户" })
                    }
                } else {
                    // 服务端不认：可能是普通网址/其它标准二维码，交给通用结果卡；纯垃圾内容也能看到原文
                    if (handled.compareAndSet(false, true)) {
                        genericPending = normalized
                    } else {
                        toast(context, result.message)
                    }
                }
            } catch (e: Exception) {
                toast(context, "验证失败: ${e.message}")
            } finally {
                resolving.set(false)
            }
        }
    }

    // 相册选图：立即交给上层解析并切入资料页（避免停留在摄像头界面）
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    .getOrNull()
            }
            onPickedImage(bytes)
        }
    }

    // 首次进入未授权则弹授权框
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) requestCamera()
    }
    // 回到前台时复核权限（用户从系统设置授权返回后自动生效）
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    if (loginQrPending == null && qrAuthPending == null && groupPending == null && genericPending == null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clickable { stopCamera(); onBack() },
                contentAlignment = Alignment.Center
            ) { Text("‹", fontSize = 28.sp, color = Color.White) }
            Spacer(Modifier.width(8.dp))
            Text("扫一扫", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = androidx.camera.view.PreviewView(ctx).apply {
                            scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                            // 用 TextureView（COMPATIBLE）渲染：滑出动画时预览随界面一起位移，
                            // 避免 SurfaceView 独立图层滞后产生的拖影
                            implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()
                                cameraProviderState.value = cameraProvider
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { imageAnalysis ->
                                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                            if (handled.get() || resolving.get()) {
                                                imageProxy.close()
                                                return@setAnalyzer
                                            }
                                            val text = try {
                                                parseQrFromBitmap(imageProxy.toBitmap())
                                            } catch (_: Exception) {
                                                null
                                            } finally {
                                                imageProxy.close()
                                            }
                                            if (text != null) resolveAndReport(text)
                                        }
                                    }
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                                    )
                                } catch (_: Exception) {}
                            } catch (_: Exception) {}
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                ScanFrame()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("需要相机权限才能扫码", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .height(40.dp).padding(horizontal = 24.dp)
                            .background(Color(0xFF2563EB), RoundedCornerShape(20.dp))
                            .clickable { requestCamera() },
                        contentAlignment = Alignment.Center
                    ) { Text("授权相机", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
                }
            }
        }

        // 底部：从相册选择
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .navigationBarsPadding()
                .clickable { pickLauncher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            Text("从相册选择二维码图片", color = Color.White, fontSize = 15.sp)
        }
    }
    } else if (loginQrPending != null) {
        LoginAuthReview(
            qrContent = loginQrPending.orEmpty(),
            onBack = {
                toast(context, "已拒绝授权")
                onBack()
            },
            onConfirmed = {
                toast(context, "已确认授权，桌面端即将登录")
                onBack()
            },
        )
    } else if (qrAuthPending != null) {
        QrAuthReview(
            qrContent = qrAuthPending!!.qrContent,
            appName = qrAuthPending!!.appName,
            scopes = qrAuthPending!!.scopes,
            onBack = {
                toast(context, "已拒绝授权")
                onBack()
            },
            onConfirmed = {
                toast(context, "已确认授权，第三方可拉取授权数据")
                onBack()
            },
        )
    } else if (genericPending != null) {
        // 通用二维码（网址/文本/电话/邮件/短信）：展示内容并提供对应操作
        GenericQrReview(
            text = genericPending.orEmpty(),
            onBack = {
                handled.set(false)
                genericPending = null
            }
        )
    } else {
        GroupQrReview(
            pending = groupPending!!,
            onBack = {
                handled.set(false)
                groupPending = null
                toast(context, "已取消")
            },
            onJoined = {
                onGroupScanned(groupPending!!.groupId, groupPending!!.groupName)
                onBack()
            }
        )
    }
    }
}

/**
 * 取景框：框外半透明遮罩 + 居中的白色圆角方框 + 四角修饰，用于校准二维码。
 */
@Composable
private fun ScanFrame() {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 取景框边长：取宽高中较小者的 68%，保证各种屏幕比例下都是适中正方形
        val frameSize = minOf(maxWidth, maxHeight) * 0.68f
        val cornerRadius = 20.dp

        // 框外遮罩：用 even-odd 路径挖出「圆角矩形」孔洞，与白框圆角严丝合缝，四角不再露出突兀暗块
        Canvas(modifier = Modifier.fillMaxSize()) {
            val f = frameSize.toPx()
            val left = (size.width - f) / 2f
            val top = (size.height - f) / 2f
            val r = cornerRadius.toPx()
            val maskPath = Path().apply {
                addRect(Rect(0f, 0f, size.width, size.height))
                addRoundRect(RoundRect(left, top, left + f, top + f, CornerRadius(r, r)))
                fillType = PathFillType.EvenOdd
            }
            drawPath(maskPath, Color(0x99000000))
        }

        // 取景框本体：白色圆角方框 + 四角修饰（统一白色，不引入其它颜色）
        Canvas(modifier = Modifier.size(frameSize)) {
            val r = cornerRadius.toPx()
            val w = size.width
            val h = size.height
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(0f, 0f),
                size = Size(w, h),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 3.dp.toPx())
            )
            // 四角（沿边内缩一个圆角半径，避免压住圆角弧线）
            val len = w * 0.12f
            val cs = 5.dp.toPx()
            drawLine(Color.White, Offset(0f, r + len), Offset(0f, r), cs, StrokeCap.Round)
            drawLine(Color.White, Offset(r, 0f), Offset(r + len, 0f), cs, StrokeCap.Round)
            drawLine(Color.White, Offset(w, r + len), Offset(w, r), cs, StrokeCap.Round)
            drawLine(Color.White, Offset(w - r, 0f), Offset(w - r - len, 0f), cs, StrokeCap.Round)
            drawLine(Color.White, Offset(0f, h - r - len), Offset(0f, h - r), cs, StrokeCap.Round)
            drawLine(Color.White, Offset(r, h), Offset(r + len, h), cs, StrokeCap.Round)
            drawLine(Color.White, Offset(w, h - r - len), Offset(w, h - r), cs, StrokeCap.Round)
            drawLine(Color.White, Offset(w - r, h), Offset(w - r - len, h), cs, StrokeCap.Round)
        }

        Text(
            "将二维码放入框内",
            color = Color.White, fontSize = 14.sp,
            // 用 offset 下移提示文字，放在取景框下方（padding 不支持负值，会闪退）
            modifier = Modifier.offset(y = frameSize / 2 + 28.dp)
        )
    }
}

/**
 * 桌面端扫码登录授权确认：从右侧滑入的完整授权界面（非弹窗）。
 * 强制要求阅读安全提示满 3 秒后才能点击"同意授权"，防止误操作误授权。
 */
@Composable
private fun LoginAuthReview(
    qrContent: String,
    onBack: () -> Unit,
    onConfirmed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 安全提示阅读倒计时
    var remaining by remember { mutableStateOf(3) }
    // 是否已调 confirmLoginQr
    var confirming by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    // 桌面端下次免登录（勾选后桌面端持久化登录态）
    var rememberPersist by remember { mutableStateOf(false) }

    // 3 秒倒计时
    LaunchedEffect(qrContent) {
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000)
            remaining--
        }
    }

    fun confirm() {
        if (confirming) return
        confirming = true
        authError = null
        scope.launch {
            val sessionId = qrContent.substringAfter("qrlogin?sid=", "").substringBefore("&")
            val result = AuroraApi.confirmLoginQr(qrContent, sessionId, rememberPersist)
            confirming = false
            if (result.success) {
                onConfirmed()
            } else {
                authError = result.message.ifEmpty { "授权失败，请重试" }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appCardBg())
            .statusBarsPadding(),
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 28.sp, color = Color(0xFF1A1D29)) }
            Spacer(Modifier.width(8.dp))
            Text("确认登录桌面端", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
        }

        // 说明 + 安全提示（可滚动）
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                "你正在授权 Aurora Chat 桌面端登录你的账号。授权后，桌面端可查看你的会话列表等账号数据。",
                fontSize = 13.sp,
                color = com.aurora.chat.ui.theme.appTextSecondary(),
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text("安全提示", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. 请确认该二维码来自你信任的设备或个人电脑。\n" +
                        "2. 仅在你自己操作的桌面端登录，切勿扫他人展示的二维码。\n" +
                        "3. 授权后桌面端将获得登录凭证，等同你本人操作。\n" +
                        "4. 如非本人扫码，请立即点击「拒绝」并勿继续操作。\n",
                    fontSize = 12.sp,
                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                    lineHeight = 18.sp,
                )
            }

            if (authError != null) {
                Spacer(Modifier.height(12.dp))
                Text(authError!!, fontSize = 12.sp, color = Color(0xFFF04438))
            }
        }

        // 底部操作（安全区）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            // 桌面端下次免登录（圆形勾选框，紧挨"同意并授权"上方）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !confirming) { rememberPersist = !rememberPersist }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(modifier = Modifier.size(18.dp)) {
                    val stroke = 1.5.dp.toPx()
                    if (rememberPersist) {
                        drawCircle(color = Color(0xFF1A1D29))
                        drawLine(
                            color = Color.White,
                            start = Offset(size.width * 0.28f, size.height * 0.52f),
                            end = Offset(size.width * 0.44f, size.height * 0.68f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(size.width * 0.44f, size.height * 0.68f),
                            end = Offset(size.width * 0.74f, size.height * 0.34f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    } else {
                        drawCircle(
                            color = com.aurora.chat.ui.theme.appTextHint(),
                            style = Stroke(width = stroke),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("桌面端下次免登录", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
            }
            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 拒绝（返回）
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .weight(1f)
                        .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp))
                        .clickable(enabled = !confirming) { onBack() },
                    contentAlignment = Alignment.Center,
                ) { Text("拒绝", color = com.aurora.chat.ui.theme.appTextSecondary(), fontSize = 15.sp, fontWeight = FontWeight.Medium) }

                // 同意授权（需读完 3 秒且未在请求中）
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .weight(1f)
                        .background(if (remaining > 0 || confirming) Color(0xFFC4C9D4) else Color(0xFF1A1D29), RoundedCornerShape(12.dp))
                        .clickable(enabled = remaining <= 0 && !confirming) { confirm() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (confirming) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(
                            if (remaining > 0) "${remaining}秒后授权" else "同意并授权",
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "请仔细阅读以上安全提示，确认是本人在操作",
                fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** 第三方扫码授权待确认信息（scan 上报成功后由确认框展示） */
private data class QrAuthPending(
    val qrContent: String,
    val appName: String,
    val scopes: String,
)

/** 将 scopes 白名单翻译为可读的中文权限名（与后端 sanitizeScopes 白名单一致） */
private fun qrAuthScopeLabels(scopes: String): List<String> {
    val labels = mapOf(
        "email" to "邮箱",
        "qq" to "QQ 号",
        "avatar" to "头像",
        "name" to "昵称",
        "uid" to "用户 ID",
    )
    return scopes.split(",").mapNotNull { labels[it.trim()] }
}

/**
 * 第三方扫码授权确认界面（样式参考 LoginAuthReview）：
 * 展示第三方应用名与申请的权限项，安全提示满 3 秒后才能同意授权。
 */
@Composable
private fun QrAuthReview(
    qrContent: String,
    appName: String,
    scopes: String,
    onBack: () -> Unit,
    onConfirmed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 安全提示阅读倒计时
    var remaining by remember { mutableStateOf(3) }
    // 是否已调 confirmQrAuth
    var confirming by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    val scopeNames = remember(scopes) { qrAuthScopeLabels(scopes) }

    // 3 秒倒计时
    LaunchedEffect(qrContent) {
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000)
            remaining--
        }
    }

    fun confirm() {
        if (confirming) return
        confirming = true
        authError = null
        scope.launch {
            val sessionId = qrContent.substringAfter("qrauth?sid=", "").substringBefore("&")
            val result = AuroraApi.confirmQrAuth(qrContent, sessionId)
            confirming = false
            if (result.success) {
                onConfirmed()
            } else {
                authError = result.message.ifEmpty { "授权失败，请重试" }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appCardBg())
            .statusBarsPadding(),
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 28.sp, color = Color(0xFF1A1D29)) }
            Spacer(Modifier.width(8.dp))
            Text("确认第三方授权", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
        }

        // 说明 + 应用信息 + 权限项 + 安全提示（可滚动）
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            // 应用卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text("第三方应用", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                Spacer(Modifier.height(6.dp))
                Text(appName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
                Spacer(Modifier.height(12.dp))
                Text("申请获取以下权限", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                Spacer(Modifier.height(6.dp))
                if (scopeNames.isEmpty()) {
                    Text("无权限项", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                } else {
                    scopeNames.forEach { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Canvas(modifier = Modifier.size(16.dp)) {
                                drawCircle(color = Color(0xFF1A1D29))
                                drawLine(
                                    color = Color.White,
                                    start = Offset(size.width * 0.3f, size.height * 0.52f),
                                    end = Offset(size.width * 0.45f, size.height * 0.68f),
                                    strokeWidth = 1.5.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )
                                drawLine(
                                    color = Color.White,
                                    start = Offset(size.width * 0.45f, size.height * 0.68f),
                                    end = Offset(size.width * 0.72f, size.height * 0.34f),
                                    strokeWidth = 1.5.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(name, fontSize = 14.sp, color = Color(0xFF1A1D29))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text("安全提示", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. 请确认该二维码来自你信任的第三方网站或应用。\n" +
                        "2. 授权后，第三方仅能按上列权限项拉取你的账号数据，未授权数据项（如 QQ）不会被提供。\n" +
                        "3. 如非本人扫码，请立即点击「拒绝」并勿继续操作。\n",
                    fontSize = 12.sp,
                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                    lineHeight = 18.sp,
                )
            }

            if (authError != null) {
                Spacer(Modifier.height(12.dp))
                Text(authError!!, fontSize = 12.sp, color = Color(0xFFF04438))
            }
        }

        // 底部操作（安全区）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 拒绝（返回）
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .weight(1f)
                        .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp))
                        .clickable(enabled = !confirming) { onBack() },
                    contentAlignment = Alignment.Center,
                ) { Text("拒绝", color = com.aurora.chat.ui.theme.appTextSecondary(), fontSize = 15.sp, fontWeight = FontWeight.Medium) }

                // 同意授权（需读完 3 秒且未在请求中）
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .weight(1f)
                        .background(if (remaining > 0 || confirming) Color(0xFFC4C9D4) else Color(0xFF1A1D29), RoundedCornerShape(12.dp))
                        .clickable(enabled = remaining <= 0 && !confirming) { confirm() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (confirming) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(
                            if (remaining > 0) "${remaining}秒后授权" else "同意并授权",
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "请仔细阅读以上安全提示，确认是本人在操作",
                fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** 群二维码待确认信息（服务端解析成功后由确认卡展示） */
private data class GroupQrPending(
    val groupId: Long,
    val groupName: String,
    val displayId: Long,
    val memberCount: Long,
    val isMember: Boolean,
)

/**
 * 群二维码确认界面：展示群名/群号/成员数，
 * 已是成员直接"进入群聊"，否则"加入群聊"（加入成功后由上层切入群聊）。
 */
@Composable
private fun GroupQrReview(
    pending: GroupQrPending,
    onBack: () -> Unit,
    onJoined: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var joining by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp)
                .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GroupAvatar(internalGroupId = pending.groupId, groupName = pending.groupName, size = 64.dp)
            Spacer(Modifier.height(12.dp))
            Text(pending.groupName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("群号：${pending.displayId}", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
            Spacer(Modifier.height(4.dp))
            Text("${pending.memberCount} 位成员", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())

            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (joining) Color(0xFF93A5CF) else Color(0xFF1E40AF))
                    .clickable(enabled = !joining) {
                        if (pending.isMember) {
                            onJoined()
                        } else {
                            scope.launch {
                                joining = true
                                val r = AuroraApi.submitJoinRequest(pending.groupId, "扫一扫加入群聊")
                                joining = false
                                if (r.success) {
                                    toast(context, "已加入群聊")
                                    onJoined()
                                } else {
                                    toast(context, r.message.ifEmpty { "加入失败，请重试" })
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (joining) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        if (pending.isMember) "进入群聊" else "加入群聊",
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("取消", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                modifier = Modifier.clickable { onBack() }.padding(8.dp))
        }
    }
}

/**
 * 通用二维码结果卡：站内码（用户/群/授权/登录）之外的任何内容都落到这里。
 * 网址 → 显示域名 + 打开；tel/mailto/sms → 直接调起系统；其它 → 原样展示 + 复制。
 * 一律本地处理，不再走服务端。
 */
@Composable
private fun GenericQrReview(
    text: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val content = text.trim()
    val lower = content.lowercase()
    val isLink = lower.startsWith("http://") || lower.startsWith("https://")
    val isTel = lower.startsWith("tel:")
    val isMail = lower.startsWith("mailto:")
    val isSms = lower.startsWith("sms:") || lower.startsWith("smsto:")
    val host = remember(content) {
        runCatching { Uri.parse(content).host }.getOrNull().orEmpty()
    }

    fun copy() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qr", content))
        toast(context, "已复制")
    }

    fun openWithSystem() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(content))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast(context, "无法打开该内容") }
    }

    val primaryLabel = when {
        isLink -> "打开链接"
        isTel -> "拨打电话"
        isMail -> "发送邮件"
        isSms -> "发送短信"
        else -> "复制文本"
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp)
                .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when {
                    isLink -> "网址二维码"
                    isTel || isSms || isMail -> "联系方式二维码"
                    else -> "文本二维码"
                },
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary()
            )
            if (isLink && host.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(host, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = Color(0xFF1E40AF), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                content,
                fontSize = 13.sp,
                color = com.aurora.chat.ui.theme.appTextSecondary(),
                maxLines = 5,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E40AF))
                    .clickable {
                        if (isLink || isTel || isMail || isSms) openWithSystem() else copy()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(primaryLabel, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
            if (isLink || isTel || isMail || isSms) {
                Spacer(Modifier.height(10.dp))
                Text("复制内容", fontSize = 14.sp, color = Color(0xFF1E40AF),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { copy() }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("取消", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                modifier = Modifier.clickable { onBack() }.padding(8.dp))
        }
    }
}
