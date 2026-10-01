package com.aurora.chat.ui.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import com.aurora.chat.DownloadService
import com.aurora.chat.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import android.content.res.Resources
import java.io.File
import java.io.FileOutputStream
import java.net.URL

data class DownloadItem(
    val id: Long,
    val name: String,
    val fileName: String,
    val iconRes: Int = 0,
    val iconUrl: String = "",
    val packageName: String = "",
    val fileExt: String = "",
    val downloadUrl: String = "",           // 空=默认 tools URL，非空=下载此 URL
    val progress: MutableStateFlow<Int> = MutableStateFlow(0),
    val isDownloading: MutableStateFlow<Boolean> = MutableStateFlow(false),
    val isCompleted: MutableStateFlow<Boolean> = MutableStateFlow(false),
    val isPaused: MutableStateFlow<Boolean> = MutableStateFlow(false),
    val error: MutableStateFlow<String?> = MutableStateFlow(null),
    val speedText: MutableStateFlow<String> = MutableStateFlow(""),
    var downloadId: Long = -1
) {
    val canRetry: Boolean get() = error.value != null && !isDownloading.value && !isCompleted.value
}

object DownloadManager {
    val queue = mutableStateListOf<DownloadItem>()
    val isShowing = mutableStateOf(false)
    val isMinimized = mutableStateOf(false)
    /** 标记需要从外部导航到下载管理页面 */
    val navigateToDownloadManager = mutableStateOf(false)
    private var nextId = 1L
    private var activeJob: Job? = null

    /** 下载完成信号：每次有下载完成时递增，聊天文件卡片据此无感刷新"已下载"状态 */
    val downloadChanged = MutableStateFlow(0L)

