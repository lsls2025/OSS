package com.aurora.chat.data.local

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 头像缓存管理器
 * - 内存缓存：按字节计费的 [LruCache]（线程安全，自带淘汰），替代原 mutableMapOf（主线程读 / IO 线程写并发不安全）
 * - 磁盘缓存：单次读盘（readBytes 一次，bounds 与正式解码都走 decodeByteArray），过期时间戳内存索引化，不再每头像多读一个 .time 文件
 * - 统一加载入口：所有公开 suspend 函数内部统一切到 [Dispatchers.IO]，杜绝任何主线程解码
 * - 并发控制：同一用户去重 + 全局限流
 * - 低内存时通过 [ComponentCallbacks2.onTrimMemory] 回收整块内存缓存
 */
object AvatarCache {

    /** 用户头像内存缓存上限（字节）。按 allocationByteCount 计费，自动淘汰最久未用。 */
    private const val MAX_MEMORY_CACHE_BYTES = 12 * 1024 * 1024

    /** 群头像内存缓存上限（字节）。 */
    private const val GROUP_MAX_MEMORY_CACHE_BYTES = 6 * 1024 * 1024

    /** 磁盘缓存过期时间（毫秒），真实 7 天（原注释误写为 60 分钟）。 */
    private const val CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L

    /** 无头像标记有效期（毫秒），1 小时后自动过期，避免网络错误永久标记。 */
    private const val NO_AVATAR_TTL_MS = 60 * 60 * 1000L

    /** 头像解码目标边长上限（px）：覆盖最大显示尺寸并预留 2x，足够清晰且内存峰值低。 */
    private const val AVATAR_TARGET_PX = 1024

