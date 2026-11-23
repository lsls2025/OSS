package com.aurora.chat.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * 公共目录读写权限的懒唤起工具。
 *
 * 应用启动时不再强制要求"文件管理权限"（避免部分机型/新版系统在启动阶段无法授权，
 * 用户又不知道去哪开启）。只在用户真正要写入/读取公共目录（如导出文件到 Downloads）
 * 时才调用 [hasPublicStorageAccess] 判断、必要时用 [openAllFilesSettings] 唤起设置页。
 *
 * 说明：应用自身的下载、私有目录读写（filesDir/getExternalFilesDir）都不需要本权限，
 * 因此日常使用完全不受影响。
 */
object StorageAccess {

    /** 当前是否具备写/读公共目录的能力（无需触发设置页） */
    fun hasPublicStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：所有文件访问权限（MANAGE_EXTERNAL_STORAGE）
            Environment.isExternalStorageManager()
        } else {
            // Android 10 及以下：WRITE_EXTERNAL_STORAGE 运行时权限
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 唤起系统设置页授予公共目录访问权限。
     * Android 11+ 优先跳"所有文件访问"专用页，不可用则兜底应用详情页（始终可用）。
     */
    fun openAllFilesSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            if (intent.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(intent)
                return
            }
        }
        openAppDetails(activity)
    }

    private fun openAppDetails(activity: Activity) {
        try {
            activity.startActivity(Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}")
            ))
        } catch (_: Exception) {}
    }
}