    /** 下载协程的异常处理器 —— 防止未捕获异常导致应用闪退 */
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("DownloadManager", "下载协程未捕获异常", throwable)
        // CrashHandler 会自动捕获，这里做额外日志记录
    }

    fun addToQueue(name: String, fileName: String, iconRes: Int, packageName: String = "", downloadUrl: String = "", context: Context? = null) {
        val ext = fileName.substringAfterLast('.').lowercase()
        val item = DownloadItem(
            id = nextId++, name = name, fileName = fileName,
            iconRes = iconRes, packageName = packageName,
            fileExt = ext, downloadUrl = downloadUrl
        )
        queue.add(item)
        isShowing.value = true
        isMinimized.value = false
    }

    /** 社区资源下载（通过 downloadUrl 指定文件地址） */
    fun addCommunityDownload(name: String, fileName: String, downloadUrl: String, iconRes: Int = 0) {
        val ext = fileName.substringAfterLast('.').lowercase()
        val item = DownloadItem(
            id = nextId++, name = name, fileName = fileName,
            iconRes = iconRes, fileExt = ext, downloadUrl = downloadUrl
        )
        queue.add(item)
        isShowing.value = true
        isMinimized.value = false
    }

    /** 删除下载项：移除队列、删除文件、取消任务 */
    fun deleteDownload(context: Context, itemId: Long) {
        val item = queue.find { it.id == itemId } ?: return
        item.isDownloading.value = false
        item.isCompleted.value = false
        item.isPaused.value = false
        // 删除文件
        try {
            val apkFile = File(context.filesDir, "downloads/${item.fileName}")
            if (apkFile.exists()) apkFile.delete()
        } catch (_: Exception) {}
        queue.removeAll { it.id == itemId }
    }

    /** 保存已安装应用的包名 */
    fun savePackageName(context: Context, name: String, pkg: String) {
        context.getSharedPreferences("aurora_installed_pkgs", Context.MODE_PRIVATE)
            .edit().putString(name, pkg).apply()
    }

    /** 获取已保存的应用包名 */
    fun getSavedPackageName(context: Context, appName: String): String {
        return context.getSharedPreferences("aurora_installed_pkgs", Context.MODE_PRIVATE)
            .getString(appName, "") ?: ""
    }

    fun startDownloads(context: Context) {
        if (activeJob?.isActive == true) return
        // 启动前台下载服务
        try {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        } catch (e: Exception) {
            Log.e("DownloadManager", "启动下载服务失败", e)
        }

        activeJob = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler).launch {
            while (isActive) {
                val pending = queue.firstOrNull {
                    !it.isCompleted.value && !it.isDownloading.value && it.error.value == null
                } ?: break
                downloadItem(context, pending)
                // 每个下载之间间隔 800ms，避免服务器/连接池过载
                delay(800L)
            }
            try { context.stopService(Intent(context, DownloadService::class.java)) } catch (_: Exception) {}
        }
    }

    /** 从 APK 文件中读取包名 */
    fun getPackageNameFromApk(context: Context, item: DownloadItem): String {
        if (item.packageName.isNotEmpty()) return item.packageName
        return try {
            val f = File(context.filesDir, "downloads/${item.fileName}")
            if (f.exists()) {
                val pi = context.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
                pi?.packageName ?: ""
            } else ""
        } catch (_: Exception) { "" }
    }

    /** 启动已安装的应用（从下载列表） */
    fun launchApp(context: Context, item: DownloadItem): Boolean {
        val pkg = getPackageNameFromApk(context, item)
        if (pkg.isEmpty()) return false
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } else {
                // 应用未安装，弹出提示
                android.widget.Toast.makeText(context, "应用未安装", android.widget.Toast.LENGTH_SHORT).show()
                false
            }
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, "启动失败", android.widget.Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** 检查是否有安装 APK 的权限（Android 8.0+ 需要 "安装未知应用" 授权） */
    private fun canInstallPackages(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /** 跳转到系统设置开启"安装未知应用"权限 */
    private fun requestInstallPermission(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // 部分旧设备/定制ROM可能没有该页面，跳转应用详情页
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    /** 检查下载的 APK 文件是否有效（验证 ZIP 魔数） */
    private fun isValidApk(file: File): Boolean {
        return try {
            val magic = file.readBytes().take(4).toByteArray()
            // ZIP/APK 魔数: PK\x03\x04
            magic.size == 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() && magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
        } catch (_: Exception) { false }
    }

    /** 触发安装已下载的 APK */
    fun installApp(context: Context, item: DownloadItem) {
        val apkFile = File(context.filesDir, "downloads/${item.fileName}")
        if (!apkFile.exists()) {
            Toast.makeText(context, "APK 文件不存在，请重新下载", Toast.LENGTH_SHORT).show()
            return
        }
        // 验证 APK 文件完整性
        if (!isValidApk(apkFile)) {
            Toast.makeText(context, "安装包已损坏或下载不完整，请检查上传的APK文件是否有效", Toast.LENGTH_LONG).show()
            return
        }
        // Android 8.0+ 需要检查"安装未知应用"权限
        if (!canInstallPackages(context)) {
            Toast.makeText(context, "需要开启「安装未知应用」权限才能安装 APK", Toast.LENGTH_LONG).show()
            requestInstallPermission(context)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val pkgBefore = getPackageNameFromApk(context, item)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            // 保存包名，用于检测已安装状态
            if (pkgBefore.isNotEmpty()) {
                savePackageName(context, item.name, pkgBefore)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "启动安装失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun pauseItem(itemId: Long) {
        queue.find { it.id == itemId }?.let {
            it.isPaused.value = true; it.isDownloading.value = false
        }
    }

    fun resumeItem(context: Context, itemId: Long) {
        val item = queue.find { it.id == itemId } ?: return
        if (!item.isPaused.value) return
        item.isPaused.value = false; item.isDownloading.value = false; item.error.value = null
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            downloadItem(context, item, resetProgress = false)
        }
    }

    fun uninstallApp(context: Context, item: DownloadItem) {
        var pkg = item.packageName
        if (pkg.isEmpty()) {
            try {
                val f = File(context.filesDir, "downloads/${item.fileName}")
                if (f.exists()) {
                    val pi = context.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
                    if (pi != null) pkg = pi.packageName ?: ""
                }
            } catch (_: Exception) {}
        }
        if (pkg.isEmpty()) {
            Toast.makeText(context, "请在应用列表中手动卸载「${item.name}」", Toast.LENGTH_LONG).show()
            try { context.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) {}
            return
        }
        try { context.startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply { data = Uri.parse("package:$pkg"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
        catch (e: Exception) { Toast.makeText(context, "卸载失败: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    fun retryItem(context: Context, itemId: Long) {
        val item = queue.find { it.id == itemId } ?: return
        if (item.isDownloading.value || item.isCompleted.value) return
        item.progress.value = 0; item.isDownloading.value = false
        item.isCompleted.value = false; item.isPaused.value = false; item.error.value = null
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { downloadItem(context, item) }
    }

    // ========== 核心下载 ==========

    private suspend fun downloadItem(context: Context, item: DownloadItem, resetProgress: Boolean = true) {
        item.isDownloading.value = true
        if (resetProgress) item.progress.value = 0
        item.error.value = null; item.speedText.value = ""

        val fileUrl = if (item.downloadUrl.isNotEmpty()) {
            item.downloadUrl
        } else {
            item.error.value = "缺少下载链接"
            item.isDownloading.value = false
            return
        }

        val cacheDir = File(context.filesDir, "downloads")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val apkFile = File(cacheDir, item.fileName)

        // 断点续传：记录已下载字节数
        var downloadedBytes = if (!resetProgress) apkFile.length() else 0L
        val maxRetries = 999
        var retryCount = 0
        var totalSize = -1L

        while (retryCount < maxRetries) {
            if (!item.isDownloading.value || item.isPaused.value) return
            if (retryCount > 0) {
                Log.i("DownloadManager", "${item.name} 重试 $retryCount/$maxRetries")
                item.speedText.value = ""
                // 指数退避: 1s, 2s, 4s, 8s, 16s...
                delay(minOf(1000L * (1L shl (retryCount - 1)), 30000L))
                // 重连前更新已下载字节数
                downloadedBytes = apkFile.length()
            }

            var resp: okhttp3.Response? = null
            var inputStream: java.io.InputStream? = null
            try {
                val reqBuilder = okhttp3.Request.Builder().url(fileUrl)
                    .header("Connection", "close")
                // 断点续传：设置 Range 头
                if (downloadedBytes > 0) {
                    reqBuilder.header("Range", "bytes=$downloadedBytes-")
                }
                // 复用 OkHttp 单例（连接池 + 单例 SSLContext）；OkHttp 处理超时
                resp = com.aurora.chat.data.api.HttpClient.client.newCall(reqBuilder.build()).execute()
                val responseCode = resp.code

                // 206=Partial Content(续传成功), 200=OK(从头开始)
                if (responseCode == 206) {
                    Log.i("DownloadManager", "${item.name} 断点续传: $downloadedBytes bytes 已下载")
                } else if (responseCode == 200) {
                    // 服务器不支持断点续传，从头开始
                    if (downloadedBytes > 0) {
                        downloadedBytes = 0L
                        if (apkFile.exists()) apkFile.delete()
                        item.progress.value = 0
                    }
                } else if (responseCode == 416) {
                    // Range Not Satisfiable → 文件已完整下载
                    item.progress.value = 100
                    item.isDownloading.value = false
                    item.isCompleted.value = true
                    item.speedText.value = ""
                    downloadChanged.value++
                    saveCompletedItems(context)
                    return
                } else {
                    throw java.io.IOException("服务器返回 $responseCode")
                }

                // 获取总大小
                if (responseCode == 206) {
                    val contentRange = resp.header("Content-Range")
                    if (contentRange != null) {
                        val parts = contentRange.split("/")
                        if (parts.size == 2) totalSize = parts[1].toLongOrNull() ?: -1L
                    }
                    if (totalSize <= 0) totalSize = (resp.body?.contentLength() ?: -1) + downloadedBytes
                } else {
                    totalSize = resp.body?.contentLength() ?: -1
                }

                inputStream = resp.body?.byteStream()
                val buffer = ByteArray(8192)
                var totalRead = downloadedBytes
                var lastSpeedTime = System.nanoTime()
                var lastSpeedBytes = totalRead

                FileOutputStream(apkFile, downloadedBytes > 0).use { output ->
                    while (true) {
                        val br = try {
                            inputStream!!.read(buffer)
                        } catch (e: java.net.SocketException) {
                            throw e // 网络断开，触发重试
                        } catch (e: java.io.IOException) {
                            if (e.message?.contains("timeout") == true || e.message?.contains("reset") == true) {
                                throw e
                            }
                            throw e
                        }
                        if (br == -1) break
                        if (item.isPaused.value) return@use
                        output.write(buffer, 0, br)
                        totalRead += br

                        if (totalSize > 0) {
                            val np = ((totalRead * 100) / totalSize).toInt().coerceIn(0, 100)
                            if (np > item.progress.value) item.progress.value = np
                        }

                        val now = System.nanoTime()
                        if (now - lastSpeedTime >= 500_000_000L) {
                            val el = (now - lastSpeedTime) / 1_000_000_000f
                            if (el > 0f) item.speedText.value = formatSpeed(((totalRead - lastSpeedBytes) / el).toLong())
                            lastSpeedTime = now; lastSpeedBytes = totalRead
                        }
                    }
                    downloadedBytes = totalRead
                }

                // 下载成功
                if (item.isPaused.value) return
                item.progress.value = 100
                item.isDownloading.value = false
                item.isCompleted.value = true
                item.speedText.value = ""
                downloadChanged.value++
                saveCompletedItems(context)
                DownloadCountManager.incrementCount(context, item.name)

                if (item.fileExt == "apk" || item.packageName.isNotEmpty()) {
                    try {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                        NotificationHelper.showDownloadCompleteNotification(context, uri, item.name)
                        if (canInstallPackages(context)) {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            context.startActivity(intent)
                        }
                    } catch (_: Exception) {}
                } else {
                    try {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                        NotificationHelper.showDownloadCompleteNotification(context, uri, item.name)
                    } catch (_: Exception) {}
                }
                return // 成功，退出重试循环

            } catch (e: java.net.SocketException) {
                retryCount++
                Log.w("DownloadManager", "${item.name} 网络断开 (${e.message}), 第${retryCount}次重试")
            } catch (e: java.net.ProtocolException) {
                retryCount++
                Log.w("DownloadManager", "${item.name} 连接异常 (${e.message}), 第${retryCount}次重试")
            } catch (e: java.io.IOException) {
                if (e.message?.contains("timed out") == true || e.message?.contains("reset") == true) {
                    retryCount++
                    Log.w("DownloadManager", "${item.name} IO异常 (${e.message}), 第${retryCount}次重试")
                } else {
                    // 非网络类错误不重试
                    item.isDownloading.value = false
                    item.error.value = "下载失败: ${e.message ?: "未知错误"}"
                    item.speedText.value = ""
                    Log.e("DownloadManager", "${item.name} 致命错误", e)
                    return
                }
            } catch (e: Exception) {
                item.isDownloading.value = false
                item.error.value = "下载失败: ${e.message ?: "未知错误"}"
                item.speedText.value = ""
                Log.e("DownloadManager", "${item.name} 未知异常", e)
                return
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                try { resp?.close() } catch (_: Exception) {}
            }
        }

        // 所有重试用尽
        item.isDownloading.value = false
        item.error.value = "下载失败: 已重试${maxRetries}次，网络不稳定"
        item.speedText.value = ""
        Log.e("DownloadManager", "${item.name} 重试${maxRetries}次后仍失败")
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "${item.name} 下载失败，已重试${maxRetries}次", Toast.LENGTH_LONG).show()
        }
    }

    // ========== 持久化（保存资源名称而非 int ID，防止跨 build 资源 ID 变化导致的崩溃） ==========

    private const val PREFS_NAME = "aurora_downloads"
    private const val KEY_ITEMS = "completed_items"

    fun loadCompletedItems(context: Context) {
        try {
            val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, null) ?: return
            val arr = JSONArray(json)
            var loadedCount = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                // 优先从资源名称解析，兼容旧版存储的 int ID
                var iconResId = 0
                val iconName = obj.optString("iconResName", "")
                if (iconName.isNotEmpty()) {
                    iconResId = context.resources.getIdentifier(iconName, "drawable", context.packageName)
                }
                if (iconResId == 0) {
                    // 旧版兼容：验证保存的 int ID 是否仍然有效
                    val savedId = obj.optInt("iconRes", 0)
                    if (savedId != 0) {
                        try {
                            // 尝试获取资源条目名称来验证 ID 有效性
                            context.resources.getResourceEntryName(savedId)
                            iconResId = savedId // 有效，使用它
                        } catch (_: Resources.NotFoundException) {
                            iconResId = 0 // 无效，跨 build 后 ID 变了
                        }
                    }
                }
                val fileExt = obj.optString("fileExt", "")
                val downloadUrl = obj.optString("downloadUrl", "")
                val iconUrl = obj.optString("iconUrl", "")
                if (iconResId == 0 && fileExt.isEmpty() && iconUrl.isEmpty()) {
                    Log.w("DownloadManager", "跳过失效的下载记录: ${obj.optString("name")}")
                    continue
                }
                val item = DownloadItem(
                    id = nextId++, name = obj.getString("name"),
                    fileName = obj.getString("fileName"),
                    iconRes = iconResId,
                    iconUrl = iconUrl,
                    packageName = obj.optString("packageName", ""),
                    fileExt = fileExt,
                    downloadUrl = downloadUrl
                )
                item.isCompleted.value = obj.optBoolean("completed", true)
                item.progress.value = 100
                queue.add(item)
                loadedCount++
            }
            // 如果旧数据全部失效，清理 SharedPreferences 避免下次再解析
            if (loadedCount == 0 && arr.length() > 0) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()
                Log.i("DownloadManager", "已清理所有失效的下载历史记录")
            }
        } catch (e: Exception) {
            Log.e("DownloadManager", "加载下载记录失败", e)
        }
    }

    fun saveCompletedItems(context: Context) {
        try {
            val arr = JSONArray()
            for (item in queue) {
                if (!item.isCompleted.value) continue
                // 保存资源名称而非 int ID
                val iconName = try {
                    context.resources.getResourceEntryName(item.iconRes)
                } catch (_: Exception) { "" }
                val obj = JSONObject().apply {
                    put("name", item.name)
                    put("fileName", item.fileName)
                    put("iconResName", iconName)
                    put("iconRes", item.iconRes)
                    put("iconUrl", item.iconUrl)
                    put("packageName", item.packageName)
                    put("fileExt", item.fileExt)
                    put("downloadUrl", item.downloadUrl)
                    put("completed", item.isCompleted.value)
                }
                arr.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_ITEMS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e("DownloadManager", "保存下载记录失败", e)
        }
    }

    /** 清除所有记录（同时清除持久化） */
    fun clearAllRecords(context: Context) {
        activeJob?.cancel()
        queue.clear()
        isShowing.value = false
        isMinimized.value = false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()
    }

    fun minimize() { isMinimized.value = true; isShowing.value = false }
    fun restore() { isMinimized.value = false; isShowing.value = true }

    fun closeAll() {
        activeJob?.cancel(); queue.clear()
        isShowing.value = false; isMinimized.value = false
    }

    val hasActiveItems: Boolean get() = queue.isNotEmpty()

    /** 是否存在正在下载中的任务 */
    val activeDownloadCount: Int get() = queue.count { it.isDownloading.value }

    /** 所有下载中的平均进度（0-100） */
    val overallProgress: Int get() {
        val downloading = queue.filter { it.isDownloading.value }
        return if (downloading.isNotEmpty()) {
            downloading.map { it.progress.value }.average().toInt()
        } else if (queue.any { it.isCompleted.value }) {
            100
        } else {
            0
        }
    }

    /** 是否存在需要显示悬浮窗的活跃下载（下载中 + 未完成的暂停/失败任务） */
    val shouldShowFloating: Boolean get() {
        if (queue.isEmpty()) return false
        return queue.any { it.isDownloading.value || it.isPaused.value || it.error.value != null }
    }

    private fun formatSpeed(bps: Long): String = when {
        bps >= 1_000_000 -> String.format("%.1f MB/s", bps / 1_000_000f)
        bps >= 1_000 -> String.format("%.0f KB/s", bps / 1_000f)
        bps > 0 -> "$bps B/s"
        else -> ""
    }
}

/**
 * 管理每个应用的下载次数（仅记录成功下载）
 */
object DownloadCountManager {
    private const val PREFS_NAME = "aurora_download_counts"

    /** 获取指定应用的下载次数 */
    fun getCount(context: Context, appName: String): Int {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(appName, 0)
        } catch (_: Exception) { 0 }
    }

    /** 成功下载后递增计数 */
    fun incrementCount(context: Context, appName: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getInt(appName, 0)
            prefs.edit().putInt(appName, current + 1).apply()
        } catch (_: Exception) {}
    }
}
