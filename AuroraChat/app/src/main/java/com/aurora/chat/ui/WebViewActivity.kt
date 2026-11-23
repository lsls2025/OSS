package com.aurora.chat.ui
import com.aurora.chat.ui.theme.LocalAppColors

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog

/**
 * 应用内 WebView 浏览器 — 点击链接时选择"内部打开"时启动。
 * 统一为 Compose（WebView 通过 AndroidView 承载，因其本质是原生 View）。
 */
class WebViewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        /** 悬浮球点「打开下载管理」时带过来的信号：就地弹出下载管理，不重建页面 */
        const val EXTRA_OPEN_DOWNLOADS = "extra_open_downloads"

        /** 当前是否在前台：悬浮球据此决定就地弹下载管理，还是回到主页 */
        @Volatile
        var isForeground: Boolean = false
    }

    /** 每次收到「打开下载管理」信号就 +1，供 Compose 侧打开弹层 */
    private val openDownloadsSignal = androidx.compose.runtime.mutableStateOf(0)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) {
            openDownloadsSignal.value++
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
    }

    /**
     * 「禁止退出」生效时，用户从这里按 HOME / 最近任务离开也要立刻拉回。
     * onUserLeaveHint 是用户主动离开时最早的可靠回调（详情见 PrivacyEnforcer）。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        try {
            com.aurora.chat.PrivacyEnforcer.onUserLeaveHint(this)
        } catch (_: Throwable) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏网页：内容铺满到状态栏/导航栏区域，系统栏透明；窗口和 WebView 都铺白，
        // 避免网页加载前顶部露出窗口默认的黑色
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            // 状态栏保持显示但透明，避免隐藏后各类弹层内 statusBarsPadding() 拿到 0 而顶到屏幕边缘
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }

        setContent {
            MaterialTheme {
                var progress by remember { mutableStateOf(0) }
                var isLoading by remember { mutableStateOf(true) }
                // 无法用应用内下载模块完成的下载（blob/data 等）→ 提示改用浏览器
                var browserDownloadUrl by remember { mutableStateOf<String?>(null) }
                // 悬浮球点「打开下载管理」→ onNewIntent 发信号 → 就地弹层（不把网站挤掉）
                var showDownloadQueue by remember { mutableStateOf(false) }
                val openDownloadsTick by openDownloadsSignal
                LaunchedEffect(openDownloadsTick) {
                    if (openDownloadsTick > 0) {
                        // 展示交给本页弹层，避免退出网站回主页时又被顶出来一次
                        com.aurora.chat.ui.tools.DownloadManager.minimize()
                        showDownloadQueue = true
                    }
                }

                BackHandler { finish() }

                // 全屏网页：无顶部标题栏，网页直接铺满
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        if (isLoading) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth().height(3.dp)
                            )
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    // 网页加载前默认是黑底，铺一层白避免整屏发黑
                                    setBackgroundColor(android.graphics.Color.WHITE)
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    // target=_blank / window.open 的站点很多：允许弹窗请求，
                                    // 由下面 onCreateWindow 抓到真实地址后在当前 WebView 打开，
                                    // 否则这些「下载」按钮点了毫无反应
                                    settings.setSupportMultipleWindows(true)
                                    settings.javaScriptCanOpenWindowsAutomatically = true
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, vUrl: String?, favicon: android.graphics.Bitmap?) {
                                            isLoading = true
                                        }

                                        override fun onPageFinished(view: WebView?, vUrl: String?) {
                                            isLoading = false
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val reqUrl = request?.url?.toString() ?: return false
                                            if (reqUrl.startsWith("tel:") || reqUrl.startsWith("mailto:")) {
                                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(reqUrl)))
                                                return true
                                            }
                                            // 网页内容交给 WebView 自己加载
                                            if (reqUrl.startsWith("http://") || reqUrl.startsWith("https://") ||
                                                reqUrl.startsWith("blob:") || reqUrl.startsWith("data:") ||
                                                reqUrl.startsWith("about:")
                                            ) return false
                                            // 其余自定义 scheme（intent:// / market:// / 各应用唤起）：交给系统处理
                                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(reqUrl))) }
                                            return true
                                        }
                                    }
                                    // 关键：没有 DownloadListener 时，网页下载（Content-Disposition / a[download]）
                                    // 一律被 WebView 静默丢弃，表现就是「点了下载没有任何反应」
                                    setDownloadListener { dlUrl, dlUserAgent, contentDisposition, mimeType, _ ->
                                        // http(s)：直接进入应用内的下载模块（队列 + 进度 + 后台保活）
                                        if (dlUrl.startsWith("http://") || dlUrl.startsWith("https://")) {
                                            val guessed = URLUtil.guessFileName(dlUrl, contentDisposition, mimeType)
                                            val safeName = guessed
                                                .replace(Regex("""[/\\:*?"<>|\r\n]"""), "_")
                                                .ifBlank { "download_${System.currentTimeMillis()}" }
                                            com.aurora.chat.ui.tools.DownloadManager.addCommunityDownload(
                                                name = safeName,
                                                fileName = safeName,
                                                downloadUrl = dlUrl,
                                                cookie = CookieManager.getInstance().getCookie(dlUrl).orEmpty(),
                                                referer = this@apply.url.orEmpty()
                                            )
                                            com.aurora.chat.ui.tools.DownloadManager.startDownloads(this@WebViewActivity)
                                            // 网页里下载只出悬浮球：最小化，避免退出网站回到主页时
                                            // MainActivity 又把全屏下载管理顶出来
                                            com.aurora.chat.ui.tools.DownloadManager.minimize()
                                        } else {
                                            // blob:/data: 只能浏览器处理；回到当前页面地址让用户在浏览器里重试
                                            browserDownloadUrl = this@apply.url ?: url
                                        }
                                    }
                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            progress = newProgress
                                        }

                                        override fun onCreateWindow(
                                            view: WebView?,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: Message?
                                        ): Boolean {
                                            // 不真的开新窗口：用临时 WebView 接住 location，再塞回当前页打开
                                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                            val holder = WebView(view?.context ?: this@WebViewActivity)
                                            holder.webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                                                    val target = request?.url?.toString() ?: return false
                                                    view?.loadUrl(target)
                                                    return true
                                                }
                                            }
                                            transport.webView = holder
                                            resultMsg.sendToTarget()
                                            return true
                                        }
                                    }
                                    loadUrl(url)
                                }
                            }
                        )
                    }
                    // 全屏网页无标题栏：右上角悬浮返回按钮，保留退出入口（不显示网址标题）
                    IconButton(
                        onClick = { finish() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(8.dp)
                            .background(Color(0x66000000), CircleShape)
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }

                    // blob:/data: 这类下载系统下载器接不了 → 引导去系统浏览器完成
                    val blockedUrl = browserDownloadUrl
                    if (blockedUrl != null) {
                        Dialog(onDismissRequest = { browserDownloadUrl = null }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(com.aurora.chat.ui.theme.appSurface())
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "该下载需要浏览器完成",
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary()
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "这类文件由网页脚本即时生成，应用内无法保存。可以用系统浏览器打开这个页面重新下载。",
                                    fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                                )
                                Spacer(Modifier.height(20.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF1E40AF))
                                            .clickable {
                                                runCatching {
                                                    startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(blockedUrl))
                                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    )
                                                }
                                                browserDownloadUrl = null
                                            }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("用浏览器打开", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(com.aurora.chat.ui.theme.appCardBg())
                                            .clickable { browserDownloadUrl = null }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("取消", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }

                    // 下载完成提示：每个任务完成时弹一次（进入本页前就已完成的不再补弹）
                    val downloadQueue = com.aurora.chat.ui.tools.DownloadManager.queue
                    var completedFileName by remember { mutableStateOf<String?>(null) }
                    val doneAtEntry = remember { downloadQueue.filter { it.isCompleted.value }.map { it.id }.toSet() }
                    downloadQueue.forEach { item ->
                        val done by item.isCompleted.collectAsState()
                        if (done && item.id !in doneAtEntry) {
                            LaunchedEffect(item.id, done) {
                                completedFileName = item.fileName
                                // 完成后不再让主页自动弹出全屏下载管理
                                com.aurora.chat.ui.tools.DownloadManager.minimize()
                            }
                        }
                    }
                    val doneName = completedFileName
                    if (doneName != null) {
                        Dialog(onDismissRequest = { completedFileName = null }) {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 300.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(com.aurora.chat.ui.theme.appSurface())
                                    .padding(20.dp)
                            ) {
                                Text("下载完成", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    doneName, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "知道了", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { completedFileName = null }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "查看", fontSize = 14.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { completedFileName = null; showDownloadQueue = true }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 下载管理复用现有全屏下载页（悬浮球点进来也走这里），关掉即回到网页
                    if (showDownloadQueue) {
                        Dialog(
                            onDismissRequest = { showDownloadQueue = false },
                            properties = androidx.compose.ui.window.DialogProperties(
                                usePlatformDefaultWidth = false,
                                decorFitsSystemWindows = false
                            )
                        ) {
                            com.aurora.chat.ui.tools.DownloadQueueScreen(
                                onMinimize = { showDownloadQueue = false }
                            )
                        }
                    }
                }
            }
        }
    }
}
