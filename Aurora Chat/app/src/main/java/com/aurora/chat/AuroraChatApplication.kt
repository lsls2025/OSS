package com.aurora.chat

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.aurora.chat.data.api.HttpClient
import com.aurora.chat.data.local.AvatarCache
import com.aurora.chat.util.SignatureValidator
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class AuroraChatApplication : Application() {
    companion object {
        lateinit var instance: AuroraChatApplication
    }

    // 启动期后台任务作用域：签名校验/缓存预热等一次性任务，进程退出时取消
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashHandler.init(this)
        ErrorReporter.init(this)

        // 签名校验仅用于打印日志，重 CPU（APK 签名 SHA256）。空签名配置时本就是空操作，
        // 移 IO 避免阻塞冷启动首帧
        initScope.launch { SignatureValidator.printActualSignature(this@AuroraChatApplication) }

        // 网络层：建立连接池 + 20MB HTTP 磁盘缓存。OkHttp Cache 构造本身只建目录/懒加载 journal，开销极小，保留主线程同步以保证后续调用立即可用
        HttpClient.init(this)
        // MMKV：替代高频 SharedPreferences 写入（无 apply/commit 阻塞主线程、无整文件重写），首次访问时从旧 SP 迁移
        MMKV.initialize(this)
        // 头像缓存：注册低内存回收（主线程）+ 过期索引构建（已移 IO，见 AvatarCache.init）
        AvatarCache.init(this)
        // 媒体后台发送：启动时处理持久化队列，上次未发出的图片会在登录后自动重试
        com.aurora.chat.data.upload.MediaUploadManager.init(this)
        // 本机发送的媒体消息真实类型记录（用于覆盖后端返回的误标 media_type）
        com.aurora.chat.data.local.LocalMediaTypeStore.init(this)

        // 配置 Coil 全局图片加载器：复用同一 OkHttpClient（连接池 + 20MB HTTP 磁盘缓存，避免重复 TCP/TLS 握手），
        // 内存缓存 + 200MB 磁盘缓存。头像/截图多为不透明图片，allowRgb565 降低 ~50% 解码内存；
        // hardwareBitmap 让解码结果直接进 GPU 纹理，减少一次内存拷贝；关闭 crossfade 避免叠加层导致的额外合成开销
        val imageLoader = ImageLoader.Builder(this)
            .okHttpClient(HttpClient.client)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .allowHardware(true)
            .allowRgb565(true)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
        Coil.setImageLoader(imageLoader)
    }

    override fun onTerminate() {
        super.onTerminate()
        initScope.cancel()
    }
}
