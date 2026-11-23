package com.aurora.chat.data.link

import android.content.Context
import android.text.Html
import com.aurora.chat.data.api.HttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 一条链接解析出来的预览信息：标题 + 站点图标 */
data class LinkPreviewInfo(
    val url: String,
    val title: String,
    /** 图标绝对地址；空串表示站点没给也不像有 favicon.ico（此时卡片只显示标题） */
    val iconUrl: String
) {
    val hasIcon: Boolean get() = iconUrl.isNotBlank()
}

/**
 * 链接预览解析（对齐 QQ / 微信：抓网页的 `<title>` 与站点图标）。
 *
 * 只在「消息正文里真的出现过链接」时才请求，且：
 *  · 结果三级缓存（内存 → SharedPreferences → 网络），滚动来回、重启 App 都不会重复抓；
 *  · 同一 URL 并发只发一次请求（多气泡同链接不互相打）；
 *  · 超时短（6s 连接/读取，最长 10s），最坏情况卡片不出现，不影响消息本身；
 *  · 解析不到标题就当作"没有"，渲染层直接不画卡片 —— 与 QQ 一致，不占任何空间。
 *
 * 所有方法都可能在主线程被调用（[peek]），真正联网的 [load] 必须放在 IO 线程。
 */
object LinkPreviewLoader {
    private const val PREFS = "link_preview_cache"
    /** 磁盘最多保留条数：一条只有几十字节，超了整批清空（预览可以随时重抓，不值得做 LRU） */
    private const val MAX_ENTRIES = 500
    /** 单页最多读 300KB：标题一定在头部，没必要把整个页面拉下来 */
    private const val MAX_BYTES = 300 * 1024L
    private const val TITLE_MAX_LEN = 200

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /** url -> LinkPreviewInfo 或 NONE（已尝试且失败，本次进程内不再重试） */
    private val mem = ConcurrentHashMap<String, Any>()
    private val NONE = Any()
    private val locks = ConcurrentHashMap<String, Any>()