    private val memoryCache = object : LruCache<Long, Bitmap>(MAX_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    private val groupMemoryCache = object : LruCache<Long, Bitmap>(GROUP_MAX_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    private val noAvatarMap = ConcurrentHashMap<Long, Long>()

    /** 用户头像过期时间戳内存索引（启动时建立，避免每头像多读一个 .time 文件）。 */
    private val expiryIndex = ConcurrentHashMap<Long, Long>()

    /** 群头像过期时间戳内存索引。 */
    private val groupExpiryIndex = ConcurrentHashMap<Long, Long>()

    // 同一用户并发请求排队复用（避免重复网络请求），首次渲染几十个头像时分流加载
    private val userLocks = ConcurrentHashMap<Long, Mutex>()
    private val groupLocks = ConcurrentHashMap<Long, Mutex>()
    // 全局头像加载限流：限制同时进行的读盘/联网数量，避免占满 IO 线程池拖累其他任务
    private val loadThrottle = Semaphore(6)

    @Volatile private var trimRegistered = false

    /**
     * 初始化：建立过期时间戳内存索引，并注册 [ComponentCallbacks2] 在低内存时回收缓存。
     * 在 [AuroraChatApplication.onCreate] 调用一次。
     */
    fun init(context: Context) {
        // 注册低内存回收必须在主线程（registerComponentCallbacks 要求），保持同步
        if (!trimRegistered) {
            trimRegistered = true
            context.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) {}
                override fun onLowMemory() { memoryCache.evictAll(); groupMemoryCache.evictAll() }
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                        memoryCache.evictAll()
                        groupMemoryCache.evictAll()
                    } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                        // 后台时仅保留一半，平滑回收
                        memoryCache.trimToSize(memoryCache.maxSize() / 2)
                        groupMemoryCache.trimToSize(groupMemoryCache.maxSize() / 2)
                    }
                }
            })
        }
        // 过期索引构建涉及目录遍历 + 逐 .time 文件读盘，移 IO 避免阻塞冷启动首帧；
        // 索引未建好前 isExpired 会回退到单文件读取（见 isExpired），不会崩溃
        val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        indexScope.launch { buildExpiryIndex(context, "avatar_cache") { expiryIndex[it.first] = it.second } }
        indexScope.launch { buildExpiryIndex(context, "group_avatar_cache") { groupExpiryIndex[it.first] = it.second } }
    }

    private fun buildExpiryIndex(context: Context, dirName: String, put: (Pair<Long, Long>) -> Unit) {
        try {
            val dir = File(context.filesDir, dirName)
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".time")) {
                    val id = f.name.substringBeforeLast(".time").removePrefix("avatar_")
                        .removePrefix("group_avatar_").toLongOrNull() ?: return@forEach
                    val t = f.readText().toLongOrNull() ?: 0L
                    put(id to t)
                }
            }
        } catch (_: Exception) {}
    }

    fun markNoAvatar(userId: Long) { noAvatarMap[userId] = System.currentTimeMillis() }
    fun hasNoAvatar(userId: Long): Boolean {
        val ts = noAvatarMap[userId] ?: return false
        if (System.currentTimeMillis() - ts > NO_AVATAR_TTL_MS) {
            noAvatarMap.remove(userId)
            return false
        }
        return true
    }
    fun clearNoAvatar(userId: Long) { noAvatarMap.remove(userId) }

    /** 同步读取用户头像内存缓存（仅内存，不碰磁盘/网络），供组合期立即显示已缓存头像。 */
    fun getCachedUserAvatarSync(userId: Long): Bitmap? = memoryCache.get(userId)

    /** 同步读取群头像内存缓存（仅内存，不碰磁盘/网络）。 */
    fun getCachedGroupAvatarSync(groupId: Long): Bitmap? = groupMemoryCache.get(groupId)

    // ==================== 用户头像 ====================

    /**
     * 获取头像 Bitmap（优先读内存 -> 磁盘（未过期） -> 网络在 [loadAvatar] 中触发）。
     * 内部统一在 [Dispatchers.IO] 执行磁盘读取，主线程调用也安全。
     */
    suspend fun getAvatar(context: Context, userId: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (hasNoAvatar(userId)) return@withContext null
        memoryCache.get(userId)?.let { return@withContext it }

        val diskFile = getDiskFile(context, userId)
        if (diskFile.exists() && !isExpired(userId, diskFile, expiryIndex)) {
            try {
                val bitmap = decodeSampledFile(diskFile)
                if (bitmap != null && bitmap.width >= 16 && bitmap.height >= 16) {
                    memoryCache.put(userId, bitmap)
                    return@withContext bitmap
                }
            } catch (_: Exception) {}
            try { diskFile.delete() } catch (_: Exception) {}
        }
        null
    }

    /**
     * 从网络加载并缓存头像（loader 返回 null 或后端返回 204 时标记无头像）。
     * 同一用户去重 + 全局限流，避免首屏多个头像并发读盘/联网占满 IO 线程池。
     */
    suspend fun loadAvatar(context: Context, userId: Long, loader: suspend (Long) -> ByteArray?): Bitmap? {
        getAvatar(context, userId)?.let { return it }
        val mutex = userLocks.computeIfAbsent(userId) { Mutex() }
        return mutex.withLock {
            getAvatar(context, userId)?.let { return@withLock it }
            loadThrottle.withPermit { doLoadAvatar(context, userId, loader) }
        }
    }

    private suspend fun doLoadAvatar(context: Context, userId: Long, loader: suspend (Long) -> ByteArray?): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = loader(userId) ?: run {
            markNoAvatar(userId)
            com.aurora.chat.BugTracker.warn("数据", "用户 $userId 无头像")
            return@withContext null
        }
        val bitmap = decodeSampledBytes(bytes) ?: run {
            com.aurora.chat.BugTracker.warn("数据", "用户 $userId 头像解码失败")
            return@withContext null
        }
        if (bitmap.width < 16 || bitmap.height < 16) {
            com.aurora.chat.BugTracker.warn("数据", "用户 $userId 头像太小 ${bitmap.width}x${bitmap.height}")
            return@withContext null
        }
        clearNoAvatar(userId)
        memoryCache.put(userId, bitmap)
        saveToDisk(context, userId, bytes)
        saveCacheTime(context, userId)
        bitmap
    }

    /** 清除指定用户的头像缓存（内存 + 磁盘）。 */
    fun clear(userId: Long, context: Context? = null) {
        memoryCache.remove(userId)
        expiryIndex.remove(userId)
        noAvatarMap.remove(userId)
        if (context != null) {
            try {
                val dir = File(context.filesDir, "avatar_cache")
                File(dir, "avatar_$userId.png").delete()
                File(dir, "avatar_$userId.time").delete()
            } catch (_: Exception) {}
        }
    }

    /** 清除所有缓存（内存 + 磁盘），用于登录/切换账号时强制从头加载。 */
    fun clearAll(context: Context? = null) {
        memoryCache.evictAll()
        groupMemoryCache.evictAll()
        expiryIndex.clear()
        groupExpiryIndex.clear()
        noAvatarMap.clear()
        if (context != null) {
            try {
                File(context.filesDir, "avatar_cache").deleteRecursively()
                File(context.filesDir, "group_avatar_cache").deleteRecursively()
            } catch (_: Exception) {}
        }
    }

    // ==================== 群头像 ====================

    suspend fun getGroupAvatar(context: Context, groupId: Long): Bitmap? = withContext(Dispatchers.IO) {
        groupMemoryCache.get(groupId)?.let { return@withContext it }
        val diskFile = getGroupDiskFile(context, groupId)
        if (diskFile.exists() && !isExpired(groupId, diskFile, groupExpiryIndex)) {
            try {
                val bitmap = decodeSampledFile(diskFile)
                if (bitmap != null) {
                    groupMemoryCache.put(groupId, bitmap)
                    return@withContext bitmap
                }
            } catch (_: Exception) {}
        }
        null
    }

    suspend fun loadGroupAvatar(context: Context, groupId: Long, loader: suspend (Long) -> ByteArray?): Bitmap? {
        getGroupAvatar(context, groupId)?.let { return it }
        val mutex = groupLocks.computeIfAbsent(groupId) { Mutex() }
        return mutex.withLock {
            getGroupAvatar(context, groupId)?.let { return@withLock it }
            loadThrottle.withPermit { doLoadGroupAvatar(context, groupId, loader) }
        }
    }

    private suspend fun doLoadGroupAvatar(context: Context, groupId: Long, loader: suspend (Long) -> ByteArray?): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = loader(groupId) ?: return@withContext null
        val bitmap = decodeSampledBytes(bytes) ?: return@withContext null
        groupMemoryCache.put(groupId, bitmap)
        saveGroupToDisk(context, groupId, bytes)
        saveGroupCacheTime(context, groupId)
        bitmap
    }

    /** 强制刷新群头像（清除缓存后从网络拉取）。 */
    suspend fun refreshGroupAvatar(context: Context, groupId: Long, loader: suspend (Long) -> ByteArray?): Bitmap? {
        clearGroupCache(groupId)
        return loadGroupAvatar(context, groupId, loader)
    }

    fun clearGroupCache(groupId: Long, context: Context? = null) {
        groupMemoryCache.remove(groupId)
        groupExpiryIndex.remove(groupId)
        if (context != null) {
            try {
                val dir = getGroupCacheDir(context)
                File(dir, "group_avatar_$groupId.png").delete()
                File(dir, "group_avatar_$groupId.time").delete()
            } catch (_: Exception) {}
        }
    }

    /** 保存刚上传的群头像到内存缓存，供立即使用。 */
    fun cacheUploadedGroupAvatar(groupId: Long, bitmap: Bitmap) { groupMemoryCache.put(groupId, bitmap) }

    /** 获取刚上传的内存缓存群头像。 */
    fun getCachedUploadedGroupAvatar(groupId: Long): Bitmap? = groupMemoryCache.get(groupId)

    /** 将用户头像直接存入缓存（上传后调用，避免重新从网络加载）。 */
    fun cacheUserAvatar(userId: Long, bitmap: Bitmap, context: Context? = null) {
        memoryCache.put(userId, bitmap)
        if (context != null) {
            try {
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
                saveToDisk(context, userId, stream.toByteArray())
                saveCacheTime(context, userId)
            } catch (_: Exception) {}
        }
    }

    // ==================== 私有方法 ====================

    private fun getGroupCacheDir(context: Context): File {
        val dir = File(context.filesDir, "group_avatar_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getGroupDiskFile(context: Context, groupId: Long): File =
        File(getGroupCacheDir(context), "group_avatar_$groupId.png")

    private fun saveGroupToDisk(context: Context, groupId: Long, bytes: ByteArray) {
        try { getGroupDiskFile(context, groupId).writeBytes(bytes) } catch (_: Exception) {}
    }

    private fun saveGroupCacheTime(context: Context, groupId: Long) {
        try {
            File(getGroupCacheDir(context), "group_avatar_$groupId.time")
                .writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) {}
    }

    /** 过期判断：首次访问时从 .time 文件读取并写入内存索引，之后零额外磁盘读。 */
    private fun isExpired(userId: Long, file: File, index: ConcurrentHashMap<Long, Long>): Boolean {
        val cachedTime = index[userId] ?: run {
            val tf = File(file.parent, file.name.replace(".png", ".time"))
            val t = if (tf.exists()) tf.readText().toLongOrNull() ?: 0L else 0L
            index[userId] = t
            t
        }
        return System.currentTimeMillis() - cachedTime > CACHE_TTL_MS
    }

    private fun getDiskFile(context: Context, userId: Long): File {
        val dir = File(context.filesDir, "avatar_cache")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "avatar_$userId.png")
    }

    private fun saveCacheTime(context: Context, userId: Long) {
        try {
            File(File(context.filesDir, "avatar_cache"), "avatar_${userId}.time")
                .writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) {}
    }

    private fun saveToDisk(context: Context, userId: Long, bytes: ByteArray) {
        try { getDiskFile(context, userId).writeBytes(bytes) } catch (_: Exception) {}
    }

    /** 根据源尺寸计算 inSampleSize（2 的幂），将解码结果限制在 target 内。 */
    private fun calcInSampleSize(outWidth: Int, outHeight: Int, target: Int): Int {
        var inSampleSize = 1
        while (outWidth / inSampleSize >= target || outHeight / inSampleSize >= target) inSampleSize *= 2
        return inSampleSize
    }

    /** 带采样解码文件：单次读盘（readBytes 一次），bounds 与正式解码都走 decodeByteArray。 */
    private fun decodeSampledFile(file: File, target: Int = AVATAR_TARGET_PX): Bitmap? {
        val bytes = try { file.readBytes() } catch (_: Exception) { return null }
        return decodeSampledBytes(bytes, target)
    }

    /** 带采样解码字节数组，使用 RGB_565 降低内存占用（头像不透明，圈裁足够）。 */
    private fun decodeSampledBytes(bytes: ByteArray, target: Int = AVATAR_TARGET_PX): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, target)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
