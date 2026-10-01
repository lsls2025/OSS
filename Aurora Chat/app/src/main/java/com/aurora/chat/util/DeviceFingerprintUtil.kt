package com.aurora.chat.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit


object DeviceFingerprintUtil {

    data class DeviceFingerprint(
        val fingerprintHash: String,
        val deviceInfoJson: String
    )

    /**
     * 收集设备指纹信息，生成唯一哈希
     * 包含：Android ID、制造商、型号、品牌、设备名、硬件、Build指纹、
     *     序列号、安装ID、SDK版本等
     */
    fun collect(context: Context): DeviceFingerprint {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        val brand = Build.BRAND
        val device = Build.DEVICE
        val hardware = Build.HARDWARE
        val fingerprint = Build.FINGERPRINT
        val display = Build.DISPLAY
        val product = Build.PRODUCT
        val board = Build.BOARD
        val bootloader = Build.BOOTLOADER

        val serial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        } catch (_: Exception) { "" }

        val radio = try {
            Build.getRadioVersion()
        } catch (_: Exception) { "" }

        // 安装ID（持久化到SharedPreferences，卸载后丢失）
        val prefs = context.getSharedPreferences("aurora_device_identity", Context.MODE_PRIVATE)
        var installId = prefs.getString("install_id", "")
        if (installId.isNullOrEmpty()) {
            installId = UUID.randomUUID().toString()
            prefs.edit().putString("install_id", installId).apply()
        }

        // 构建原始字符串（所有特征组合）
        val raw = listOf(
            androidId, manufacturer, model, brand, device,
            hardware, fingerprint, serial, installId,
            display, product, board, bootloader, radio,
            Build.VERSION.SDK_INT.toString(),
            Build.VERSION.INCREMENTAL,
            Build.TAGS ?: "",
            Build.TYPE ?: ""
        ).joinToString("|")

        // SHA-256 哈希
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }

        // 构建设备信息JSON（用于后端审计）
        val infoJson = JSONObject().apply {
            put("android_id", androidId)
            put("manufacturer", manufacturer)
            put("model", model)
            put("brand", brand)
            put("device", device)
            put("hardware", hardware)
            put("fingerprint", fingerprint)
            put("serial", serial)
            put("install_id", installId)
            put("display", display)
            put("product", product)
            put("board", board)
            put("bootloader", bootloader)
            put("radio", radio)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("incremental", Build.VERSION.INCREMENTAL)
            put("tags", Build.TAGS ?: "")
            put("type", Build.TYPE ?: "")
            put("host", Build.HOST ?: "")
            put("user", Build.USER ?: "")
        }.toString()

        return DeviceFingerprint(hash, infoJson)
    }

    /**
     * 判断当前设备是否为模拟器
     */
    fun isEmulator(): Boolean {
        return Build.MODEL.contains("google_sdk", ignoreCase = true) ||
                Build.MODEL.lowercase().contains("emulator") ||
                Build.MODEL.contains("Android SDK built for x86", ignoreCase = true) ||
                Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
                Build.PRODUCT == "google_sdk" ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true)
    }

    /**
     * 获取应用首次安装时间到当前时间的秒数
     */
    fun getAppInstallSeconds(context: Context): Long {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName
            val firstInstallTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L)).firstInstallTime
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0).firstInstallTime
            }
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - firstInstallTime)
        } catch (_: Exception) {
            0L
        }
    }
}