    private val client: OkHttpClient by lazy {
        val builder = if (HttpClient.isReady()) {
            // 复用进程级客户端（连接池/代理拦截一致），但外站页面不进 App 的接口缓存
            HttpClient.client.newBuilder().cache(null)
        } else {
            OkHttpClient.Builder()
        }
        builder
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 同步查内存缓存：已解析过的直接拿到，未解析/失败都返回 null（不触发网络） */
    fun peek(url: String): LinkPreviewInfo? = mem[url] as? LinkPreviewInfo

    /**
     * 解析链接预览。请在 IO 线程调用。
     * @return 解析成功返回信息；无标题/请求失败/非网页返回 null
     */
    fun load(ctx: Context, url: String): LinkPreviewInfo? {
        (mem[url] as? LinkPreviewInfo)?.let { return it }
        if (mem[url] === NONE) return null
        val lock = locks.getOrPut(url) { Any() }
        return synchronized(lock) {
            (mem[url] as? LinkPreviewInfo)?.let { return@synchronized it }
            if (mem[url] === NONE) return@synchronized null

            val disk = readDisk(ctx, url)
            val info = disk ?: runCatching { fetch(url) }.getOrNull()
            if (info != null) {
                mem[url] = info
                if (disk == null) writeDisk(ctx, info)
            } else {
                mem[url] = NONE
            }
            info
        }
    }

    // ── 网络 + HTML 解析 ──────────────────────────────────────────────

    private fun fetch(url: String): LinkPreviewInfo? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val ctype = resp.header("Content-Type").orEmpty()
            // 只解析网页：PDF / 图片 / 直链文件没有「标题」，也没有卡片可展示
            if (ctype.isNotEmpty() &&
                !ctype.contains("html", true) && !ctype.contains("xml", true)
            ) {
                return null
            }
            val body = resp.body ?: return null
            val source = body.source()
            source.request(MAX_BYTES)          // 最多预读 300KB，够覆盖 <head>
            val bytes = source.buffer.readByteArray()
            if (bytes.isEmpty()) return null

            val html = String(bytes, charsetOf(ctype, bytes))
            val title = extractTitle(html) ?: return null
            return LinkPreviewInfo(url = url, title = title, iconUrl = extractIcon(html, url))
        }
    }

    private fun charsetOf(contentType: String, bytes: ByteArray): Charset {
        Regex("charset=([\\w\\-]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.getOrNull(1)?.let { name ->
                runCatching { return Charset.forName(name) }
            }
        // 响应头没写就看 <meta charset>（国内站点大量 GBK 页面靠这个）
        val head = runCatching {
            String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        }.getOrDefault("")
        Regex("<meta[^>]+charset\\s*=\\s*[\"']?([\\w\\-]+)", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.getOrNull(1)?.let { name ->
                runCatching { return Charset.forName(name) }
            }
        return Charsets.UTF_8
    }

    private val OG_TITLE = Regex(
        """<meta[^>]+(?:property|name)\s*=\s*["']og:title["'][^>]*content\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val OG_TITLE_REVERSED = Regex(
        """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]*(?:property|name)\s*=\s*["']og:title["']""",
        RegexOption.IGNORE_CASE
    )
    private val TITLE_TAG = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)

    private fun extractTitle(html: String): String? {
        val raw = OG_TITLE.find(html)?.groupValues?.getOrNull(1)
            ?: OG_TITLE_REVERSED.find(html)?.groupValues?.getOrNull(1)
            ?: TITLE_TAG.find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val text = runCatching {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        }.getOrElse { raw }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isEmpty()) return null
        return if (text.length > TITLE_MAX_LEN) text.take(TITLE_MAX_LEN) + "…" else text
    }

    private val LINK_TAG = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val REL_ATTR = Regex("""rel\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val HREF_ATTR = Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    private fun extractIcon(html: String, baseUrl: String): String {
        var best: String? = null
        var bestScore = 0
        for (tag in LINK_TAG.findAll(html).map { it.value }) {
            val rel = REL_ATTR.find(tag)?.groupValues?.getOrNull(1)?.lowercase() ?: continue
            if (!rel.contains("icon")) continue
            val href = HREF_ATTR.find(tag)?.groupValues?.getOrNull(1)?.trim() ?: continue
            if (href.isEmpty() || href.startsWith("data:", true)) continue
            val score = when {
                rel.contains("apple-touch-icon") -> 3
                rel.contains("shortcut") -> 2
                else -> 1
            }
            if (score > bestScore) {
                bestScore = score
                best = href
            }
        }
        best?.let { resolveUrl(baseUrl, it) }?.let { return it }
        return defaultFavicon(baseUrl)
    }

    private fun resolveUrl(base: String, href: String): String? {
        runCatching {
            java.net.URI(base).resolve(href.replace(" ", "%20")).toString()
        }.getOrNull()?.let { if (it.startsWith("http", true)) return it }
        return href.takeIf { it.startsWith("http", true) }
    }

    private fun defaultFavicon(base: String): String = runCatching {
        val u = java.net.URI(base)
        val scheme = u.scheme ?: "https"
        val host = u.host ?: return ""
        val port = if (u.port > 0) ":${u.port}" else ""
        "$scheme://$host$port/favicon.ico"
    }.getOrDefault("")

    // ── 磁盘缓存 ────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readDisk(ctx: Context, url: String): LinkPreviewInfo? = runCatching {
        val raw = prefs(ctx).getString(url, null) ?: return null
        val o = JSONObject(raw)
        val title = o.optString("t")
        if (title.isBlank()) return null
        LinkPreviewInfo(url, title, o.optString("i"))
    }.getOrNull()

    private fun writeDisk(ctx: Context, info: LinkPreviewInfo) {
        runCatching {
            val p = prefs(ctx)
            // 预览数据可随时重抓，超限整批清空即可，不做 LRU
            if (p.all.size >= MAX_ENTRIES) p.edit().clear().apply()
            p.edit()
                .putString(info.url, JSONObject().put("t", info.title).put("i", info.iconUrl).toString())
                .apply()
        }
    }
}
