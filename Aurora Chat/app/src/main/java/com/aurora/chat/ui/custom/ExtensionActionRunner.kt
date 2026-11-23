package com.aurora.chat.ui.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.aurora.chat.MainActivity

/**
 * 与悬浮窗服务无关的动作执行器：预览、编辑器里也能真触发事件。
 *
 * 覆盖所有不需要悬浮窗窗口本身的动作（硬件 / 权限页 / 系统面板 / 跳转 / 数据 / 杂项）。
 * 只在悬浮窗运行时有意义的动作（收起 / 关闭 / 锁定 / 穿透 / 显隐元素等）在预览里
 * 提示一句就跳过，不报错。
 */
object ExtensionActionRunner {

    private const val MANAGE_ALL_FILES = "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"

    private var torchOn = false
    private var torchLevel = 1
    private var tts: TextToSpeech? = null
    private var sosRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun run(ctx: Context, action: ElementAction, rawValue: String) {
        ExtensionSafety.guardRun(ctx, "执行预览动作") {
            val value = DynamicValues.resolve(rawValue, ctx)
            when (action) {
                // 硬件
                ElementAction.FLASHLIGHT -> toggleFlashlight(ctx)
                ElementAction.TORCH_SOS -> toggleTorchSos(ctx)
                ElementAction.TORCH_LEVEL -> setTorchLevel(ctx, value)
                ElementAction.VIBRATE -> vibrate(ctx, value)
                ElementAction.VIBRATE_PATTERN -> vibratePattern(ctx, value)
                ElementAction.MEDIA_PLAY -> mediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                ElementAction.MEDIA_NEXT -> mediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                ElementAction.MEDIA_PREV -> mediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                ElementAction.VOLUME_UP -> adjustVolume(ctx, AudioManager.ADJUST_RAISE)
                ElementAction.VOLUME_DOWN -> adjustVolume(ctx, AudioManager.ADJUST_LOWER)
                ElementAction.VOLUME_MUTE -> toggleMute(ctx)
                ElementAction.WAKE_SCREEN -> wakeScreen(ctx)
                ElementAction.SCREEN_BRIGHT -> setBrightness(ctx, value)
                ElementAction.AUTO_BRIGHTNESS -> toggleAutoBrightness(ctx)
                ElementAction.ROTATION_LOCK -> toggleRotationLock(ctx)
                ElementAction.RINGER_MODE -> setRinger(ctx, value)
                ElementAction.DND_TOGGLE -> toggleDnd(ctx)

                // 权限 / 系统面板
                ElementAction.PERM_WRITE_SETTINGS ->
                    openSystemPage(ctx, Settings.ACTION_MANAGE_WRITE_SETTINGS, withPackage = true)
                ElementAction.PERM_DND ->
                    openSystemPage(ctx, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                ElementAction.PERM_OVERLAY ->
                    openSystemPage(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackage = true)
                ElementAction.PERM_BATTERY ->
                    openSystemPage(ctx, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                ElementAction.PERM_USAGE ->
                    openSystemPage(ctx, Settings.ACTION_USAGE_ACCESS_SETTINGS)
                ElementAction.PERM_ALL_FILES -> openSystemPage(ctx, MANAGE_ALL_FILES)
                ElementAction.APP_DETAILS ->
                    openSystemPage(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true)
                ElementAction.SET_TIMER -> setTimer(ctx, value)
                ElementAction.SET_ALARM -> setAlarm(ctx, value)
                ElementAction.WIFI_PANEL -> openSystemPage(ctx, Settings.ACTION_WIFI_SETTINGS)
                ElementAction.BT_PANEL -> openSystemPage(ctx, Settings.ACTION_BLUETOOTH_SETTINGS)
                ElementAction.NFC_PANEL -> openSystemPage(ctx, Settings.ACTION_NFC_SETTINGS)
                ElementAction.LOCATION_PANEL ->
                    openSystemPage(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                ElementAction.SOUND_PANEL -> openSystemPage(ctx, Settings.ACTION_SOUND_SETTINGS)
                ElementAction.AIRPLANE_PANEL ->
                    openSystemPage(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                ElementAction.BATTERY_PANEL ->
                    openSystemPage(ctx, Settings.ACTION_BATTERY_SAVER_SETTINGS)
                ElementAction.PRIVACY_PANEL -> openSystemPage(ctx, Settings.ACTION_PRIVACY_SETTINGS)

                // 跳转
                ElementAction.BACK_TO_APP -> backToApp(ctx)
                ElementAction.OPEN_URL -> openUrl(ctx, value)
                ElementAction.OPEN_APP -> openApp(ctx, value)
                ElementAction.OPEN_SETTINGS -> openSettings(ctx, value)
                ElementAction.DIAL -> dial(ctx, value)
                ElementAction.SMS -> sms(ctx, value)

                // 数据
                ElementAction.TTS_SPEAK -> speak(ctx, value)
                ElementAction.TIMER_TOGGLE -> HostTimer.toggle()
                ElementAction.TIMER_RESET -> HostTimer.reset()

                // 杂项
                ElementAction.TOAST -> toast(ctx, value)
                ElementAction.COPY_TEXT -> copyText(ctx, value)
                ElementAction.SHARE_TEXT -> shareText(ctx, value)

                // 只在悬浮窗运行时有意义：预览里说明一句即可
                ElementAction.NONE -> Unit
                ElementAction.COLLAPSE, ElementAction.EXPAND, ElementAction.CLOSE,
                ElementAction.TOGGLE_LOCK, ElementAction.REFRESH, ElementAction.TOUCH_THROUGH,
                ElementAction.TOGGLE_ELEMENT, ElementAction.KEEP_SCREEN_ON ->
                    toast(ctx, "该动作用于悬浮窗，实际运行时生效")
            }
        }
    }

    // ────────────────────────── 硬件 ──────────────────────────

    private fun torchCamera(cm: CameraManager): String? = cm.cameraIdList.firstOrNull { id ->
        runCatching {
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }.getOrDefault(false)
    }

    private fun setTorch(ctx: Context, on: Boolean, quiet: Boolean = false) {
        runCatching {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: throw IllegalStateException("no camera service")
            val cam = torchCamera(cm) ?: throw IllegalStateException("no flash")
            cm.setTorchMode(cam, on)
            torchOn = on
            DynamicValues.torchOn = on
            if (!quiet) toast(ctx, if (on) "手电筒已开" else "手电筒已关")
        }.onFailure { if (!quiet) toast(ctx, "无法控制手电筒") }
    }

    private fun toggleFlashlight(ctx: Context) = setTorch(ctx, !torchOn)

    private fun toggleTorchSos(ctx: Context) {
        if (sosRunnable != null) {
            sosRunnable?.let { mainHandler.removeCallbacks(it) }
            sosRunnable = null
            setTorch(ctx, false, quiet = true)
            toast(ctx, "爆闪已停")
            return
        }
        var on = false
        val r = object : Runnable {
            override fun run() {
                if (sosRunnable !== this) return
                on = !on
                setTorch(ctx, on, quiet = true)
                mainHandler.postDelayed(this, 110L)
            }
        }
        sosRunnable = r
        mainHandler.post(r)
        toast(ctx, "爆闪中，再点一次停止")
    }

    private fun setTorchLevel(ctx: Context, value: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toggleFlashlight(ctx)
            return
        }
        runCatching {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: throw IllegalStateException("no camera service")
            val cam = torchCamera(cm) ?: throw IllegalStateException("no flash")
            val max = cm.getCameraCharacteristics(cam)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            if (max <= 1) {
                toggleFlashlight(ctx)
                return
            }
            val want = value.trim().toIntOrNull()
            torchLevel = (want ?: (torchLevel % max) + 1).coerceIn(1, max)
            cm.turnOnTorchWithStrengthLevel(cam, torchLevel)
            torchOn = true
            DynamicValues.torchOn = true
            toast(ctx, "手电亮度 $torchLevel/$max")
        }.onFailure {
            toast(ctx, "该设备不支持分级亮度")
            toggleFlashlight(ctx)
        }
    }

    private fun vibrator(ctx: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private fun vibrate(ctx: Context, value: String) {
        val ms = value.trim().toLongOrNull()?.coerceIn(10L, 2000L) ?: 80L
        val v = vibrator(ctx) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        }
    }

    private fun vibratePattern(ctx: Context, value: String) {
        val parts = value.split(",", " ", "，").mapNotNull { it.trim().toLongOrNull() }
        if (parts.isEmpty()) {
            vibrate(ctx, "80")
            return
        }
        val v = vibrator(ctx) ?: return
        runCatching {
            val pattern = LongArray(parts.size) { parts[it].coerceIn(0L, 2000L) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, -1)
            }
        }
    }

    private fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }.onFailure { toast(ctx, "媒体键不可用") }
    }

