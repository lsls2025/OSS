package com.aurora.chat.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 设备指纹 + 跨卸载痕迹（防批量小号注册）
 *
 * 设计要点：
 * 1. 设备指纹：Android ID + 机型/厂商特征值，卸载应用后不变（除非恢复出厂）。
 * 2. 痕迹散落在 10 个公共目录（Download/Pictures/DCIM/Documents...），卸载 App 后文件仍在，
 *    重装可读取 → 实现"跨卸载追踪"。单点被清理不至于失守（多目录冗余）。
 * 3. 每个痕迹文件内容为 "aurora|v1|<指纹>|<已注册账号数>"，账号数取所有痕迹中的最大值。
 * 4. 单设备最多注册 2 个账号，超过则第 3 次注册被拒绝。
 */
object DeviceTracker {
    private const val TAG = "DeviceTracker"
    private const val TRACE_PREFIX = ".aurora_d"
    private const val TRACE_SUFFIX = ".cache"
    const val MAX_ACCOUNTS_PER_DEVICE = 2

    // 散落多个公共目录，覆盖 5~10 个痕迹点
    private val TRACE_DIRS = listOf(
        Environment.DIRECTORY_DOWNLOADS,
        Environment.DIRECTORY_PICTURES,
        Environment.DIRECTORY_DCIM,
        Environment.DIRECTORY_DOCUMENTS,
        Environment.DIRECTORY_MOVIES,
        Environment.DIRECTORY_MUSIC,
        Environment.DIRECTORY_RINGTONES,
        Environment.DIRECTORY_ALARMS,
        Environment.DIRECTORY_PODCASTS,
        Environment.DIRECTORY_SCREENSHOTS
    )

    /** 计算设备指纹（SHA-256 摘要，不存明文） */
    fun getFingerprint(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val raw = "$androidId|${Build.BRAND}|${Build.MODEL}|${Build.MANUFACTURER}|${Build.DEVICE}"
        return sha256(raw)
    }

    private fun sha256(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 读取当前设备已注册账号数（取所有痕迹中的最大值），0 表示本机从未注册过 */
    fun countRegisteredAccounts(): Int {
        var max = 0
        for (dir in TRACE_DIRS) {
            val d = Environment.getExternalStoragePublicDirectory(dir)
            if (!d.isDirectory) continue
            d.listFiles { f -> f.name.startsWith(TRACE_PREFIX) && f.name.endsWith(TRACE_SUFFIX) }?.forEach { f ->
                val c = readCount(f)
                if (c > max) max = c
            }
        }
        return max
    }

    /** 是否还能继续注册（未满上限） */
    fun canRegisterMore(): Boolean = countRegisteredAccounts() < MAX_ACCOUNTS_PER_DEVICE

    /** 注册成功后记录 +1，并写入所有痕迹点 */
    fun recordRegistration(fingerprint: String): Boolean {
        val next = countRegisteredAccounts() + 1
        if (next > MAX_ACCOUNTS_PER_DEVICE) return false
        writeAll(fingerprint, next)
        return true
    }

    /** 应用启动时调用：若已有痕迹则重新写入全部痕迹点，防止个别目录被清理导致"痕迹失效" */
    fun ensureTraces(fingerprint: String) {
        val c = countRegisteredAccounts()
        if (c > 0) writeAll(fingerprint, c)
    }

    private fun writeAll(fingerprint: String, count: Int) {
        for (dir in TRACE_DIRS) {
            try {
                val d = Environment.getExternalStoragePublicDirectory(dir)
                if (!d.exists()) d.mkdirs()
                if (!d.canWrite()) continue
                val file = File(d, "$TRACE_PREFIX${fingerprint.take(8)}$TRACE_SUFFIX")
                file.writeText("aurora|v1|$fingerprint|$count")
            } catch (e: Exception) {
                Log.w(TAG, "写入痕迹失败: $dir -> ${e.message}")
            }
        }
    }

    private fun readCount(f: File): Int = try {
        f.readText().split("|").lastOrNull()?.toIntOrNull() ?: 0
    } catch (_: Exception) { 0 }
}
