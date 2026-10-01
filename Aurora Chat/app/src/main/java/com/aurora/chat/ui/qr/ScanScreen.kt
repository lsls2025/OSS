package com.aurora.chat.ui.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanScreen(
    currentUserId: Long,
    onBack: () -> Unit,
    onScanned: (Long, String) -> Unit,  // (目标用户ID, 用户名) 相机实时扫码成功时调用
    onPickedImage: (ByteArray?) -> Unit = {}  // 从相册选图后立即回调（由上层解析并切入资料页）
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
                val result = withContext(Dispatchers.IO) { AuroraApi.resolveQrCode(text) }
                if (result.success && result.data != null) {
                    val d = result.data!!
                    // 扫到自己/别人都正常跳转资料页（用户想看看自己资料完全合理，不拦截）
                    if (d.userId > 0) {
                        if (handled.compareAndSet(false, true)) {
                            onScanned(d.userId, d.username)
                        }
                    } else {
                        toast(context, d.message.ifEmpty { "无效用户" })
                    }
                } else {
                    toast(context, result.message)
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
    if (loginQrPending == null) {
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
    } else {
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
            .background(Color(0xFFF7F8FB))
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
                color = Color(0xFF6B7280),
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(12.dp))
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
                    color = Color(0xFF6B7280),
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
                            color = Color(0xFF9CA3AF),
                            style = Stroke(width = stroke),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("桌面端下次免登录", fontSize = 13.sp, color = Color(0xFF6B7280))
            }
            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 拒绝（返回）
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .weight(1f)
                        .background(Color(0xFFEFF1F5), RoundedCornerShape(12.dp))
                        .clickable(enabled = !confirming) { onBack() },
                    contentAlignment = Alignment.Center,
                ) { Text("拒绝", color = Color(0xFF6B7280), fontSize = 15.sp, fontWeight = FontWeight.Medium) }

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
                fontSize = 11.sp, color = Color(0xFF9CA3AF),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
