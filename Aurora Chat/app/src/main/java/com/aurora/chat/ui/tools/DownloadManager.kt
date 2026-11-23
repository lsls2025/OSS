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
import java.io.RandomAccessFile
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

data class DownloadItem(
    val id: Long,
    val name: String,
    val fileName: String,
    val iconRes: Int = 0,
    val iconUrl: String = "",
    val packageName: String = "",
    val fileExt: String = "",
    val downloadUrl: String = "",           // 空=默认 tools URL，非空=下载此 URL
    /** 网页下载才需要：登录态 Cookie / 来源页，随请求一起带上 */
    val cookie: String = "",
    val referer: String = "",
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
    fun addCommunityDownload(
        name: String,
        fileName: String,
        downloadUrl: String,
        iconRes: Int = 0,
        cookie: String = "",
        referer: String = "",
    ) {
        val ext = fileName.substringAfterLast('.').lowercase()
        val item = DownloadItem(
            id = nextId++, name = name, fileName = fileName,
            iconRes = iconRes, fileExt = ext, downloadUrl = downloadUrl,
            cookie = cookie, referer = referer
        )
        queue.add(item)
        isShowing.value = true
        isMinimized.value = false
    }

    /** 网页里的下载请求补上 Cookie / Referer（很多站点的下载直链要登录态才给文件） */
    private fun okhttp3.Request.Builder.withWebAuth(item: DownloadItem): okhttp3.Request.Builder = apply {
        if (item.cookie.isNotEmpty()) header("Cookie", item.cookie)
        if (item.referer.isNotEmpty()) header("Referer", item.referer)
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

    /**
     * 构造安装意图：ACTION_VIEW + ClipData + 读权限 flag。
     * ClipData 不能省：只给 flag 不给 ClipData 时，MIUI 等 ROM 拿不到读权限，
     * 表现就是「安装器一闪 / 解析包时出现问题」。
     */
    private fun apkViewIntent(context: Context, uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        clipData = android.content.ClipData.newRawUri("apk", uri)
    }

    /** 逐个把读权限授予能响应安装意图的应用，避免授权只落在「默认安装器」上。 */
    private fun grantApkUriPermission(context: Context, intent: Intent, uri: Uri) {
        runCatching {
            context.packageManager.queryIntentActivities(intent, 0).forEach { info ->
                context.grantUriPermission(
                    info.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    /**
     * 拉起系统安装器（APK）。
     *
     * 定制 ROM（小米/MIUI、部分华为/OPPO）上「点了没反应」几乎都出在权限传递上，所以这里补全：
     *  1. FileProvider + FLAG_GRANT_READ_URI_PERMISSION 是基础；
     *  2. **同时 setClipData**：不少 ROM 只认 ClipData 里的 uri 授权，只给 flag 会报「解析包时出现问题」；
     *  3. 对每个能响应安装意图的 Activity 单独 grantUriPermission，避免授权只落在默认安装器上；
     *  4. ACTION_VIEW 失败时退回旧版 ACTION_INSTALL_PACKAGE（另一条系统安装通道，能救一部分 ROM）。
     *
     * @return true = 已成功拉起安装器
     */
    fun openApkInstaller(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) {
            Toast.makeText(context, "APK 文件不存在，请重新下载", Toast.LENGTH_SHORT).show()
            return false
        }
        // 验证 APK 文件完整性
        if (!isValidApk(apkFile)) {
            Toast.makeText(context, "安装包已损坏或下载不完整，请检查上传的APK文件是否有效", Toast.LENGTH_LONG).show()
            return false
        }
        // Android 8.0+ 需要检查"安装未知应用"权限
        if (!canInstallPackages(context)) {
            Toast.makeText(context, "需要开启「安装未知应用」权限才能安装 APK", Toast.LENGTH_LONG).show()
            requestInstallPermission(context)
            return false
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        }.getOrNull() ?: return false

        val intent = apkViewIntent(context, uri)
        grantApkUriPermission(context, intent, uri)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w("DownloadManager", "ACTION_VIEW 拉起安装器失败，退回 ACTION_INSTALL_PACKAGE", e)
            // 旧版安装意图：另一条系统通道，能救「明明装了安装器却起不来」的定制 ROM
            val legacy = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = android.content.ClipData.newRawUri("apk", uri)
            }
            try {
                context.startActivity(legacy)
                true
            } catch (e2: Exception) {
                Toast.makeText(context, "启动安装失败: ${e2.message}", Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    /** 触发安装已下载的 APK */
    fun installApp(context: Context, item: DownloadItem) {
        val apkFile = File(context.filesDir, "downloads/${item.fileName}")
        // 保存包名，用于检测已安装状态
        val pkgBefore = runCatching { getPackageNameFromApk(context, item) }.getOrDefault("")
        if (openApkInstaller(context, apkFile) && pkgBefore.isNotEmpty()) {
            savePackageName(context, item.name, pkgBefore)
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

    // ========== 核心下载（多线程分片 + EMA 平滑） ==========

    private const val DOWNLOAD_SEGMENTS = 6
    private const val SINGLE_CONN_THRESHOLD = 4L * 1024 * 1024

    private data class Segment(val start: Long, val end: Long)

    /** 速度采样协程：用指数移动平均(EMA)平滑，避免数字一跳一跳 */
    private fun launchSpeedSampler(item: DownloadItem, counter: AtomicLong): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            var lastTime = System.nanoTime()
            var lastBytes = 0L
            var ema = 0L
            while (isActive) {
                delay(500)
                if (!item.isDownloading.value) break
                val now = System.nanoTime()
                val el = (now - lastTime) / 1_000_000_000f
                val cur = counter.get()
                if (el > 0f && cur >= lastBytes) {
                    val inst = ((cur - lastBytes) / el).toLong()
                    ema = if (ema == 0L) inst else (ema * 6 + inst * 4) / 10
                    item.speedText.value = formatSpeed(ema)
                }
                lastTime = now
                lastBytes = cur
            }
        }
    }

    /** 下载成功统一处理：置满进度、持久化、通知/安装 */
    private fun finishSuccess(context: Context, item: DownloadItem, apkFile: File) {
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
                    // 和 installApp 走同一套意图（ClipData + 逐应用授权），
                    // 否则小米等 ROM 上下载完成自动拉起安装器会「点了没反应」。
                    val intent = apkViewIntent(context, uri)
                    grantApkUriPermission(context, intent, uri)
                    context.startActivity(intent)
                }
            } catch (_: Exception) {}
        } else {
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                val mime = when (item.fileExt) {
                    "txt", "log", "md", "json", "xml", "csv", "js", "kt", "java", "py", "html", "css" -> "text/plain"
                    "pdf" -> "application/pdf"
                    "zip", "rar", "7z" -> "application/zip"
                    "mp3", "amr", "m4a", "wav", "ogg", "aac" -> "audio/*"
                    "mp4", "mkv", "mov", "avi", "webm", "3gp" -> "video/*"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "gif" -> "image/gif"
                    "webp" -> "image/webp"
                    else -> "*/*"
                }
                NotificationHelper.showDownloadCompleteNotification(context, uri, item.name, mime, "打开")
            } catch (_: Exception) {}
        }
    }

    private suspend fun downloadItem(context: Context, item: DownloadItem, resetProgress: Boolean = true) {
        item.isDownloading.value = true
        if (resetProgress) item.progress.value = 0
        item.error.value = null
        item.speedText.value = ""

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

        // 续传：已有部分文件则从已有长度继续（仅单连接安全）
        val initialBytes = if (!resetProgress && apkFile.exists()) apkFile.length() else 0L
        if (resetProgress && apkFile.exists()) apkFile.delete()

        // 探测大小 + 是否支持 Range（分片/续传前提）
        var totalSize = -1L
        var rangeSupported = false
        try {
            com.aurora.chat.data.api.HttpClient.client.newCall(
                okhttp3.Request.Builder().url(fileUrl).header("Range", "bytes=0-0").withWebAuth(item).build()
            ).execute().use { resp ->
                if (resp.code == 206) {
                    val cr = resp.header("Content-Range")
                    if (cr != null && !cr.endsWith("/*")) {
                        val p = cr.split("/")
                        if (p.size == 2) {
                            totalSize = p[1].toLongOrNull() ?: -1L
                            if (totalSize > 0) rangeSupported = true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        when {
            initialBytes > 0 -> singleConnectionDownload(context, item, apkFile, fileUrl, totalSize, initialBytes)
            rangeSupported && totalSize >= SINGLE_CONN_THRESHOLD -> multiSegmentDownload(context, item, apkFile, fileUrl, totalSize)
            else -> singleConnectionDownload(context, item, apkFile, fileUrl, totalSize, 0)
        }
    }

    /** 单连接下载（兜底 / 续传），带 EMA 速度平滑 */
    private suspend fun singleConnectionDownload(
        context: Context, item: DownloadItem, apkFile: File, fileUrl: String, knownTotal: Long, initialBytes: Long
    ) {
        val counter = AtomicLong(initialBytes)
        val sampler = launchSpeedSampler(item, counter)
        var totalSize = knownTotal
        var retry = 0
        try {
            while (retry < 999) {
                if (item.isPaused.value || !item.isDownloading.value) return
                try {
                    val reqBuilder = okhttp3.Request.Builder().url(fileUrl).withWebAuth(item)
                    if (counter.get() > 0) reqBuilder.header("Range", "bytes=${counter.get()}-")
                    com.aurora.chat.data.api.HttpClient.client.newCall(reqBuilder.build()).execute().use { resp ->
                        when (val code = resp.code) {
                            206 -> {
                                val cr = resp.header("Content-Range")
                                if (totalSize <= 0 && cr != null) {
                                    val p = cr.split("/")
                                    if (p.size == 2) totalSize = p[1].toLongOrNull() ?: totalSize
                                }
                                if (totalSize <= 0) totalSize = (resp.body?.contentLength() ?: -1) + counter.get()
                            }
                            200 -> {
                                if (counter.get() > 0) {
                                    counter.set(0)
                                    if (apkFile.exists()) apkFile.delete()
                                    item.progress.value = 0
                                }
                                totalSize = resp.body?.contentLength() ?: -1
                            }
                            416 -> item.progress.value = 100
                            else -> throw java.io.IOException("服务器返回 $code")
                        }
                        val stream = resp.body?.byteStream() ?: throw java.io.IOException("空响应")
                        FileOutputStream(apkFile, counter.get() > 0).use { out ->
                            val buf = ByteArray(8192)
                            var n: Int
                            while (stream.read(buf).also { n = it } != -1) {
                                if (item.isPaused.value || !item.isDownloading.value) return
                                out.write(buf, 0, n)
                                val total = counter.addAndGet(n.toLong())
                                if (totalSize > 0) {
                                    val prog = ((total * 100) / totalSize).toInt().coerceIn(0, 100)
                                    if (prog > item.progress.value) item.progress.value = prog
                                }
                            }
                        }
                    }
                    break
                } catch (e: Exception) {
                    retry++
                    if (retry >= 999) throw e
                    delay(minOf(1000L * (1L shl (retry - 1)), 30000L))
                }
            }
        } catch (e: Exception) {
            item.isDownloading.value = false
            item.error.value = "下载失败: ${e.message ?: "未知错误"}"
            item.speedText.value = ""
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "${item.name} 下载失败", Toast.LENGTH_LONG).show()
            }
            return
        } finally {
            sampler.cancel()
        }
        if (item.isPaused.value || !item.isDownloading.value) return
        finishSuccess(context, item, apkFile)
    }

    /** 多线程分片下载：文件切 N 段并行拉取，提速 + 波动更平滑 */
    private suspend fun multiSegmentDownload(
        context: Context, item: DownloadItem, apkFile: File, fileUrl: String, totalSize: Long
    ) {
        val raw = (totalSize / (2L * 1024 * 1024)).toInt().coerceAtLeast(2)
        val segCount = if (raw > DOWNLOAD_SEGMENTS) DOWNLOAD_SEGMENTS else raw
        val segLen = totalSize / segCount
        val segs = ArrayList<Segment>(segCount)
        for (i in 0 until segCount) {
            val start = i * segLen
            val end = if (i == segCount - 1) totalSize - 1 else start + segLen - 1
            segs.add(Segment(start, end))
        }

        // 预分配整文件，分片各自 seek 写入对应区间
        RandomAccessFile(apkFile, "rw").use { it.setLength(totalSize) }

        val counter = AtomicLong(0)
        val sampler = launchSpeedSampler(item, counter)
        try {
            coroutineScope {
                val jobs = segs.map { seg ->
                    async(Dispatchers.IO) {
                        var localDone = 0L
                        var retry = 0
                        while (retry < 999) {
                            if (item.isPaused.value || !item.isDownloading.value) return@async
                            if (localDone >= seg.end - seg.start + 1) break
                            try {
                                val req = okhttp3.Request.Builder().url(fileUrl)
                                    .header("Range", "bytes=${seg.start + localDone}-${seg.end}")
                                    .withWebAuth(item).build()
                                com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp ->
                                    val stream = resp.body?.byteStream() ?: throw java.io.IOException("空响应")
                                    RandomAccessFile(apkFile, "rw").use { raf ->
                                        raf.seek(seg.start + localDone)
                                        val buf = ByteArray(8192)
                                        var n: Int
                                        while (stream.read(buf).also { n = it } != -1) {
                                            if (item.isPaused.value || !item.isDownloading.value) return@async
                                            raf.write(buf, 0, n)
                                            localDone += n
                                            val total = counter.addAndGet(n.toLong())
                                            val prog = ((total * 100) / totalSize).toInt().coerceIn(0, 100)
                                            if (prog > item.progress.value) item.progress.value = prog
                                        }
                                    }
                                }
                                break
                            } catch (e: Exception) {
                                retry++
                                if (retry >= 999) throw e
                                delay(minOf(1000L * (1L shl (retry - 1)), 30000L))
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        } catch (e: Exception) {
            if (item.isPaused.value || !item.isDownloading.value) return
            item.isDownloading.value = false
            item.error.value = "下载失败: ${e.message ?: "未知错误"}"
            item.speedText.value = ""
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "${item.name} 下载失败", Toast.LENGTH_LONG).show()
            }
            return
        } finally {
            sampler.cancel()
        }
        if (item.isPaused.value || !item.isDownloading.value) return
        finishSuccess(context, item, apkFile)
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
