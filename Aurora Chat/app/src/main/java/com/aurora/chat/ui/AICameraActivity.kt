package com.aurora.chat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.aurora.chat.ui.chat.AiChatManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 专用相机界面：供「打开前置摄像头 / 打开后置摄像头」工具拉起。
 *
 * 拍照后照片保存到工作区 `camera_captures/` 目录，并通过 [lastResult] 把绝对路径回传给工具；
 * 未授予相机权限时先弹系统授权，用户同意后自动继续。
 */
class AICameraActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LENS = "lens"      // "front" = 前置，"back" = 后置
        const val LENS_FRONT = "front"
        const val LENS_BACK = "back"
        const val DIR_NAME = "camera_captures"
        private const val REQ_CAM = 9101

        @Volatile
        var lastResult: String? = null

        @Volatile
        var lastError: String? = null

        /** 用户手动点了「关闭」：供等待中的工具立即收尾，不再傻等超时。 */
        @Volatile
        var closedByUser: Boolean = false

        /** 工具侧请求关闭界面（AI 主动收起相机）：由界面轮询后自行 finish。 */
        @Volatile
        var closeRequested: Boolean = false

        fun reset() {
            lastResult = null
            lastError = null
            closedByUser = false
            closeRequested = false
        }

        /** 请求关闭当前已打开的相机界面（供 open_front_camera / open_rear_camera 工具调用）。 */
        fun requestClose() {
            closeRequested = true
        }
    }

    private var lensFront = false
    private var imageCapture: ImageCapture? = null
    private lateinit var previewView: PreviewView
    private lateinit var hintText: TextView
    private lateinit var shotBtn: TextView

    /** 相机权限申请(ActivityResult 方式,兼容新版 Activity)。 */
    private val camPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) bindCamera() else {
            lastError = "用户未授予相机权限"
            Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 轮询「工具请求关闭」：AI 侧调用 requestClose() 后 200ms 内收起界面。 */
    private val closeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val closePoll = object : Runnable {
        override fun run() {
            if (closeRequested && !isFinishing && !isDestroyed) {
                closedByUser = false
                finish()
                return
            }
            closeHandler.postDelayed(this, 200)
        }
    }

    override fun onDestroy() {
        closeHandler.removeCallbacks(closePoll)
        // 兜底终态：无论如何关闭（返回键、系统回收、工具请求），只要没拍照也没报错，
        // 就给等待中的工具一个明确结论，绝不让它轮询到超时。
        if (lastResult == null && lastError == null) {
            lastError = if (closedByUser) {
                "用户关闭了相机界面,未拍照"
            } else {
                "相机界面已关闭,未拍照"
            }
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lensFront = intent?.getStringExtra(EXTRA_LENS) == LENS_FRONT

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 顶栏：当前镜头 + 关闭
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(16), dp(30), dp(16), dp(10))
        }
        hintText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            text = if (lensFront) "前置摄像头" else "后置摄像头"
        }
        top.addView(
            hintText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val closeBtn = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            text = "关闭"
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                // 必须留下终态信号：否则等待中的工具会一直轮询到超时（最长 5 分钟），
                // 表现就是「状态行卡在正在调用，永远不结束」。
                closedByUser = true
                finish()
            }
        }
        top.addView(closeBtn)
        root.addView(
            top,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 底栏：切换镜头 + 拍照
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(30))
        }
        bottom.addView(TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            text = "切换镜头"
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener {
                lensFront = !lensFront
                hintText.text = if (lensFront) "前置摄像头" else "后置摄像头"
                bindCamera()
            }
        })
        shotBtn = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            text = "拍照"
            setPadding(dp(30), dp(12), dp(30), dp(12))
            setOnClickListener { takePhoto() }
        }
        bottom.addView(shotBtn)
        root.addView(
            bottom,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        setContentView(root)

        // 工具侧请求关闭（AI 主动收起相机）：轮询到即自行结束，不等用户操作。
        closeHandler.post(closePoll)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { camPermLauncher.launch(Manifest.permission.CAMERA) }
                .onFailure {
                    lastError = "无法弹出相机权限申请:${it.message}"
                    finish()
                }
        } else {
            bindCamera()
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val selector = if (lensFront) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
                imageCapture = capture
            } catch (e: Exception) {
                // 把「为什么打不开」说清楚：CameraX 的报错多半是「没有对应镜头」，
                // 附上本机实际可用镜头，避免调用方把启动失败误判成「界面找不到」。
                val lens = availableLensCn()
                lastError = if (lens.isEmpty()) {
                    "本机未检测到可用摄像头(${e.javaClass.simpleName}:${e.message})"
                } else {
                    "打不开${if (lensFront) "前置" else "后置"}摄像头" +
                        "(${e.javaClass.simpleName}:${e.message})；本机可用摄像头:${lens.joinToString("、")}"
                }
                Toast.makeText(this, lastError, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 本机实际存在的镜头中文列表（前置 / 后置 / 外接），探测不到时返回空表。 */
    private fun availableLensCn(): List<String> = runCatching {
        val cm = getSystemService(CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            ?: return emptyList()
        cm.cameraIdList.map { cid ->
            when (cm.getCameraCharacteristics(cid)
                .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "后置"
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
                else -> "其它"
            }
        }.distinct()
    }.getOrDefault(emptyList())

    private fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(AiChatManager.agentRoot(this), DIR_NAME).apply { if (!exists()) mkdirs() }
        val file = File(
            dir,
            "camera_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
        )
        shotBtn.isEnabled = false
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            opts,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(res: ImageCapture.OutputFileResults) {
                    lastResult = file.absolutePath
                    finish()
                }

                override fun onError(exc: ImageCaptureException) {
                    shotBtn.isEnabled = true
                    lastError = "拍照失败:${exc.message}"
                    Toast.makeText(this@AICameraActivity, "拍照失败", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}