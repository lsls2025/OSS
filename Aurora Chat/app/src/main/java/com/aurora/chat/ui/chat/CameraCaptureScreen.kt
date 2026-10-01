package com.aurora.chat.ui.chat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CameraCaptureScreen(
    onDismiss: () -> Unit,
    onPhotoCaptured: (File) -> Unit,
    onVideoCaptured: (File, Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasCameraPermission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            LaunchedEffect(Unit) {
                try {
                    (context as androidx.activity.ComponentActivity).requestPermissions(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 200
                    )
                } catch (_: Exception) {}
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("需要相机权限", color = Color.White, fontSize = 16.sp)
        }
        return
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }

    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingTime by remember { mutableIntStateOf(0) }
    var capturedPhotoPath by remember { mutableStateOf<String?>(null) }
    var capturedVideoPath by remember { mutableStateOf<String?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    val scope = rememberCoroutineScope()

    val outputDir = remember {
        File(context.cacheDir, "camera_captures").also { it.mkdirs() }
    }

    // 录制计时
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingTime = 0
            while (isRecording && recordingTime < 30) {
                delay(1000)
                recordingTime++
            }
            if (recordingTime >= 30) {
                isRecording = false
                activeRecording?.close()
                activeRecording = null
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val captureRingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse)
    )

    // 拍照
    fun takePhoto() {
        val cap = imageCapture ?: return
        val file = File(outputDir, "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg")
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturedPhotoPath = file.absolutePath
                }
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(context, "拍照失败", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 开始录制
    fun startRecording() {
        val vc = videoCapture ?: return
        isRecording = true
        val file = File(outputDir, "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.mp4")
        val outputOptions = FileOutputOptions.Builder(file).build()
        try {
            val recording = vc.output.prepareRecording(context, outputOptions)
                .apply {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        withAudioEnabled()
                    }
                }
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Finalize -> {
                            if (!event.hasError()) {
                                capturedVideoPath = file.absolutePath
                            } else {
                                Toast.makeText(context, "录制失败", Toast.LENGTH_SHORT).show()
                                isRecording = false
                            }
                        }
                        else -> {}
                    }
                }
            activeRecording = recording
        } catch (e: Exception) {
            android.util.Log.e("CameraCapture", "Record start failed", e)
            isRecording = false
        }
    }

    // 停止录制
    fun stopRecording() {
        activeRecording?.close()
        activeRecording = null
        isRecording = false
    }

    DisposableEffect(Unit) {
        onDispose {
            activeRecording?.close()
            cameraExecutor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (capturedPhotoPath != null || capturedVideoPath != null) {
            // 预览结果
            val isVideo = capturedVideoPath != null
            val filePath = capturedPhotoPath ?: capturedVideoPath ?: ""

            Box(Modifier.fillMaxSize()) {
                if (!isVideo) {
                    AsyncImage(
                        model = File(filePath),
                        contentDescription = "拍摄结果",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1F2937)), contentAlignment = Alignment.Center) {
                        Text("视频 ${recordingTime}s", color = Color.White, fontSize = 16.sp)
                    }
                }

                // 顶部关闭
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp).statusBarsPadding()
                        .size(36.dp).clip(CircleShape).background(Color(0x66000000))
                        .clickable {
                            capturedPhotoPath = null; capturedVideoPath = null
                        },
                    contentAlignment = Alignment.Center
                ) { Text("×", fontSize = 22.sp, color = Color.White) }

                // 底部按钮
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .padding(horizontal = 32.dp, vertical = 40.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0x66FFFFFF))
                        .clickable { capturedPhotoPath = null; capturedVideoPath = null }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) { Text("重拍", color = Color.White, fontSize = 15.sp) }
                    Box(Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xFF1E40AF))
                        .clickable {
                            if (isVideo) {
                                capturedVideoPath?.let { onVideoCaptured(File(it), recordingTime) }
                            } else {
                                capturedPhotoPath?.let { onPhotoCaptured(File(it)) }
                            }
                        }
                        .padding(horizontal = 28.dp, vertical = 12.dp)
                    ) { Text("发送", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        } else {
            // 相机预览
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    ProcessCameraProvider.getInstance(ctx).also { future ->
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            imageCapture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .setFlashMode(flashMode).build()
                            val recorder = Recorder.Builder()
                                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
                                .build()
                            videoCapture = VideoCapture.withOutput(recorder)
                            try {
                                provider.unbindAll()
                                camera = provider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview, imageCapture, videoCapture
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("Camera", "bind failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0x66FFFFFF)).clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Text("×", fontSize = 22.sp, color = Color.White) }
                val fl = when (flashMode) {
                    ImageCapture.FLASH_MODE_ON -> "开"; ImageCapture.FLASH_MODE_AUTO -> "自动"
                    else -> "关"
                }
                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x66FFFFFF))
                    .clickable {
                        flashMode = when (flashMode) {
                            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                            else -> ImageCapture.FLASH_MODE_OFF
                        }
                        imageCapture?.flashMode = flashMode
                    }.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("⚡", fontSize = 16.sp, color = Color.White)
                        Text(fl, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // 底部拍照按钮
            Box(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 50.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(80.dp)) {
                    if (!isRecording) drawCircle(Color.White.copy(alpha = captureRingAlpha), size.minDimension / 2, style = Stroke(2.dp.toPx()))
                }
                Box(
                    modifier = Modifier
                        .size(if (isRecording) 56.dp else 68.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) Color(0xFFFF4444) else Color.White)
                        .border(if (isRecording) 3.dp else 4.dp, Color.White.copy(0.7f), CircleShape)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val longPressMs = viewConfiguration.longPressTimeoutMillis
                                val released = withTimeoutOrNull(longPressMs) {
                                    waitForUpOrCancellation()
                                }
                                if (released == null) {
                                    // 长按→开始录制
                                    scope.launch { startRecording() }
                                    waitForUpOrCancellation()
                                    stopRecording()
                                } else {
                                    // 短按→拍照
                                    takePhoto()
                                }
                            }
                        }
                ) {
                    if (isRecording) Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).align(Alignment.Center))
                }
                if (isRecording) {
                    Box(Modifier.align(Alignment.TopCenter).offset(y = (-70).dp)) {
                        Text("${recordingTime}s", color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x99000000))
                                .padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}
