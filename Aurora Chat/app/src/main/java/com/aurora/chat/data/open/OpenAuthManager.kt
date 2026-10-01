package com.aurora.chat.data.open

import android.content.Intent

/**
 * 第三方授权 Deep Link 统一入口。
 *
 * 解析第三方平台发起的授权跳转（scheme: aurora://oauth/authorize?...），
 * 冷启动（onCreate）与热启动（onNewIntent）均调用 [handleIntent]，
 * 由 Compose 层观察 [authRequest] 自动弹出全屏授权界面。
 *
 * 本阶段为预览骨架：确定/取消仅关闭界面，真实授权数据下发由后续阶段实现。
 */
object OpenAuthManager {

    /** 一次第三方授权请求（由 deep link 参数解析而来） */
    data class OpenAuthRequest(
        val clientId: String,
        val appName: String,
        val scopes: List<String>,
        val redirectUri: String,
        val state: String,
        val rawUri: String,
        /** 发起方应用标识：第三方通过 source_pkg 参数显式上报，或从 referrer 尽力检测 */
        val sourcePkg: String = "",
    )

    /** 当前待授权的请求；null = 无待处理授权 */
    val authRequest = androidx.compose.runtime.mutableStateOf<OpenAuthRequest?>(null)

    /** 去重锚点：同一跳转 URI 只弹一次，避免 onCreate/onNewIntent 双触发 */
    private var lastRawUri: String? = null

    /** 处理任意 intent：是第三方授权 deep link 则登记请求；否则忽略 */
    fun handleIntent(intent: Intent?) {
        val req = parse(intent) ?: return
        if (req.rawUri == lastRawUri) return
        lastRawUri = req.rawUri
        authRequest.value = req
    }

    /** 解析授权 deep link；非授权跳转返回 null */
    fun parse(intent: Intent?): OpenAuthRequest? {
        val data = intent?.data ?: return null
        if (data.scheme != "aurora" || data.host != "oauth") return null
        val path = data.path ?: return null
        if (path != "/authorize" && !path.startsWith("/authorize")) return null
        val clientId = data.getQueryParameter("client_id") ?: return null
        if (clientId.isBlank()) return null
        val appName = data.getQueryParameter("app_name")?.takeIf { it.isNotBlank() } ?: clientId
        val scopes = (data.getQueryParameter("scopes") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val redirectUri = data.getQueryParameter("redirect_uri")
            ?.takeIf { it.isNotEmpty() }
            ?: rawQueryParam(data, "redirect_uri") ?: ""
        val state = data.getQueryParameter("state")
            ?.takeIf { it.isNotEmpty() }
            ?: rawQueryParam(data, "state") ?: ""
        // 发起方应用标识：优先取显式上报的 source_pkg / from_pkg / source 参数，其次尽力从 referrer 检测
        val sourcePkg = listOf("source_pkg", "from_pkg", "source")
            .asSequence()
            .mapNotNull { data.getQueryParameter(it)?.takeIf { v -> v.isNotBlank() } }
            .firstOrNull()
            ?: referrerPackage(intent)
        return OpenAuthRequest(
            clientId = clientId,
            appName = appName,
            scopes = scopes,
            redirectUri = redirectUri,
            state = state,
            rawUri = data.toString(),
            sourcePkg = sourcePkg.orEmpty(),
        )
    }

    /** 从 Intent referrer 尽力提取发起方包名；浏览器跳转等场景可用，普通 App scheme 跳转通常为 null */
    private fun referrerPackage(intent: Intent?): String? {
        val ref = try {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_REFERRER)
        } catch (e: Exception) {
            null
        } ?: return null
        return ref.host?.takeIf { it.isNotBlank() }
    }

    /** 关闭授权界面（确定/取消/返回均调用） */
    fun dismiss() {
        authRequest.value = null
        // 清除去重锚点，保证关闭授权页后同一 URI 可再次触发跳转
        lastRawUri = null
    }

    /**
     * 手动从原始 query 提取参数值。
     * 兼容部分系统/版本中 Uri.getQueryParameter 对百分号编码值（如 redirect_uri=https%3A%2F%2F...）
     * 解析异常返回 null/空串的问题。
     */
    private fun rawQueryParam(data: android.net.Uri, key: String): String? {
        val q = data.encodedQuery ?: return null
        for (pair in q.split("&")) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == key) {
                val v = pair.substring(eq + 1)
                return try {
                    android.net.Uri.decode(v)
                } catch (e: Exception) {
                    v
                }
            }
        }
        return null
    }
}
