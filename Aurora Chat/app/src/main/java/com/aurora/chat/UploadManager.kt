package com.aurora.chat

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

object UploadManager {

    data class UploadItem(
        val id: Long,
        val fileName: String,
        val fileSize: Long = 0,
        val isUploading: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val isCompleted: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val isCancelled: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val error: MutableStateFlow<String?> = MutableStateFlow(null),
        val progress: MutableStateFlow<Int> = MutableStateFlow(0) // 当前文件 0-100
    ) {
        val isFinished: Boolean get() = isCompleted.value || isCancelled.value || error.value != null
    }

    private var nextId = 1L
    @Volatile
    var queue: List<UploadItem> = emptyList()
        private set

    /** 当前正在上传的文件名 */
    @Volatile
    var currentFileName: String = ""
        private set

    /** 上传协程 Job，用于取消 */
    @Volatile
    var uploadJob: Job? = null
        internal set

    val activeUploadCount: Int get() = queue.count { it.isUploading.value }
    val completedCount: Int get() = queue.count { it.isCompleted.value }
    val cancelledCount: Int get() = queue.count { it.isCancelled.value }
    val totalCount: Int get() = queue.size

    /** 当前文件进度百分比 0-100 */
    val currentFileProgress: Int get() {
        val active = queue.find { it.isUploading.value }
        return active?.progress?.value ?: 0
    }

    val overallProgress: Int get() {
        if (queue.isEmpty()) return 0
        val done = queue.count { it.isFinished }
        return (done * 100 / queue.size).coerceIn(0, 100)
    }

    val shouldShowFloating: Boolean get() {
        if (queue.isEmpty()) return false
        return queue.any { it.isUploading.value || it.error.value != null }
    }

    fun addToQueue(fileName: String, fileSize: Long = 0): Long {
        val id = nextId++
        queue = queue + UploadItem(id = id, fileName = fileName, fileSize = fileSize, isUploading = MutableStateFlow(true))
        currentFileName = fileName
        return id
    }

    fun updateProgress(id: Long, percent: Int) {
        queue = queue.map {
            if (it.id == id) {
                val newFlow = MutableStateFlow(percent)
                it.copy(progress = newFlow)
            } else it
        }
    }

    fun markCompleted(id: Long) {
        queue = queue.map {
            if (it.id == id) it.copy(
                isUploading = MutableStateFlow(false),
                isCompleted = MutableStateFlow(true),
                progress = MutableStateFlow(100)
            ) else it
        }
        currentFileName = queue.find { it.isUploading.value }?.fileName ?: ""
    }

    fun markFailed(id: Long, errorMsg: String) {
        queue = queue.map {
            if (it.id == id) it.copy(
                isUploading = MutableStateFlow(false),
                error = MutableStateFlow(errorMsg)
            ) else it
        }
    }

    fun cancelAll() {
        uploadJob?.cancel()
        queue = queue.map {
            if (it.isUploading.value) it.copy(
                isUploading = MutableStateFlow(false),
                isCancelled = MutableStateFlow(true)
            ) else it
        }
        currentFileName = ""
    }

    fun clearAll() {
        queue = emptyList()
        currentFileName = ""
        uploadJob = null
    }
}