    private fun adjustVolume(ctx: Context, dir: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }.onFailure { toast(ctx, "音量调节失败") }
    }

    private fun toggleMute(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            val muted = am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (muted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE,
                0
            )
            toast(ctx, if (muted) "已取消静音" else "已静音")
        }.onFailure { toast(ctx, "静音切换失败") }
    }

    private fun wakeScreen(ctx: Context) {
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "aurora:custom:preview_wake"
            )
            lock.acquire(1500L)
            toast(ctx, "已点亮屏幕")
        }.onFailure { toast(ctx, "无法点亮屏幕") }
    }

    private fun ensureWriteSettings(ctx: Context): Boolean {
        if (Settings.System.canWrite(ctx)) return true
        openSystemPage(
            ctx, Settings.ACTION_MANAGE_WRITE_SETTINGS, withPackage = true,
            tip = "需要先允许修改系统设置"
        )
        return false
    }

    private fun setBrightness(ctx: Context, value: String) {
        val n = value.trim().toIntOrNull()
        if (n == null) {
            toast(ctx, "亮度填 0-255")
            return
        }
        if (!ensureWriteSettings(ctx)) return
        runCatching {
            val v = n.coerceIn(1, 255)
            Settings.System.putInt(
                ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            toast(ctx, "亮度已设为 $v")
        }.onFailure { toast(ctx, "亮度设置失败") }
    }

    private fun toggleAutoBrightness(ctx: Context) {
        if (!ensureWriteSettings(ctx)) return
        runCatching {
            val cur = Settings.System.getInt(
                ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val auto = cur != Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            Settings.System.putInt(
                ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            toast(ctx, if (auto) "自动亮度已开" else "自动亮度已关")
        }.onFailure { toast(ctx, "自动亮度切换失败") }
    }

    private fun toggleRotationLock(ctx: Context) {
        if (!ensureWriteSettings(ctx)) return
        runCatching {
            val cur = Settings.System.getInt(
                ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
            )
            Settings.System.putInt(
                ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                if (cur == 1) 0 else 1
            )
            toast(ctx, if (cur == 1) "已锁定竖屏" else "已恢复自动旋转")
        }.onFailure { toast(ctx, "旋转设置失败") }
    }

    private fun ensureDndAccess(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        if (nm?.isNotificationPolicyAccessGranted == true) return true
        openSystemPage(
            ctx, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            tip = "需要先授予勿扰权限"
        )
        return false
    }

    private fun setRinger(ctx: Context, value: String) {
        if (!ensureDndAccess(ctx)) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = when (value.trim().lowercase()) {
            "silent", "静音" -> AudioManager.RINGER_MODE_SILENT
            "vibrate", "振动" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        runCatching {
            am.setRingerMode(mode)
            toast(ctx, "铃音模式已切换")
        }.onFailure { toast(ctx, "铃音模式切换失败") }
    }

    private fun toggleDnd(ctx: Context) {
        if (!ensureDndAccess(ctx)) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return
        runCatching {
            val all = nm.currentInterruptionFilter ==
                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            nm.setInterruptionFilter(
                if (all) android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            )
            toast(ctx, if (all) "勿扰已开" else "勿扰已关")
        }.onFailure { toast(ctx, "勿扰切换失败") }
    }

    private fun speak(ctx: Context, value: String) {
        if (value.isBlank()) return
        runCatching {
            val t = tts ?: TextToSpeech(ctx) { }.also { tts = it }
            t.setLanguage(java.util.Locale.getDefault())
            @Suppress("DEPRECATION")
            t.speak(value, TextToSpeech.QUEUE_FLUSH, Bundle.EMPTY, "aurora_preview")
        }.onFailure { toast(ctx, "语音朗读不可用") }
    }

    // ────────────────────────── 系统页面与跳转 ──────────────────────────

    private fun openSystemPage(
        ctx: Context,
        action: String,
        withPackage: Boolean = false,
        tip: String? = null
    ) {
        tip?.let { toast(ctx, it) }
        runCatching {
            val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (withPackage) i.data = Uri.parse("package:${ctx.packageName}")
            ctx.startActivity(i)
        }.onFailure { toast(ctx, "系统不支持这个设置页") }
    }

    private fun setTimer(ctx: Context, value: String) {
        val sec = value.trim().toIntOrNull()?.coerceIn(1, 86400) ?: 60
        runCatching {
            ctx.startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, sec)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast(ctx, "无法启动系统计时器") }
    }

    private fun setAlarm(ctx: Context, value: String) {
        val parts = value.trim().split(":", "：")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 30
        runCatching {
            ctx.startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h.coerceIn(0, 23))
                    .putExtra(AlarmClock.EXTRA_MINUTES, m.coerceIn(0, 59))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast(ctx, "无法设置闹钟") }
    }

    private fun openSettings(ctx: Context, key: String) {
        val target = when (key.trim().lowercase()) {
            "wifi", "wlan" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth", "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "notification" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            "privacy" -> Settings.ACTION_PRIVACY_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "vpn" -> Settings.ACTION_VPN_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        runCatching {
            val i = Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (target == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            }
            ctx.startActivity(i)
        }.onFailure { toast(ctx, "打不开这个设置页") }
    }

    private fun backToApp(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
    }

    private fun openUrl(ctx: Context, value: String) {
        val v = normalizeUrl(value) ?: run {
            toast(ctx, "网址为空或无法识别")
            return
        }
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(v)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast(ctx, "没有能打开网页的应用") }
    }

    /** 自动补 https:// 前缀；用户只填 baidu.com 也能直接跳系统浏览器。 */
    private fun normalizeUrl(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        return when {
            v.startsWith("http://") || v.startsWith("https://") -> v
            v.contains(".") && !v.contains(" ") -> "https://$v"
            else -> null
        }
    }

    private fun openApp(ctx: Context, value: String) {
        val pkg = value.trim()
        if (pkg.isEmpty()) return
        val intent = runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    private fun dial(ctx: Context, value: String) {
        val n = value.trim()
        if (n.isEmpty()) return
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun sms(ctx: Context, value: String) {
        val n = value.trim()
        if (n.isEmpty()) return
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun copyText(ctx: Context, value: String) {
        if (value.isEmpty()) return
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("aurora", value))
            toast(ctx, "已复制")
        }
    }

    private fun shareText(ctx: Context, value: String) {
        if (value.isEmpty()) return
        runCatching {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, value)
            }
            ctx.startActivity(Intent.createChooser(i, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun toast(ctx: Context, text: String) {
        if (text.isBlank()) return
        runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
    }
}
