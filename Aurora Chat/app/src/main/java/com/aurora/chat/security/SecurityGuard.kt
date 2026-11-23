package com.aurora.chat.security

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aurora.chat.ErrorReporter

/**
 * 反抓包 / 反代理 环境检测（客户端）。
 *
 * 命中以下任一环境即判定为高风险，调用方应据此阻断网络访问：
 *  1) 系统/应用设置了 HTTP 代理（抓包工具的「手动代理」模式，如 Charles / Fiddler / HttpCanary 代理模式）；
 *  2) 当前活动网络为 VPN（HttpCanary 等「无 root 抓包」使用本地 VPN 拦截全部流量）。
 *
 * 注意：「设备上安装了抓包工具」本身不算命中、不阻断——仅仅装了就让整个 App 不可用属于误伤。
 * 该信息仍可通过 [installedCaptureApp] 获取，用于提示与统计。
 *
 * 局限（务必知悉）：纯客户端检测只能提高门槛，无法绝对防御。
 * root 设备 + 系统级证书 + Frida hook 可绕过任何客户端检测；真正的机密性依赖
 * 「HTTPS + 证书绑定（SSL Pinning）」，本类只用于拦截绝大多数普通用户使用抓包工具的场景。
 */
object SecurityGuard {

    /** 是否把「检测到 VPN」也判为抓包正在生效。
     *  HttpCanary 无 root 模式 = 本地 VPN，开启可拦住它；但会误伤正常使用 VPN / 加速器的用户。
     *  如误伤严重，把它置为 false（仍保留「系统/应用 HTTP 代理已设置」这一最高置信度的检测）。 */
    @Volatile
    var blockOnVpn: Boolean = true

    /** 已知抓包 / 网络调试工具包名。 */
    private val CAPTURE_PACKAGES = arrayOf(
        "com.guoshi.httpcanary",          // HttpCanary（小黄鸟）
        "com.guoshi.httpcanary.premium",  // HttpCanary 高级版
        "com.guoshi.httpcanary.pro",      // HttpCanary Pro
        "com.minhui.networkcapture",      // NetworkCapture
        "com.minhui.networkcapture.pro",
        "app.greyshirts.sslcapture",      // Packet Capture
        "com.emanuelef.remote_capture",   // PCAPdroid
        "com.evbadger.packetcapture",     // Packet Capture (EvilBadger)
        "org.mitmproxy.android",          // mitmproxy
        "net.kwatttz.androiddump"         // AndroidDump
    )

    private var appContext: Context? = null

    // 抓包 App 扫描较贵（多次 getPackageInfo），带 TTL 缓存；代理/VPN 检测很便宜，每次实时判定。
    private var captureAppCache: String? = null
    private var captureAppCacheAt = 0L
    private const val APP_SCAN_TTL_MS = 5000L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 返回「抓包正在实际生效」的原因描述；null 表示未检出。
     *
     * 只有真正会拦截本 App 流量的情形才算命中：
     *  1) 系统/应用设置了 HTTP 代理（抓包工具的「手动代理」模式确实在工作）；
     *  2) 当前活动网络为 VPN（HttpCanary 等无 root 抓包靠本地 VPN 拦截）。
     *
     * 「设备上装了抓包 App」**不再阻断**：装没装与是否在抓包没有因果关系，
     * 早期把它算作命中导致用户只是预装/装过抓包工具就完全无法使用本 App（且所有网络请求被抛异常）。
     * 需要按安装情况做提示/统计时用 [installedCaptureApp]。
     */
    fun detect(): String? {
        val ctx = appContext ?: return null
        return systemProxyReason(ctx)
            ?: (if (blockOnVpn) vpnReason(ctx) else null)
    }

    /** 设备上是否装了抓包工具（仅用于提示/诊断，不阻断）。 */
    fun installedCaptureApp(): String? {
        val ctx = appContext ?: return null
        return captureAppReason(ctx)
    }

    /** 「抓包是否正在实际生效」的便捷判断。 */
    fun isCaptureEnvironment(): Boolean = detect() != null

    // ── ① 系统 HTTP 代理 ──
    private fun systemProxyReason(ctx: Context): String? {
        // JVM 系统属性（部分抓包工具会设置）
        val host = System.getProperty("http.proxyHost")
        val port = System.getProperty("http.proxyPort")
        if (!host.isNullOrBlank() && host != "null" && port != null && port != "0" && port != "-1") {
            return "系统 HTTP 代理 $host:$port"
        }
        // ConnectivityManager 默认代理（Wi-Fi 手动代理，抓包工具最常用）
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val proxy = try { cm.defaultProxy } catch (_: Exception) { null } ?: return null
        val pHost = proxy.host
        if (!pHost.isNullOrBlank()) return "系统 HTTP 代理 $pHost:${proxy.port}"
        return null
    }

    // ── ② VPN（无 root 抓包） ──
    private fun vpnReason(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN / 本地代理环境"
        return null
    }

    // ── ③ 已安装抓包工具（带缓存） ──
    private fun captureAppReason(ctx: Context): String? {
        val now = System.currentTimeMillis()
        if (now - captureAppCacheAt < APP_SCAN_TTL_MS) return captureAppCache
        val pm = ctx.packageManager
        var hit: String? = null
        for (pkg in CAPTURE_PACKAGES) {
            val installed = try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Exception) {
                false
            }
            if (installed) { hit = pkg; break }
        }
        captureAppCache = hit?.let { "已安装抓包工具（$it）" }
        captureAppCacheAt = now
        return captureAppCache
    }

    /** 记录一次拦截（用于排查 / 统计，走软件内现有日志系统）。 */
    fun logBlock(reason: String) {
        runCatching { ErrorReporter.warn("SecurityGuard", "反抓包拦截：$reason") }
    }
}
