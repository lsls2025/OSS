package com.aurora.chat

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.*

class UploadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val NOTIFICATION_ID = 1003
        const val EXTRA_POST_ID = "postId"
        const val EXTRA_URIS = "uris"
        const val EXTRA_NAMES = "names"
        const val EXTRA_MIMES = "mimes"

        fun startIntent(
            ctx: android.content.Context,
            postId: Long,
            uris: List<Uri>,
            names: List<String>,
            mimes: List<String>
        ): Intent = Intent(ctx, UploadService::class.java).apply {
            putExtra(EXTRA_POST_ID, postId)
            putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map { it.toString() }))
            putStringArrayListExtra(EXTRA_NAMES, ArrayList(names))
            putStringArrayListExtra(EXTRA_MIMES, ArrayList(mimes))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val postId = intent?.getLongExtra(EXTRA_POST_ID, 0L) ?: return START_NOT_STICKY
        val uris = intent?.getStringArrayListExtra(EXTRA_URIS) ?: return START_NOT_STICKY
        val names = intent?.getStringArrayListExtra(EXTRA_NAMES) ?: return START_NOT_STICKY
        val mimes = intent?.getStringArrayListExtra(EXTRA_MIMES) ?: return START_NOT_STICKY

        val notification = NotificationHelper.buildUploadNotification(this, "正在后台上传，请勿关闭应用后台")
        startForeground(NOTIFICATION_ID, notification)

        // 获取文件大小
        val fileSizes = uris.map { uriStr ->
            try {
                val u = Uri.parse(uriStr)
                var sz = 0L
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0) sz = c.getLong(idx)
                    }
                }
                sz
            } catch (_: Exception) { 0L }
        }

        // 注册到上传管理器并启动悬浮球
        val uploadItemIds = names.mapIndexed { i, name -> UploadManager.addToQueue(name, fileSizes.getOrElse(i) { 0L }) }
        if (UploadFloatingService.ensureOverlayPermission(this)) {
            UploadFloatingService.start(this)
        }

        val job = scope.launch {
            var successCount = 0
            var failCount = 0
            for (i in uris.indices) {
                try {
                    val uri = Uri.parse(uris[i])
                    val mime = mimes[i]
                    val uploadMime = if (mime.startsWith("audio/")) "application/octet-stream" else mime
                    val fileSize = fileSizes.getOrElse(i) { 0L }

                    val inputStream = contentResolver.openInputStream(uri)
                    if (inputStream == null) { failCount++; UploadManager.markFailed(uploadItemIds[i], "无法读取文件"); continue }
                    val result = AuroraApi.uploadCommunityFileStreaming(
                        postId, inputStream, names[i], uploadMime,
                        totalBytes = fileSize,
                        onProgress = { sent, total ->
                            val pct = if (total > 0) (sent * 100 / total).toInt().coerceIn(0, 100) else 0
                            UploadManager.updateProgress(uploadItemIds[i], pct)
                        }
                    )
                    inputStream.close()
                    if (result.success) {
                        successCount++
                        UploadManager.markCompleted(uploadItemIds[i])
                    } else {
                        failCount++
                        UploadManager.markFailed(uploadItemIds[i], result.message)
                    }

                    val text = "正在后台上传 (${i + 1}/${uris.size})，请勿关闭应用后台"
                    val notif = NotificationHelper.buildUploadNotification(this@UploadService, text)
                    val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.notify(NOTIFICATION_ID, notif)

                } catch (e: Exception) {
                    if (UploadManager.queue.find { it.id == uploadItemIds[i] }?.isCancelled?.value == true) {
                        // 用户取消，不计入失败
                    } else {
                        failCount++
                        UploadManager.markFailed(uploadItemIds[i], e.localizedMessage ?: "未知错误")
                    }
                }
            }

            val summary = if (failCount == 0) {
                "上传成功 (共${uris.size}个文件)"
            } else {
                "上传完成: ${successCount}成功, ${failCount}失败"
            }
            NotificationHelper.showUploadCompleteNotification(this@UploadService, summary)
            try { UploadFloatingService.stop(this@UploadService) } catch (_: Exception) {}
            UploadManager.clearAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        UploadManager.uploadJob = job

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
