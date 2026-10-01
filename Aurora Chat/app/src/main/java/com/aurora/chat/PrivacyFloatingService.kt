package com.aurora.chat

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * 隐私功能悬浮球服务 —— 蓝紫渐变悬浮球，点击弹出隐私控制面板。
 *
 * 功能：
 * - 主开关：启用后监控截屏/录屏
 * - 禁止退出：开启后双方锁定，需双方同意才能解锁
 * - 禁止截图：开启后自动拦截截图并通知对方
 * - 禁止录屏：开启后自动拦截录屏并通知对方
 */
class PrivacyFloatingService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var popupView: View? = null
    private var isPopupShowing = false
    private var isDestroyed = false

    /** 渐变背景，用于颜色动画 */
    private var bgGradient: GradientDrawable? = null
    /** 背景色循环动画 */
    private var colorAnimator: ValueAnimator? = null

    companion object {
        @JvmStatic var currentFriendId = 0L
        @JvmStatic var masterOn = false
        @JvmStatic var noExit = false
        @JvmStatic var noScreenshot = false
        @JvmStatic var noRecording = false
        @JvmStatic var detectScreenshot = false
        @JvmStatic var detectRecording = false

        fun start(ctx: Context, friendId: Long) {
            currentFriendId = friendId
            ensureOverlayPermission(ctx)
            try { ctx.stopService(Intent(ctx, PrivacyFloatingService::class.java)) } catch (_: Exception) {}
            ctx.startService(Intent(ctx, PrivacyFloatingService::class.java))
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, PrivacyFloatingService::class.java)) } catch (_: Exception) {}
        }

        fun ensureOverlayPermission(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private var pollJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (floatingView == null) buildFloatingView()
        loadSettings()
        // 轮询同步：每 3 秒拉取对方的最新隐私设置
        pollJob?.cancel()
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(3000)
                if (isDestroyed) break
                try {
                    // 轮询我方设置（确保静态字段与服务端同步）
                    val myResult = AuroraApi.getPrivacySettings(currentFriendId)
                    if (myResult.success && myResult.data != null) {
                        masterOn = myResult.data.optBoolean("master_on", masterOn)
                        noExit = myResult.data.optBoolean("no_exit", noExit)
                        noScreenshot = myResult.data.optBoolean("no_screenshot", noScreenshot)
                        noRecording = myResult.data.optBoolean("no_recording", noRecording)
                    }
                    // 轮询对方设置
                    val fr = AuroraApi.getFriendPrivacySettings(currentFriendId)
                    if (fr.success && fr.data != null) {
                        val friendNoExit = fr.data.optBoolean("no_exit", false)
                        val friendNoScreenshot = fr.data.optBoolean("no_screenshot", false)
                        val friendNoRecording = fr.data.optBoolean("no_recording", false)
                        // 双方都开启 noExit → 显示全锁定状态
                        if (noExit && friendNoExit) {
                            // 双方锁死，显示提示
                        }
                        // 对方开启截图保护 → 应用 FLAG_SECURE（由 Activity 端处理）
                        if (friendNoScreenshot != noScreenshot) {
                            // 标记状态变化
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isDestroyed = true
        colorAnimator?.cancel()
        ScreenshotDetector.stop(this)
        removePopup()
        floatingView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        floatingView = null
        super.onDestroy()
    }

    private fun loadSettings() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = AuroraApi.getPrivacySettings(currentFriendId)
                if (result.success && result.data != null) {
                    masterOn = result.data.optBoolean("master_on", false)
                    noExit = result.data.optBoolean("no_exit", false)
                    noScreenshot = result.data.optBoolean("no_screenshot", false)
                    noRecording = result.data.optBoolean("no_recording", false)
                    detectScreenshot = result.data.optBoolean("detect_screenshot", false)
                    detectRecording = result.data.optBoolean("detect_recording", false)
                    if (masterOn && (noScreenshot || detectScreenshot)) {
                        ScreenshotDetector.start(this@PrivacyFloatingService, currentFriendId)
                    } else if (!masterOn && detectScreenshot) {
                        // 没开总开关但开启了截图检测 → 也启动检测器
                        ScreenshotDetector.start(this@PrivacyFloatingService, currentFriendId)
                    } else {
                        ScreenshotDetector.stop(this@PrivacyFloatingService)
                    }
                    if (masterOn && (noRecording || detectRecording)) {
                        // 录屏检测待实现
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ==================== 悬浮球（蓝紫渐变，与下载风格一致） ====================

    private fun buildFloatingView() {
        val density = resources.displayMetrics.density
        val bubbleSize = (56 * density).toInt()

        // 蓝紫渐变背景
        bgGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF6D28D9.toInt(), 0xFF1E3A5F.toInt())
        ).apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }

        // 颜色循环动画
        colorAnimator = ValueAnimator.ofObject(
            ArgbEvaluator(),
            0xFF6D28D9.toInt(),  // 紫罗兰
            0xFF1E3A5F.toInt(),  // 深海军蓝
            0xFF4C1D95.toInt(),  // 深紫
            0xFF1E40AF.toInt(),  // 蓝
            0xFF6D28D9.toInt()   // 紫罗兰（回到起点）
        ).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                val currColor = anim.animatedValue as Int
                bgGradient?.setColors(intArrayOf(
                    currColor,
                    blendColor(currColor, 0xFF4C1D95.toInt(), 0.4f)
                ))
                invalidateFloatingBg()
            }
        }

        floatingView = TextView(this).apply {
            text = "🔒"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 0)
            setBackground(bgGradient)
            alpha = 0.95f

            val params = WindowManager.LayoutParams(
                bubbleSize, bubbleSize,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (resources.displayMetrics.widthPixels - bubbleSize) / 2
                y = resources.displayMetrics.heightPixels / 3
            }
            windowManager?.addView(this, params)

            setOnTouchListener(FloatingTouchHandler(params, bubbleSize))
            setOnClickListener { togglePopup() }
        }
        colorAnimator?.start()
    }

    private fun invalidateFloatingBg() {
        try { floatingView?.invalidate() } catch (_: Exception) {}
    }

    /** 颜色混合工具 */
    private fun blendColor(color1: Int, color2: Int, ratio: Float): Int {
        val r = ((color1 shr 16 and 0xFF) * (1 - ratio) + (color2 shr 16 and 0xFF) * ratio).toInt()
        val g = ((color1 shr 8 and 0xFF) * (1 - ratio) + (color2 shr 8 and 0xFF) * ratio).toInt()
        val b = ((color1 and 0xFF) * (1 - ratio) + (color2 and 0xFF) * ratio).toInt()
        return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
    }

    // ==================== 弹出菜单（白色圆角卡片） ====================

    private fun togglePopup() {
        if (isPopupShowing) removePopup() else showPopup()
    }

    private fun showPopup() {
        if (popupView != null) return
        val density = resources.displayMetrics.density
        val popupWidth = (280 * density).toInt()

        val composeView = ComposeView(this).apply {
            setContent {
                var master by remember { mutableStateOf(masterOn) }
                var noExit by remember { mutableStateOf(noExit) }
                var noScreenshot by remember { mutableStateOf(noScreenshot) }
                var noRecording by remember { mutableStateOf(noRecording) }

                PrivacyPanel(
                    master = master,
                    onMasterChange = { v ->
                        master = v
                        masterOn = v
                        saveSettings()
                        notifyPrivacy("settings_change", v)
                    },
                    noExit = noExit,
                    onNoExitChange = { v ->
                        if (v) {
                            showPrivacyLockDialog(
                                onConfirm = {
                                    noExit = true
                                    PrivacyFloatingService.noExit = true
                                    saveSettings()
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try { AuroraApi.sendPrivacyRequest(currentFriendId) } catch (_: Exception) {}
                                    }
                                },
                                onCancel = { noExit = false }
                            )
                        } else {
                            noExit = false
                            PrivacyFloatingService.noExit = false
                            saveSettings()
                            Toast.makeText(this@PrivacyFloatingService, "已发送取消锁定请求，等待对方同意", Toast.LENGTH_SHORT).show()
                            CoroutineScope(Dispatchers.IO).launch {
                                try { AuroraApi.sendUnlockRequest(currentFriendId) } catch (_: Exception) {}
                            }
                        }
                        notifyPrivacy("no_exit_toggle", v)
                    },
                    noScreenshot = noScreenshot,
                    onNoScreenshotChange = { v ->
                        noScreenshot = v
                        PrivacyFloatingService.noScreenshot = v
                        saveSettings()
                        notifyPrivacy("no_screenshot_toggle", v)
                    },
                    noRecording = noRecording,
                    onNoRecordingChange = { v ->
                        noRecording = v
                        PrivacyFloatingService.noRecording = v
                        saveSettings()
                        notifyPrivacy("no_recording_toggle", v)
                    },
                    onClose = { togglePopup() },
                    onUnlock = {
                        togglePopup()
                        noExit = false
                        PrivacyFloatingService.noExit = false
                        saveSettings()
                        Toast.makeText(this@PrivacyFloatingService, "已发送取消锁定请求，等待对方同意", Toast.LENGTH_SHORT).show()
                        CoroutineScope(Dispatchers.IO).launch {
                            try { AuroraApi.sendUnlockRequest(currentFriendId) } catch (_: Exception) {}
                        }
                    }
                )
            }
        }
        popupView = composeView
        // 不设 NOT_FOCUSABLE 让 Switch 能接收点击；设 NOT_TOUCH_MODAL 让弹窗外触摸穿透
        val params = WindowManager.LayoutParams(
            popupWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        windowManager?.addView(composeView, params)
        isPopupShowing = true
    }

    private fun notifyPrivacy(type: String, value: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            try { AuroraApi.sendPrivacyAlert(currentFriendId, type, value) } catch (_: Exception) {}
        }
    }

    @Composable
    private fun PrivacyPanel(
        master: Boolean,
        onMasterChange: (Boolean) -> Unit,
        noExit: Boolean,
        onNoExitChange: (Boolean) -> Unit,
        noScreenshot: Boolean,
        onNoScreenshotChange: (Boolean) -> Unit,
        noRecording: Boolean,
        onNoRecordingChange: (Boolean) -> Unit,
        onClose: () -> Unit,
        onUnlock: () -> Unit
    ) {
        Surface(
            modifier = Modifier.width(IntrinsicSize.Max),
            shape = RoundedCornerShape(12.dp),
            color = Color.White
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "隐私控制",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                PanelRow(label = "隐私保护", checked = master, onCheckedChange = onMasterChange)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E7EB)).padding(vertical = 8.dp))
                PanelRow(label = "禁止退出", checked = noExit, enabled = master, onCheckedChange = onNoExitChange)
                PanelRow(label = "禁止截图", checked = noScreenshot, enabled = master, onCheckedChange = onNoScreenshotChange)
                PanelRow(label = "禁止录屏", checked = noRecording, enabled = master, onCheckedChange = onNoRecordingChange)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E7EB)).padding(vertical = 8.dp))
                Text(
                    text = "关闭弹窗",
                    color = Color(0xFF1E40AF),
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().clickable { onClose() }.padding(vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
                if (noExit) {
                    Text(
                        text = "取消锁定",
                        color = Color(0xFFDC2626),
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onUnlock() }.padding(vertical = 12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    private fun PanelRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = Color(0xFF1F2937), modifier = Modifier.weight(1f))
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }

    private fun removePopup() {
        popupView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        (popupView as? ComposeView)?.disposeComposition()
        popupView = null
        isPopupShowing = false
    }

    /** 开启禁止退出时的无障碍权限说明弹窗 */
    private fun showPrivacyLockDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
        try {
            val builder = android.app.AlertDialog.Builder(this)
            builder.setTitle("隐私锁定")
            builder.setMessage("需双方同时给予无障碍权限\n开启后双方将无法退出软件\n双方同意即可退出")
            builder.setPositiveButton("确定") { _, _ ->
                onConfirm()
                // 跳转到无障碍设置
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {
                    Toast.makeText(this, "请手动前往系统设置 → 无障碍 → 开启隐私锁定服务", Toast.LENGTH_LONG).show()
                }
            }
            builder.setNegativeButton("取消") { _, _ -> onCancel() }
            val dialog = builder.create()
            dialog.window?.setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE)
            dialog.show()
        } catch (_: Exception) {}
    }

    private fun saveSettings() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AuroraApi.updatePrivacySettings(currentFriendId, masterOn, noExit, noScreenshot, noRecording, detectScreenshot, detectRecording)
            } catch (_: Exception) {}
        }
        if (masterOn && (noScreenshot || detectScreenshot)) {
            ScreenshotDetector.start(this, currentFriendId)
        } else {
            ScreenshotDetector.stop(this)
        }
        // 取消锁定按钮可见性现由 Compose 面板依据 noExit 状态派生，无需在此手动更新 View
    }

    // ==================== 拖拽处理 ====================

    private inner class FloatingTouchHandler(
        private val params: WindowManager.LayoutParams,
        private val bubbleSize: Int
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var isDragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) isDragging = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(v, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) v.performClick()
                    return true
                }
            }
            return false
        }
    }
}
