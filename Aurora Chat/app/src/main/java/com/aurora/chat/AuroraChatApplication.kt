package com.aurora.chat

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.aurora.chat.core.domain.ChatEvents
import com.aurora.chat.data.api.HttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
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
        startKoin {
            androidContext(this@AuroraChatApplication)
            modules(appModule)
        }
        ChatEvents.sink = AppChatEventSink
        CrashHandler.init(this)
        ErrorReporter.init(this)

        // 「禁止退出」互锁：**冷启动复位为关闭**（这个功能默认关闭，不落盘、不继承）。
        // 热启动（进程还活着）不会走到这里，状态自然保持。
        PrivacyLockState.onProcessStart(this)
        // 必须同时监听 resumed/paused：上滑进多任务视图时 Activity 只走 onPause，
        // 没有 onStop，只看 started/stopped 会误判成"还在前台"从而完全不拦。
        registerActivityLifecycleCallbacks(
            registerAppVisibilityCallbacks(
                onStarted = { AppVisibility.onActivityStarted() },
                onStopped = { AppVisibility.onActivityStopped() },
                onResumed = { AppVisibility.onActivityResumed(it) },
                onPaused = { AppVisibility.onActivityPaused() },
                onDestroyed = { AppVisibility.onActivityDestroyed(it) }
            )
        )

        // 签名校验仅用于打印日志，重 CPU（APK 签名 SHA256）。空签名配置时本就是空操作，
        // 移 IO 避免阻塞冷启动首帧
        initScope.launch { SignatureValidator.printActualSignature(this@AuroraChatApplication) }

        // 网络层：建立连接池 + 20MB HTTP 磁盘缓存。OkHttp Cache 构造本身只建目录/懒加载 journal，开销极小，保留主线程同步以保证后续调用立即可用
        HttpClient.init(this)
        // 反抓包/反代理环境检测：初始化后 HttpClient 的拦截器即可基于它断网
        com.aurora.chat.security.SecurityGuard.init(this)
        // MMKV：替代高频 SharedPreferences 写入（无 apply/commit 阻塞主线程、无整文件重写），首次访问时从旧 SP 迁移
        MMKV.initialize(this)
        // 头像缓存：注册低内存回收（主线程）+ 过期索引构建（已移 IO，见 AvatarCache.init）
        AvatarCache.init(this)
        // 媒体后台发送：启动时处理持久化队列，上次未发出的图片会在登录后自动重试
        com.aurora.chat.data.upload.MediaUploadManager.init(this)
        // 本机发送的媒体消息真实类型记录（用于覆盖后端返回的误标 media_type）
        com.aurora.chat.data.local.LocalMediaTypeStore.init(this)
        // 已被清理/过期媒体的负结果缓存：读回上次记录，重启后也不再重复请求这些地址
        com.aurora.chat.ui.chat.media.MediaFailureCache.init(this)
        // 图片原始尺寸缓存：读回上次嗅探到的宽高，重启后首帧即定高，避免每次开会话重新嗅探导致气泡跳动
        com.aurora.chat.ui.chat.media.MediaSizeCache.init(this)

        // 会话列表磁盘缓存预热：聊天页首帧会同步取内存快照（LocalConversationStore.peek），
        // 这里在 IO 上先读一遍，保证冷启动进入聊天页时第一帧就是磁盘里的完整列表，
        // 而不是先只显示个人对话 / AI / 通知中心这 3 个固定项。
        initScope.launch {
            runCatching { com.aurora.chat.data.local.LocalConversationStore.load(this@AuroraChatApplication) }
            // 全局用户资料缓存预热：读进内存后，群成员/资料页/消息气泡按 userId 查名字零磁盘读，
            // 断网首帧也能直接显示看过的用户。
            runCatching { com.aurora.chat.data.local.UserProfileStore.init(this@AuroraChatApplication) }
            // 群信息缓存预热：读进内存后，群设置/群二维码按 groupId 查群号零磁盘读，
            // 断网首帧也能直接显示群号。
            runCatching { com.aurora.chat.data.local.GroupInfoStore.init(this@AuroraChatApplication) }
        }

        // 客户端偏好还原（置顶会话 / 社区动态已读 / 社区排序）：
        // 冷启动时登录态是本地读出来的，不等登录流程，直接拉一次服务端记录回写本地，
        // 这样首屏进聊天页/社区时看到的就是用户上次的设置（重装也不会丢）。
        initScope.launch {
            runCatching {
                val st = com.aurora.chat.data.local.LocalStorage
                    .loadLoginState(this@AuroraChatApplication)
                if (st.isLoggedIn) {
                    com.aurora.chat.data.local.ClientPrefsSync.pull(this@AuroraChatApplication)
                }
            }
        }

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
