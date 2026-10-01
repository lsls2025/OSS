package com.aurora.chat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * 无障碍「操控手机」能力：读屏、点击、滑动、输入、全局按键、非 root 截屏。
 *
 * 依赖 [PrivacyAccessibilityService]（同一份无障碍服务，用户只需开一次）。
 * 全部方法都不抛异常：服务未开启时返回失败原因，由调用方（AI 工具层）转成提示，
 * 并顺带打开系统无障碍设置页引导用户授权。
 */
object PhoneControl {

    /** 无障碍服务是否已就绪（服务已连接才算就绪） */
    fun isReady(): Boolean = PrivacyAccessibilityService.isRunning()

    /** 系统设置里本服务是否处于「已开启」状态（开 ≠ 已连上，见下） */
    fun isEnabledInSystem(ctx: Context): Boolean = PrivacyAccessibilityService.isEnabledInSystem(ctx)

    /**
     * 等待无障碍服务就绪。
     *
     * 为什么需要它：进程刚启动（冷启动、崩溃重启、**重装后第一次打开**）时，
     * 系统还没把无障碍服务绑定回来，`isRunning()` 会短暂为 false。若此时直接判定
     * 「未开启」并跳系统设置页，用户会觉得「明明我已经开了还跳，莫名其妙」。
     * 所以先给服务一点时间连上来（或直接重试），再下结论。
     *
     * @return true = 已就绪；false = 超时仍未连上
     */
    suspend fun awaitReady(timeoutMs: Long = 2500L): Boolean {
        if (isReady()) return true
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            kotlinx.coroutines.delay(150L)
            if (isReady()) return true
        }
        return isReady()
    }

    fun service(): PrivacyAccessibilityService? = PrivacyAccessibilityService.instance()

    /** 未授权时的统一提示 */
    const val NOT_READY = "无障碍服务未开启(需要它才能操控手机:读屏、点击、输入)"

    /** 跳到系统「无障碍」设置页，引导用户开启本应用的无障碍服务 */
    fun openSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    /** 元素类型的中文简称，方便 AI 理解读屏结果 */
    private fun shortType(cls: String): String = when {
        cls.endsWith("EditText") -> "输入框"
        cls.endsWith("Button") -> "按钮"
        cls.endsWith("TextView") -> "文本"
        cls.endsWith("ImageView") -> "图片"
        cls.contains("RecyclerView") || cls.contains("ListView") || cls.contains("ScrollView") -> "列表"
        cls.contains("WebView") -> "网页"
        cls.endsWith("CheckBox") || cls.endsWith("Switch") -> "开关"
        else -> cls.substringAfterLast('.')
    }

    /**
     * 等待界面「稳定」：直到距上次窗口内容变化超过 [quietMs] 毫秒，或达到 [timeoutMs] 上限。
     *
     * 为什么需要：点击应用图标后目标应用冷启动需 300ms~2s，期间 rootInActiveWindow
     * 仍返回上一个界面的节点树。若不等待就读屏，AI 会拿到旧数据并误判（表现为「一直显示上一轮」）。
     *
     * @return true 表示检测到界面已稳定；false 表示超时（此时仍会读屏，但结果可能是过渡态）
     */
    suspend fun waitForStableScreen(quietMs: Long = 300L, timeoutMs: Long = 2500L): Boolean {
        val start = android.os.SystemClock.uptimeMillis()
        while (true) {
            val since = PrivacyAccessibilityService.msSinceLastWindowChange()
            if (since >= quietMs) return true
            if (android.os.SystemClock.uptimeMillis() - start >= timeoutMs) return false
            kotlinx.coroutines.delay(50L)
        }
    }

    /**
     * 读屏：返回当前界面的包名与可见文本元素（含中心坐标，可直接用于点击）。
     * @param waitStable 是否先等待界面稳定（点击 / 跳转后建议 true，纯查看可 false）
     * 失败返回空串，由调用方给出提示。
     */
    suspend fun readScreenStable(maxNodes: Int = 80, waitStable: Boolean = true): String {
        if (waitStable) waitForStableScreen()
        return readScreen(maxNodes)
    }

    /**
     * 读屏：返回当前界面的包名与可见文本元素（含中心坐标，可直接用于点击）。
     * 失败返回空串，由调用方给出提示。
     */
    fun readScreen(maxNodes: Int = 80): String {
        val s = service() ?: return ""
        val root = try { s.rootInActiveWindow } catch (_: Exception) { null } ?: return ""
        val arr = JSONArray()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && arr.length() < maxNodes && visited < 800) {
            val n = queue.removeFirst()
            visited++
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val label = text.ifEmpty { desc }
            val editable = try { n.isEditable } catch (_: Exception) { false }
            if (label.isNotEmpty() || editable) {
                val r = Rect()
                try { n.getBoundsInScreen(r) } catch (_: Exception) { }
                if (r.width() > 0 && r.height() > 0) {
                    arr.put(
                        JSONObject()
                            .put("text", label.take(60))
                            .put("type", shortType(n.className?.toString().orEmpty()))
                            .put("clickable", try { n.isClickable } catch (_: Exception) { false })
                            .put("editable", editable)
                            .put("x", r.centerX())
                            .put("y", r.centerY())
                    )
                }
            }
            for (i in 0 until n.childCount) {
                try { n.getChild(i)?.let { queue.add(it) } } catch (_: Exception) { }
            }
        }
        return JSONObject()
            .put("package", try { root.packageName?.toString().orEmpty() } catch (_: Exception) { "" })
            .put("element_count", arr.length())
            .put("elements", arr)
            .toString()
    }

    /** 在节点树里按文字找元素（先精确匹配，再包含匹配），返回可点击祖先 */
    private fun findNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        for (exact in listOf(true, false)) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 800) {
                val n = queue.removeFirst()
                visited++
                val label = n.text?.toString()?.trim().orEmpty()
                    .ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }
                if (label.isNotEmpty() && (if (exact) label == target else label.contains(target))) {
                    var c: AccessibilityNodeInfo? = n
                    var up = 0
                    while (c != null && !c.isClickable && up < 6) {
                        c = try { c.parent } catch (_: Exception) { null }
                        up++
                    }
                    return c ?: n
                }
                for (i in 0 until n.childCount) {
                    try { n.getChild(i)?.let { queue.add(it) } } catch (_: Exception) { }
                }
            }
        }
        return null
    }

    /** 点击/长按指定坐标 */
    fun tap(x: Int, y: Int, long: Boolean = false): Boolean {
        val s = service() ?: return false
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, if (long) 700L else 60L))
                .build()
            s.dispatchGesture(g, null, null)
        } catch (_: Exception) {
            false
        }
    }

    /** 按文字找元素并点击（长按可选）；返回被点元素描述，找不到返回 null */
    fun tapByText(text: String, long: Boolean = false): JSONObject? {
        val s = service() ?: return null
        val root = try { s.rootInActiveWindow } catch (_: Exception) { null } ?: return null
        val n = findNode(root, text) ?: return null
        val r = Rect()
        try { n.getBoundsInScreen(r) } catch (_: Exception) { return null }
        if (r.width() <= 0 || r.height() <= 0) return null
        if (!tap(r.centerX(), r.centerY(), long)) return null
        return JSONObject()
            .put("text", text)
            .put("x", r.centerX())
            .put("y", r.centerY())
    }

    /** 滑动（从起点拖到终点，durationMs 越大越慢） */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val s = service() ?: return false
        return try {
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.toLong().coerceIn(50L, 3000L)))
                .build()
            s.dispatchGesture(g, null, null)
        } catch (_: Exception) {
            false
        }
    }

    /** 全局按键：back / home / recents / notifications / quick_settings */
    fun globalAction(name: String): Boolean {
        val s = service() ?: return false
        val code = when (name.trim().lowercase()) {
            "back", "返回" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home", "桌面", "主页" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent", "最近", "多任务" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "notification", "通知栏", "下拉通知" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "settings", "快捷设置" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return try { s.performGlobalAction(code) } catch (_: Exception) { false }
    }

    /**
     * 往当前输入框写文本：优先焦点输入框，否则第一个可编辑框。
     * 返回写入的元素描述；失败返回 null。
     */
    fun setText(text: String): JSONObject? {
        val s = service() ?: return null
        val root = try { s.rootInActiveWindow } catch (_: Exception) { null } ?: return null
        var target: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 800) {
            val n = queue.removeFirst()
            visited++
            val editable = try { n.isEditable } catch (_: Exception) { false }
            if (editable) {
                if (try { n.isFocused } catch (_: Exception) { false }) { target = n; break }
                if (target == null) target = n
            }
            for (i in 0 until n.childCount) {
                try { n.getChild(i)?.let { queue.add(it) } } catch (_: Exception) { }
            }
        }
        val node = target ?: return null
        return try {
            if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!ok) return null
            val r = Rect()
            try { node.getBoundsInScreen(r) } catch (_: Exception) { }
            JSONObject().put("x", r.centerX()).put("y", r.centerY()).put("length", text.length)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 非 root 截屏（Android 11+ 无障碍截屏），PNG 写入 [saveTo]。
     * 成功返回 null，失败返回原因。
     */
    suspend fun screenshot(saveTo: File): String? = screenshot(saveTo, Bitmap.CompressFormat.PNG, 100)

    /**
     * 非 root 截屏，按指定格式与质量写入 [saveTo]。
     *
     * 用 JPEG + 85 画质时体积通常只有 PNG 的 1/5 左右，适合作为图片消息发送：
     * PNG 截图在高分屏上常达 5~10MB，编码写盘、本地消息库加解密、Coil 解码都会明显变慢。
     * 注意：图片消息走 file:// 本地路径，不经过网络；这里优化的是磁盘 IO 与解码开销。
     *
     * @param format PNG 或 JPEG
     * @param quality 仅 JPEG 生效（0~100）
     */
    suspend fun screenshot(
        saveTo: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): String? {
        val s = service() ?: return NOT_READY
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "当前系统低于 Android 11，无障碍截屏不可用（可改用 root 截屏）"
        }
        val executor = java.util.concurrent.Executor { r -> Handler(Looper.getMainLooper()).post(r) }
        val bmp = withTimeoutOrNull(6000L) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                try {
                    s.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        executor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                val b = try {
                                    Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                        ?.copy(Bitmap.Config.ARGB_8888, false)
                                } catch (_: Exception) {
                                    null
                                } finally {
                                    try { result.hardwareBuffer.close() } catch (_: Exception) { }
                                }
                                if (cont.isActive) cont.resume(b)
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } ?: return "无障碍截屏超时"
        bmp ?: return "无障碍截屏失败（系统未返回画面）"
        return try {
            withContext(Dispatchers.IO) {
                saveTo.parentFile?.mkdirs()
                saveTo.outputStream().use { bmp.compress(format, quality.coerceIn(1, 100), it) }
            }
            if (!saveTo.exists() || saveTo.length() == 0L) "截屏文件写入失败" else null
        } catch (e: Exception) {
            "截屏保存失败：${e.message ?: e.javaClass.simpleName}"
        } finally {
            bmp.recycle()
        }
    }

    // ==================== 卸载应用（本应用 / 任意第三方应用） ====================

    /** 一条「已安装应用」记录。 */
    data class InstalledApp(
        val packageName: String,
        val label: String,
        /** 系统预装应用：静默卸载默认拒绝，需显式 force，避免把手机搞坏。 */
        val isSystemApp: Boolean,
    )

    /** 指代「本应用自己」的说法。 */
    private val SELF_TOKENS = setOf(
        "自己", "本应用", "本app", "本软件", "这个app", "这个应用", "这个软件", "当前应用",
        "aurorachat", "aurora", "aurora chat", "本程序", "这个程序"
    )

    /** 判断一段描述是不是在说「本应用」。 */
    fun isSelfQuery(query: String): Boolean {
        val q = query.trim().lowercase().replace(" ", "").replace("_", "")
        if (q.isEmpty()) return false
        return SELF_TOKENS.any { it.lowercase().replace(" ", "") == q }
    }

    /** 读取本应用的显示名。 */
    fun appLabel(ctx: Context): String? = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(ctx.packageName, 0)).toString()
    } catch (_: Exception) { null }

    /** 该包是否为系统预装应用。 */
    fun isSystemPackage(ctx: Context, pkg: String): Boolean = try {
        (ctx.packageManager.getApplicationInfo(pkg, 0).flags and
            android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: Exception) { false }

    /** 该包当前是否还装在手机上（卸载成功后返回 false）——判定卸载是否真的成功的唯一可信依据。 */
    fun isPackageInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: Exception) { false }

    /** 当前活动窗口的包名（需无障碍已开启）；读不到返回 null。 */
    fun activeWindowPackage(): String? = try {
        service()?.rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) { null }

    /**
     * 按「包名」或「应用名」解析已安装应用。**只读，无任何副作用。**
     *
     * - query 是包名（含「.」且不含空格）→ 先按包名精确查
     * - query 是「自己」这类词 → 只返回本应用
     * - 否则按应用名匹配：先精确全等，有全等就只用全等的那批；否则用「包含」匹配
     *
     * Manifest 已声明 QUERY_ALL_PACKAGES，所以这里能看到全部已安装应用。
     */
    fun resolveInstalledApps(ctx: Context, query: String): List<InstalledApp> {
        val pm = try { ctx.packageManager } catch (_: Exception) { return emptyList() }
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()

        if (isSelfQuery(raw)) {
            return listOf(InstalledApp(ctx.packageName, appLabel(ctx) ?: "本应用",
                isSystemPackage(ctx, ctx.packageName)))
        }
        // 1) 包名精确匹配
        if (raw.contains('.') && !raw.contains(' ')) {
            try {
                val ai = pm.getApplicationInfo(raw, 0)
                return listOf(InstalledApp(ai.packageName, pm.getApplicationLabel(ai).toString(),
                    ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0))
            } catch (_: Exception) { /* 不是包名，继续按应用名找 */ }
        }
        // 2) 应用名匹配
        val q = raw.lowercase()
        val apps = try { pm.getInstalledApplications(0) } catch (_: Exception) { emptyList() }
        val exact = ArrayList<InstalledApp>()
        val partial = ArrayList<InstalledApp>()
        for (ai in apps) {
            val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { continue }
            val l = label.lowercase()
            val entry = InstalledApp(ai.packageName, label,
                ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
            if (l == q) exact.add(entry) else if (l.contains(q)) partial.add(entry)
        }
        val chosen = if (exact.isNotEmpty()) exact else partial
        return chosen.sortedBy { it.label }
    }

    /**
     * 唤起系统卸载器的结果。
     *
     * 之所以要三态而不是「成功 / 失败」：`startActivity` 不抛异常并不等于弹窗真的出现了
     * （后台启动 Activity 限制会静默吞掉它）。而「确实没弹」和「弹了但我探测不到」是两回事
     * ——把后者当失败，就会让 AI 反复重试、越试越乱。这里如实区分。
     */
    data class UninstallerLaunch(
        /** true = 已确认卸载器出现在前台 */
        val verified: Boolean,
        /** true = startActivity 未抛异常（可能弹了，也可能被系统静默吞掉） */
        val started: Boolean,
        /** 真正的失败原因（started=false 时才有值） */
        val error: String? = null,
        /** 给日志/排查用的补充说明 */
        val detail: String = "",
    )

    /**
     * 方式二（默认，推荐）：唤起系统卸载器，让用户自己点「确定」。
     *
     * 走标准 ACTION_DELETE + **目标包名**，系统会弹一个「要卸载此应用吗？」的确认框。
     * 这类框挂在 com.android.packageinstaller 下，不受本应用控制，最稳妥、最不容易
     * 被 ROM 拦截；缺点是必须由用户亲手点最后一下。
     *
     * **这里必须显式传入目标包名。** 早期版本把包名写死成 ctx.packageName，
     * 结果「卸载微信」也会把 Aurora Chat 自己删掉——这是绝不能复现的事故。
     *
     * 三个必须处理的坑：
     * 1. **必须在主线程调用** startActivity——工具层跑在 Dispatchers.IO 上，
     *    从后台线程启动 Activity 在部分 ROM 上会被静默丢弃（不抛异常，活像成功）。
     * 2. **后台启动 Activity 限制**（Android 10+）——本应用退到后台时系统会屏蔽这次启动，
     *    所以先确认自己在前台，不在就拉回前台再启动。
     * 3. **启动后尽量核实**——用无障碍读「当前活动窗口包名」判断卸载器是否真的到了前台。
     *    无障碍不可用时**无法核实**，此时如实标记为「未核实」，既不谎报成功、
     *    也不谎报失败（后者会让 AI 无意义地反复重试）。
     *
     * @param packageName 目标应用包名，默认本应用
     */
    suspend fun openSystemUninstaller(
        ctx: Context,
        packageName: String = ctx.packageName
    ): UninstallerLaunch = withContext(Dispatchers.Main) {
        val pkg = packageName.trim().ifEmpty { ctx.packageName }
        val who = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { if (pkg == ctx.packageName) "Aurora Chat" else pkg }

        val self = ctx.packageName
        try {
            // 坑 2：先把本应用拉回前台，否则后台启动 Activity 会被系统静默屏蔽。
            val activeBefore = activeWindowPackage()
            val isForeground = if (activeBefore != null) {
                activeBefore == self
            } else {
                try {
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    (am?.runningAppProcesses ?: emptyList()).any {
                        it.processName == self &&
                            it.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    }
                } catch (_: Exception) { false }
            }

            if (!isForeground) {
                // 先把本应用拉回前台：从当前 Activity 拿启动 Intent 重开一次
                try {
                    val launch = ctx.packageManager.getLaunchIntentForPackage(self)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        ctx.startActivity(launch)
                        kotlinx.coroutines.delay(600L)
                    }
                } catch (_: Exception) { }
            }

            // 坑 3：逐个尝试可用的卸载入口。
            // ACTION_DELETE 是标准入口；ACTION_UNINSTALL_PACKAGE 是它的前身（已废弃，
            // 但部分 ROM 只认这个）。只有在前一个「确实没生效」时才试下一个，避免弹两个框。
            //
            // 判据只能是「前台窗口变了没有」——**不能**再去猜卸载框挂在哪个包名下。
            // 曾经写成「必须命中 packageinstaller 才算弹出」，结果 ColorOS/OxygenOS 这类
            // 自研卸载框的包名对不上，框明明弹了却被判成没弹、直接返回失败，
            // 表现为用户说的「无法完成，我明明都开无障碍了」。
            val candidates = listOf(
                Intent.ACTION_DELETE to "ACTION_DELETE",
                Intent.ACTION_UNINSTALL_PACKAGE to "ACTION_UNINSTALL_PACKAGE"
            )
            val tried = ArrayList<String>()
            var lastError: String? = null
            var sent = false        // startActivity 成功发出（不代表界面真的弹了）
            var verified = false    // 已确认前台窗口换成非本应用 / 目标包已消失
            var sawWindow = false   // 是否读到过窗口（决定我们有没有「核实」的能力）
            var lastWindow = ""
            var gone = false        // 目标包已从系统里消失

            for ((action, name) in candidates) {
                tried.add(name)
                try {
                    val it = Intent(action, Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    ctx.startActivity(it)
                    sent = true
                } catch (e: SecurityException) {
                    // 最典型的就是清单里缺 REQUEST_DELETE_PACKAGES
                    lastError = "$name 被系统拒绝：${e.message ?: "SecurityException"}" +
                        "（多为缺少 REQUEST_DELETE_PACKAGES 权限）"
                    continue
                } catch (e: Exception) {
                    lastError = "$name 启动失败：${e.message ?: e.javaClass.simpleName}"
                    continue
                }

                // 等前台换窗口：
                //  - 目标包消失       → 已卸载成功
                //  - 前台不再是本应用 → 卸载界面（或启动器）已到前台，收工
                //  - 仍然是我们自己   → 这次 action 没生效，试下一个
                //  - 读不到窗口       → 没能力核实，直接收工（避免重复弹框）
                var waited = 0L
                while (waited < 2400L && !verified && !gone) {
                    kotlinx.coroutines.delay(300L)
                    waited += 300L
                    if (!isPackageInstalled(ctx, pkg)) { gone = true; break }
                    val a = activeWindowPackage() ?: continue
                    sawWindow = true
                    lastWindow = a
                    if (a != self) { verified = true; break }
                }
                if (verified || gone || !sawWindow) break
            }

            com.aurora.chat.ErrorReporter.debug(
                "AI_Uninstall",
                "唤起卸载器 pkg=$pkg who=$who selfFg=$isForeground tried=$tried " +
                    "sent=$sent verified=$verified gone=$gone sawWindow=$sawWindow lastWindow='$lastWindow'"
            )

            when {
                gone -> UninstallerLaunch(verified = true, started = true, detail = "目标包已从系统中移除")
                verified -> UninstallerLaunch(verified = true, started = true, detail = "已确认卸载界面到前台")
                // 无障碍没连上 → 读不到任何窗口 → 失去探测能力。此时不能下任何结论：
                // 既不谎报成功，也不谎报失败（后者会让 AI 无意义地反复重试）。
                sent && !sawWindow -> UninstallerLaunch(
                    verified = false, started = true,
                    detail = "已发出卸载请求；无障碍未连接，无法核实界面是否弹出"
                )
                sent -> UninstallerLaunch(
                    verified = false, started = false,
                    error = "「$who」的卸载界面没能显示出来（发出 ${tried.joinToString("/")} 后，" +
                        "前台仍是 '${lastWindow.ifBlank { self }}'）。请手动到「设置 → 应用管理」里卸载「$who」。",
                    detail = "已 startActivity 但前台未切换"
                )
                else -> UninstallerLaunch(
                    verified = false, started = false,
                    error = "无法唤起系统卸载器：${lastError ?: "系统里没有能处理卸载请求的组件"}",
                    detail = "所有卸载入口都启动失败 tried=$tried"
                )
            }
        } catch (e: Exception) {
            UninstallerLaunch(
                verified = false, started = false,
                error = "无法唤起系统卸载器：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * root 静默卸载：`pm uninstall --user 0 <pkg>`，屏幕上不会有任何确认框。
     *
     * 需要 su 可用（即 agent 访问模式为 root）。这是唯一真正「全自动、不碰界面」的卸载方式，
     * 因此对第三方应用优先尝试它；失败再退回无障碍路线。
     *
     * @return null 表示卸载成功；否则返回失败原因
     */
    suspend fun uninstallByRoot(ctx: Context, packageName: String): String? = withContext(Dispatchers.IO) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return@withContext "包名为空"
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm uninstall --user 0 $pkg"))
            val finished = proc.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                runCatching { proc.destroyForcibly() }
                return@withContext "root 卸载超时（25 秒），已强制终止"
            }
            val out = runCatching { proc.inputStream.bufferedReader().readText().trim() }.getOrDefault("")
            val err = runCatching { proc.errorStream.bufferedReader().readText().trim() }.getOrDefault("")
            val exit = proc.exitValue()
            when {
                !isPackageInstalled(ctx, pkg) -> null
                exit == 0 -> "root 卸载命令已执行，但「$pkg」仍然存在：${(err.ifBlank { out }).take(200)}"
                else -> "root 卸载失败（exit=$exit）：${(err.ifBlank { out }).take(200)}"
            }
        } catch (e: Exception) {
            "root 卸载异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 无障碍自动卸载**本应用**（兼容既有调用点）。
     * 实现见 [uninstallByAccessibility]。
     *
     * @param label 桌面上本应用的显示名；传空则自动从 PackageManager 读取
     * @return null 表示卸载已确认触发；否则返回人类可读的失败原因
     */
    suspend fun uninstallSelfByAccessibility(ctx: Context, label: String = "", timeoutMs: Long = 25000L): String? =
        uninstallByAccessibility(ctx, ctx.packageName, label, timeoutMs)

    /**
     * 无障碍自动卸载**任意应用**（本应用或第三方应用），全程不需要用户动手。
     *
     * 三条路，按优先级依次尝试：
     * 1. **本应用**：回桌面 → 按应用名长按自己的桌面图标 → 点「卸载」→ 点「确定」
     *    （走启动器，可以绕开「后台启动 Activity 限制」）
     * 2. **其它应用**：`ACTION_DELETE` 指定目标包名唤起系统卸载器 → 点「确定」
     *    （第三方应用没必要去桌面找图标，那条路更脆；系统卸载器一定能锁定目标）
     * 3. **兜底**：以上都不成时，打开「应用详情页」点「卸载」——该页面每个 ROM 都有。
     *
     * 三条路最后都落到同一个系统卸载确认框，由 [tapUninstallConfirm] 尝试点掉。
     *
     * 关于「卸载确认框能不能被无障碍点掉」：**能。** `dispatchGesture` 由系统自身派发，
     * 不属于「来自悬浮窗的触摸」，因此不受 `filterTouchesWhenObscured` 限制，对系统对话框
     * 同样有效。（早期注释里写的「Android 7+ 屏蔽无障碍点击」是错的，已更正。）
     * 真正会出问题的是**卸载框压根没弹出来**——那通常是清单缺 `REQUEST_DELETE_PACKAGES`，
     * 或本应用不在前台导致后台启动 Activity 被系统静默拦截。
     *
     * @param packageName 目标应用包名；等于本应用包名时走「自己」那条路
     * @param label 目标应用显示名；传空则从 PackageManager 读取
     * @param timeoutMs 整体超时预算
     * @return null 表示卸载已确认触发；否则返回人类可读的失败原因
     */
    suspend fun uninstallByAccessibility(
        ctx: Context,
        packageName: String = ctx.packageName,
        label: String = "",
        timeoutMs: Long = 25000L
    ): String? {
        if (!isReady()) {
            // 区分「系统里根本没开」和「开了但服务还没连上」——后者直接说「未开启」
            // 会让用户觉得莫名其妙（他明明开着）。这是此前反复跳设置页的同一个病根。
            return if (isEnabledInSystem(ctx))
                "无障碍已在系统里开启，但服务还没连接上（刚重启/刚重装时服务正在重新绑定）。请等几秒后重试。"
            else NOT_READY
        }
        val pkg = packageName.trim().ifEmpty { ctx.packageName }
        val isSelf = pkg == ctx.packageName
        val appLabel = label.trim().ifEmpty {
            try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { if (isSelf) "Aurora Chat" else pkg }
        }
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        fun remain(): Long = (deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)

        try {
            Handler(Looper.getMainLooper()).post {
                val tip = if (isSelf) "正在卸载，后会有期！" else "正在自动卸载「$appLabel」"
                android.widget.Toast.makeText(ctx, tip, android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) { }

        var iconFound = false
        if (isSelf) {
            // 1) 回桌面（先收键盘/返回，再 Home，确保真的在桌面而不是某个二级页）
            globalAction("back")
            kotlinx.coroutines.delay(250L)
            globalAction("home")
            waitForStableScreen(quietMs = 400L, timeoutMs = 3000L)

            // 2) 在桌面上按应用名长按自己的图标。
            //    桌面图标多数是纯 ImageView，无障碍不一定读得到 text/contentDescription；
            //    读不到就直接退回系统卸载器——**不做「扫描可长按节点」的兜底**，
            //    因为误长按到别的图标、进而卸错软件的代价远大于收益。
            var launched = false
            val scopes = ArrayList<String>()
            scopes.add(appLabel)
            scopes.add(appLabel.replace(" ", ""))
            for (scope in scopes) {
                if (scope.isBlank()) continue
                if (tapByText(scope, long = true) != null) { iconFound = true; break }
            }
            if (iconFound) {
                kotlinx.coroutines.delay(900L)
                waitForStableScreen(quietMs = 300L, timeoutMs = 2500L)
                // 长按图标后一般是「应用信息 / 卸载 / 移除」浮动菜单
                val menuHit = tapByText("卸载", long = false)
                    ?: tapByText("Remove", long = false)
                    ?: tapByText("Uninstall", long = false)
                    ?: tapByText("应用信息", long = false)
                if (menuHit != null) {
                    kotlinx.coroutines.delay(700L)
                    waitForStableScreen(quietMs = 300L, timeoutMs = 2500L)
                    // 若是「应用信息」页，还要再点一次里面的「卸载」
                    tapByText("卸载", long = false) ?: tapByText("Uninstall", long = false)
                    launched = true
                }
            }
            if (launched) return tapUninstallConfirm(ctx, pkg, appLabel, remain())
        }

        // 3) 第三方应用（或本应用的桌面图标没找到）→ 系统卸载器 + 无障碍点确认。
        //    系统卸载器是唯一能保证「删的就是目标应用」的入口。
        val launch = openSystemUninstaller(ctx, pkg)
        if (launch.error != null) {
            val err = launch.error
            return when {
                !isSelf -> "无法唤起系统卸载器来卸载「$appLabel」：$err"
                iconFound -> "长按桌面图标后没能找到「卸载」菜单，且无法唤起系统卸载器：$err"
                else -> "桌面上没能定位到「$appLabel」图标（可能被收进文件夹/分屏/启动器不暴露图标），且无法唤起系统卸载器：$err"
            }
        }
        // 无论是否「已核实」，都继续尝试点确认框——
        // 未核实只说明探测手段缺失，不代表弹窗没出来。最终成败由 tapUninstallConfirm 以
        // 「目标包是否真的消失」来判定，不靠这里的猜测。
        kotlinx.coroutines.delay(1200L)
        waitForStableScreen(quietMs = 400L, timeoutMs = 3500L)
        val first = tapUninstallConfirm(ctx, pkg, appLabel, remain())
        if (first == null) return null

        // 4) 兜底：改走「应用详情页」。
        //    各 ROM 的系统卸载器宿主差异很大，但「应用详情页」是标准页面、结构统一、
        //    必定带「卸载」按钮，是仅次于系统卸载器的第二条可靠路径。
        //    只在第一条路失败、且预算还够时才走。
        if (remain() > 9000L) {
            val second = openAppDetailAndUninstall(ctx, pkg, appLabel, remain())
            if (second == null) return null
            return "$first 另外也试了「应用详情页」的卸载按钮，仍未成功：$second"
        }
        return first
    }

    /**
     * 兜底路线：打开「应用详情页」→ 点页面里的「卸载」→ 点确认框。
     *
     * 为什么值得做兜底：不同 ROM 的系统卸载器宿主包名/交互差异很大，而
     * `ACTION_APPLICATION_DETAILS_SETTINGS` 是每个 ROM 都必须提供的标准页面，
     * 结构稳定、必然带「卸载」按钮。
     *
     * @return null 表示已成功；否则返回失败原因
     */
    private suspend fun openAppDetailAndUninstall(
        ctx: Context, pkg: String, appLabel: String, budgetMs: Long
    ): String? {
        try {
            withContext(Dispatchers.Main) {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (e: Exception) {
            return "无法打开「$appLabel」的应用详情页：${e.message ?: e.javaClass.simpleName}"
        }
        try {
            kotlinx.coroutines.delay(1300L)
            waitForStableScreen(quietMs = 350L, timeoutMs = 3000L)
            // 详情页里的卸载入口：多数 ROM 是文字「卸载」，部分只给图标（靠 contentDescription）
            val hit = tapByText("卸载", long = false)
                ?: tapByText("Uninstall", long = false)
                ?: tapByText("卸载应用", long = false)
                ?: tapByText("删除", long = false)
            if (hit == null) {
                return "应用详情页里没找到「卸载」按钮（当前前台窗口：${activeWindowPackage() ?: "读取不到"}）"
            }
            kotlinx.coroutines.delay(1000L)
            waitForStableScreen(quietMs = 350L, timeoutMs = 3000L)
            return tapUninstallConfirm(ctx, pkg, appLabel, budgetMs)
        } catch (e: Exception) {
            return "在应用详情页里卸载「$appLabel」时出错：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 点掉系统卸载确认框的「确定」。
     *
     * 设计原则只有一条：**唯一可信的成功判据是「目标包真的从手机上消失了」**。
     * 不去判断卸载框挂在哪个包名下（`packageinstaller` / 厂商自研组件各不相同），
     * 也不去猜框有没有弹——猜错的代价是谎报失败，然后 AI 反复重试、越试越乱。
     *
     * 补充事实（此前注释写错了）：`dispatchGesture` 对系统对话框**是生效的**。
     * 它由系统自身派发，不等同于来自悬浮窗的触摸，所以不受 `filterTouchesWhenObscured`
     * 限制。点不动只可能是坐标/节点没找到，不是被系统拦了。
     *
     * @return null 表示卸载已确认触发；否则返回失败原因
     */
    private suspend fun tapUninstallConfirm(
        ctx: Context, pkg: String, appLabel: String, budgetMs: Long
    ): String? {
        val deadline = android.os.SystemClock.uptimeMillis() + budgetMs.coerceAtLeast(12000L)
        fun remain(): Long = (deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
        // 顺序有讲究：确认键优先。把「卸载 / Uninstall」放最后——那两个字也出现在标题里，
        // 排前面容易把点击浪费在标题上。
        val confirmWords = arrayOf(
            "确定", "确认", "OK", "Ok", "ok", "Confirm", "继续", "允许",
            "卸载", "Uninstall", "删除", "Delete"
        )
        val self = ctx.packageName
        var taps = 0
        var lastWindow = ""

        // 0) 先等卸载界面出现——走「长按桌面图标」那条路时，框是点完菜单之后才弹的。
        var waited = 0L
        while (waited < 5000L) {
            if (!isPackageInstalled(ctx, pkg)) return null
            val a = activeWindowPackage()
            if (a != null) {
                lastWindow = a
                if (a != self) break
            }
            kotlinx.coroutines.delay(350L)
            waited += 350L
        }

        // 1) 反复点确认键，直到目标包消失或预算耗尽。
        while (remain() > 700L) {
            if (!isPackageInstalled(ctx, pkg)) return null

            val a = activeWindowPackage()
            if (a != null) lastWindow = a

            // 前台只要不再是我们自己，就认为卸载界面（或应用详情页）在前台，尝试点确认。
            // 刻意**不**匹配卸载框的宿主包名：不同 ROM 的卸载框宿主不一样，
            // 硬匹配会把「弹了但包名不认识」误判成「没弹」。
            if (a == null || a != self) {
                for (w in confirmWords) {
                    if (tapByText(w, long = false) != null) {
                        taps++
                        kotlinx.coroutines.delay(900L)
                        break
                    }
                }
            } else if (taps > 0) {
                // 卸载框已关闭、本应用回到前台 → 大概率是用户点了「取消」。
                // 给系统 1 秒把包真正移除，再下结论（别把「刚点完确定还没删完」误判成取消）。
                kotlinx.coroutines.delay(1000L)
                if (!isPackageInstalled(ctx, pkg)) return null
                return "「$appLabel」的卸载框已关闭，但应用仍然在（多半是点了「取消」，或系统没把自动点击当回事）。" +
                    "如确实要卸载，请重试并在弹出的确认框上点「确定」。"
            }
            kotlinx.coroutines.delay(450L)
        }

        if (!isPackageInstalled(ctx, pkg)) return null
        return if (taps > 0) {
            "已自动点击「$appLabel」的卸载确认框 $taps 次，但目标包（$pkg）仍未消失。" +
                "请手动点一下屏幕上的「确定」，或到「设置 → 应用管理」里卸载。"
        } else {
            "没能找到「$appLabel」的卸载确认按钮（当前前台窗口：${lastWindow.ifBlank { "读取不到" }}）。" +
                "请手动点一下屏幕上的「确定」，或到「设置 → 应用管理」里卸载。"
        }
    }
}
