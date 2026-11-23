package com.aurora.chat.ui.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具调用展示信息的**唯一事实源**（目标 + 参数摘要）。
 *
 * 背景（用户明确要求）：
 *  · 工具名本身不需要改，但**显示**必须汉化，而且不能只写「正在调用工具…」这种无信息量的泛化文案；
 *  · 能追溯的一定要追溯出来 —— 例如「搜索文件：MainActivity.kt」里的文件名必须是 AI 本次真正搜的那个；
 *  · 所有工具都要显示具体目标，一个不漏；将来新增工具也不能退化。
 *
 * 因此这里不再用「一个巨大 when 逐个 if」的写法，而是：
 *  1. [TARGET_RULES] 表驱动：每个工具一条规则，明确它该显示哪个/哪些参数；
 *  2. [NO_TARGET_TOOLS] 白名单：确实没有对象可显示的工具（查设备状态、列备份…），显式声明而不是漏配；
 *  3. [FALLBACK_KEYS] 通用兜底：新增工具忘了登记时，按常见目标键顺序探测，绝不再出现空目标；
 *  4. [toolTargetCoverageGaps]：把「schema 里有、但既没规则也没进白名单」的工具名暴露出来，供 debug 审计与单测断言。
 *
 * 约定：
 *  · 目标返回值**只有目标本身**（文件 / 目录 / 关键字 / URL / 命令…），不含工具名与状态词；
 *  · 状态词与工具中文名由 [toolStatusText] 拼接：`正在X：目标…` / `已X：目标` / `X失败：目标`；
 *  · 拿不到有意义的目标时返回空串，由调用方回退成纯工具名文案（绝不显示「：」后面跟着空气）；
 *  · 一切输出都会剔除 `\u0001` / `\u0002` 等协议控制字符；**状态行**目标压成单行（见 [statusLineOf]），
 *    明细页目标保留换行（代码类工具的多行预览）。
 */
internal data class ToolCallInfo(
    /** 状态行用目标：单行、≤[STATUS_TARGET_MAX] 字 */
    val target: String,
    /** 状态行展开用参数摘要：单行、≤[STATUS_ARGS_MAX] 字 */
    val argsSummary: String,
    /** 明细页用目标：**可多行**（代码类工具给前 [TARGET_PREVIEW_LINES] 条有效语句）、≤[DETAIL_TARGET_MAX] 字 */
    val fullTarget: String,
    /** 明细页展开用参数摘要：多行、≤[DETAIL_ARGS_MAX] 字 */
    val argsDetail: String,
    /**
     * 代码类工具的原始代码正文（其它工具为空串），明细页据此渲染「代码胶囊」并支持一键复制。
     * 对应参数键已从 [argsDetail] 中剔除，避免同一个东西显示两遍（一遍能看、一遍只剩字数）。
     */
    val code: String = ""
)

private const val STATUS_TARGET_MAX = 100
private const val DETAIL_TARGET_MAX = 300
private const val STATUS_ARGS_MAX = 160
private const val DETAIL_ARGS_MAX = 800
/** 摘要里单个值的展示上限：超过就只报字数，避免把整个文件内容抄进状态行 */
private const val DIGEST_VALUE_MAX = 120
/** 代码胶囊留存的正文上限：够放下正常脚本（上千行），又不至于把消息 JSON 撑爆 */
private const val CODE_MAX = 12000
/**
 * 明细目标里代码预览的最大行数（用户要求「应该多知道几行啊」）。
 * 3 行足够看出「这次调用在干什么」，又不把详情面板撑成代码编辑器 —— 全文有代码胶囊。
 */
private const val TARGET_PREVIEW_LINES = 3

/**
 * 「跑代码」类工具 → 正文所在的参数键。
 * 这些键的正文会单独取出给代码胶囊，并从参数摘要里剔除（否则重复显示，且摘要里只有字数）。
 */
private val CODE_KEYS: Map<String, List<String>> = mapOf(
    "execute_python" to listOf("code"),
    "serve_start" to listOf("script"),
    "shell_exec" to listOf("command")
)

// ═══════════════════════════════════════════════════════════════════════
// 公开入口
// ═══════════════════════════════════════════════════════════════════════

/**
 * 兼容旧签名：只取状态行目标（单行）。
 *
 * @param maxLen 截断上限（按 code point 计，不会切坏 emoji）
 */
internal fun toolActionTarget(tool: String, argsJson: String, maxLen: Int = STATUS_TARGET_MAX): String =
    clip(statusLineOf(targetOf(tool, Args(argsJson))), maxLen)

/**
 * 一次解析同时产出四份展示信息（状态行 / 状态行展开 / 明细页 / 明细页展开）。
 *
 * 性能：argsJson 只解析一次；只读白名单键，绝不读取 `content` / `code` 全文
 *（`write_file` 的 content 可能上百 KB，抄进状态行会直接卡住列表滚动）。
 */
internal fun toolCallInfo(tool: String, argsJson: String): ToolCallInfo {
    val a = Args(argsJson)
    val rawTarget = targetOf(tool, a)
    val codeKeys = CODE_KEYS[tool].orEmpty()
    val hideArgs = tool in NO_ARG_SUMMARY_TOOLS
    return ToolCallInfo(
        // 状态行永远单行：多行目标（代码预览）收敛成「首行（共 N 行）」
        target = clip(statusLineOf(rawTarget), STATUS_TARGET_MAX),
        // 摘要里只放「目标行之外」的关键参数；read_file 类的行段已进 target，展开区不再铺 key=value
        argsSummary = if (hideArgs) "" else argsDigest(tool, a, singleLine = true, maxLen = STATUS_ARGS_MAX, suppress = codeKeys),
        // 明细页保留换行：代码类工具能看到前 3 条有效语句，而不是一行 import
        fullTarget = clip(rawTarget, DETAIL_TARGET_MAX),
        argsDetail = if (hideArgs) "" else argsDigest(tool, a, singleLine = false, maxLen = DETAIL_ARGS_MAX, suppress = codeKeys),
        code = codeOf(tool, a)
    )
}

/**
 * 取出代码类工具的代码正文：保留换行与缩进（代码的可读性全靠缩进），去掉末尾空行，长度封顶 [CODE_MAX]。
 * 非代码类工具返回空串。
 */
private fun codeOf(tool: String, a: Args): String {
    val keys = CODE_KEYS[tool] ?: return ""
    val body = keys.firstNotNullOfOrNull { k -> a.raw(k).takeIf { it.isNotBlank() } } ?: return ""
    val normalized = body.replace("\r\n", "\n").trimEnd()
    return if (normalized.length > CODE_MAX) clip(normalized, CODE_MAX) else normalized
}

/**
 * 覆盖率审计：返回既没有目标规则、也不在「确实无目标」白名单里的工具名。
 *
 * 用途：debug 构建首次生成工具 schema 时打一条日志；单元测试里断言为空列表。
 * 这样新增工具却忘了配目标时，会立刻暴露，而不是等用户截图来投诉「又一个工具没显示具体文件」。
 */
internal fun toolTargetCoverageGaps(toolNames: Collection<String>): List<String> =
    toolNames.asSequence()
        .filter { it.isNotBlank() }
        .filter { it !in TARGET_RULES && it !in NO_TARGET_TOOLS }
        .distinct()
        .sorted()
        .toList()

// ═══════════════════════════════════════════════════════════════════════
// 规则表（覆盖 buildAIToolsSchema 里的全部工具）
// ═══════════════════════════════════════════════════════════════════════

private val TARGET_RULES: Map<String, (Args) -> String> = mutableMapOf<String, (Args) -> String>().apply {
    fun reg(rule: (Args) -> String, vararg names: String) {
        names.forEach { put(it, rule) }
    }

    // ── 工作区文件 / 目录 ──────────────────────────────────────────────
    reg({ a -> a.one("path").ifBlank { "工作区根目录" } }, "list_files")
    reg({ a -> a.one("path") }, "write_file", "edit_file", "append_file", "stat_file",
        "get_file", "delete_file", "create_folder", "delete_folder", "install_apk", "play_audio",
        "play_video")
    // read_file 类：路径后面跟行段（如 "a.kt L1-9" / "a.kt L120"），让状态行直接看到读取范围
    reg({ a -> readTarget(a) }, "read_file", "read_files", "read_text_file")
    reg({ a -> arrow(a.one("path"), a.one("backup")) }, "restore_file")
    reg({ a -> arrow(a.one("path"), a.one("new_name")) }, "rename_file")
    reg({ a -> arrow(a.one("source"), a.one("target")) }, "copy_file")
    reg({ a -> keywordScope(a.one("keyword"), a.one("path")) }, "find_files")
    reg({ a -> keywordScope(a.one("query"), a.one("path")) }, "search_in_files")

    // ── 压缩包 / 批量 ─────────────────────────────────────────────────
    reg({ a -> arrow(a.one("source"), a.one("target")) }, "extract_archive")
    reg({ a -> arrowList(a.list("sources"), a.one("target")) }, "compress_archive")
    reg({ a -> arrow(a.one("source"), a.one("target").ifBlank { "上一级目录" }) }, "move_dir_contents")
    reg({ a -> a.one("path") }, "archive_list")
    reg({ a -> joinPath(a.one("path"), a.one("entry")) }, "archive_read_file")
    reg({ a -> filesWithPattern(a.list("paths"), a.one("pattern")) }, "batch_replace")
    reg({ a -> filesWithCount(a.list("files")) }, "apply_patch")
    reg({ a -> diffPair(a.one("a"), a.one("b")) }, "diff_text")

    // ── 备份 ─────────────────────────────────────────────────────────
    reg({ a -> joinPath(a.one("backup"), a.one("path")) }, "read_backup_file")
    reg({ a -> a.one("backup") }, "restore_backup")

    // ── 网络 ─────────────────────────────────────────────────────────
    reg({ a -> a.one("url") }, "fetch_webpage")
    reg({ a -> listOf(a.one("method").ifBlank { "GET" }, a.one("url")).filter { it.isNotBlank() }.joinToString(" ") }, "http_request")
    reg({ a -> arrow(a.one("url"), a.one("dest")) }, "download_file")
    reg({ a -> arrow(a.one("path"), a.one("url")) }, "upload_file")
    reg({ a -> listOf(a.one("action"), listOf(a.one("host"), a.one("remote_path")).filter { it.isNotBlank() }.joinToString(":")).filter { it.isNotBlank() }.joinToString(" ") }, "ftp_transfer")
    reg({ a -> withPort(a.one("host"), a.one("port")) }, "ping_host")
    reg({ a -> a.one("host") }, "dns_lookup", "traceroute")
    reg({ a -> listOf(a.one("host"), a.one("ports")).filter { it.isNotBlank() }.joinToString(" ") }, "port_scan")
    reg({ a -> a.one("url").ifBlank { a.one("size_mb").let { if (it.isBlank()) "" else "$it MB 测速" } } }, "bandwidth_test")
    reg({ a -> a.one("query") }, "search_web", "deep_research")

    // ── 执行 / 逆向 ──────────────────────────────────────────────────
    reg({ a -> codeTarget(a.raw("command"), "命令") }, "shell_exec")
    reg({ a -> firstLine(a.raw("args")) }, "run_adb", "git_command")
    // Python 脚本的"目标"不是前几条语句（用户反馈那是噪音），只报规模；正文在代码胶囊里
    reg({ a -> codeSizeTarget(a.raw("code")) }, "execute_python")
    reg({ a -> a.one("path") }, "reverse_strings", "reverse_dex", "reverse_native", "reverse_disasm")
    reg({ a -> arrow(a.one("path").ifBlank { bulkHint(a.raw("content")) }, a.one("dest")) }, "crypto_encrypt", "crypto_decrypt")
    reg({ a -> listOf(a.one("action"), firstLine(a.raw("input"))).filter { it.isNotBlank() }.joinToString("：") }, "json_process")
    reg({ a -> a.one("pattern") }, "regex_test")
    reg({ a -> listOf(a.one("action"), clip(a.one("text"), 40)).filter { it.isNotBlank() }.joinToString("：") }, "encode_convert")
    reg({ a -> a.one("algorithm").ifBlank { "摘要" } }, "hash_digest")
    reg({ a -> a.one("value") }, "timestamp_convert", "size_convert")
    reg({ a -> bulkHint(a.raw("text")).ifBlank { "文本" } }, "text_stats")
    reg({ a -> a.one("path") }, "qr_decode")

    // ── 设备 / 系统 ──────────────────────────────────────────────────
    reg({ a -> withSuffix(a.one("level"), "%").ifBlank { a.one("stream") } }, "set_volume")
    reg({ a -> a.one("pattern").ifBlank { a.one("duration_ms").let { if (it.isBlank()) "" else "$it ms" } } }, "vibrate")
    // 闹钟：目标 = 响铃时刻（+ 重复/名称），状态行直接读得懂「07:30 工作日 吃药」
    reg({ a ->
        listOf(a.one("time"), a.one("days").ifBlank { a.one("repeat") }, a.one("label"))
            .filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { a.one("in_minutes").let { if (it.isBlank()) "" else "$it 分钟后" } }
    }, "set_alarm")
    reg({ a -> a.one("alarm_id").ifBlank { a.one("all").let { if (it.isBlank()) "" else "全部闹钟" } } }, "cancel_alarm")
    reg({ a -> withSuffix(a.one("brightness"), "%") }, "set_brightness")
    reg({ a -> a.one("enabled") }, "keep_screen_on")
    reg({ a -> a.one("mode") }, "set_screen_orientation", "exit_app")
    reg({ a -> a.one("on") }, "set_bluetooth", "set_gps", "set_wifi", "set_nfc", "set_flashlight")
    reg({ a -> a.one("target") }, "stop_app", "open_app", "uninstall_app")
    reg({ a -> a.one("pid") }, "kill_process")
    reg({ a -> a.one("tab") }, "switch_tab")
    reg({ a -> a.one("title") }, "send_notification")
    reg({ a -> "当前屏幕" }, "capture_screen")
    reg({ a -> a.one("text").ifBlank { a.one("mode") } }, "keep_alive")
    reg({ a -> waitTarget(a.one("seconds"), a.one("minutes")) }, "wait")
    reg({ a -> a.one("text").ifBlank { coords(a.one("x"), a.one("y")) } }, "phone_tap")
    reg({ a -> "(${a.one("from_x")},${a.one("from_y")}) → (${a.one("to_x")},${a.one("to_y")})" }, "phone_swipe")
    reg({ a -> a.one("text") }, "phone_type")
    reg({ a -> a.one("key") }, "phone_key")
    reg({ a -> "当前界面" }, "phone_screen")
    reg({ a -> "当前位置" }, "get_current_location")
    reg({ a -> "前置摄像头" }, "open_front_camera")
    reg({ a -> "后置摄像头" }, "open_rear_camera")
    reg({ a -> a.one("name").ifBlank { "当前屏幕" } }, "take_screenshot")

    // ── 本 App 业务 ──────────────────────────────────────────────────
    reg({ a -> listOf(a.one("target_type"), a.one("target_id")).filter { it.isNotBlank() }.joinToString(" ") + previewContent(a.one("content")) }, "send_message")
    reg({ a -> arrow(a.one("path"), a.one("target_id")) }, "send_file")
    reg({ a -> listOf(a.one("target_id"), a.one("keyword")).filter { it.isNotBlank() }.joinToString(" ") }, "list_recent_messages")
    reg({ a -> a.one("message_id").let { if (it.isBlank()) "" else "message_id=$it" } }, "recall_message")
    reg({ a -> a.one("user_id") }, "send_friend_request")
    reg({ a -> a.one("title") }, "create_community_post")
    reg({ a -> listOf(a.one("type"), clip(a.one("prompt"), 40)).filter { it.isNotBlank() }.joinToString("：") }, "generate_media")
    reg({ a -> listOf(a.one("tag"), codeTarget(a.raw("script"))).filter { it.isNotBlank() }.joinToString("：") }, "serve_start")
    reg({ a -> a.one("tag") }, "serve_stop", "serve_log")
    reg({ a -> a.one("name") }, "plugin_call", "plugin_submit")
    reg({ a -> a.one("keyword").ifBlank { a.one("detail") } }, "plugin_list")
    reg({ a -> a.one("tool").let { t -> if (t.isBlank()) "" else "$t × ${a.one("count").ifBlank { "N" }} 次" } }, "batch_loop")
    reg({ a -> "第 ${a.one("step_index").ifBlank { "?" }} 步 → ${a.one("status")}" }, "update_plan_step")
    reg({ a -> clip(a.one("task"), 60) }, "agent_plan", "agent_review")
    reg({ a -> a.list("tasks").let { l -> when { l.isEmpty() -> ""; else -> "${l.size} 个子任务：${clip(l.first(), 40)}" } } }, "agent_fork")
    reg({ a -> a.list("questions").let { l -> if (l.isEmpty()) "向用户提问" else "${l.size} 个问题：${clip(l.first(), 40)}" } }, "ask_user")
    reg({ a -> a.one("mode") }, "set_agent_mode")
    reg({ a -> a.one("mode") }, "work_log")
}.toMap()

/**
 * 确实没有「对象」可显示的工具（查状态 / 列清单 / 无参动作）：
 * 状态行保持「正在X…」即可，硬塞目标反而是噪音。
 *
 * 注意：这里必须显式登记，靠 [toolTargetCoverageGaps] 保证不会有工具「既没规则也没登记」。
 */
private val NO_TARGET_TOOLS: Set<String> = setOf(
    "serve_list",
    "list_backups", "query_environment", "shutdown_device", "reboot_device",
    "list_my_contacts", "list_my_groups", "phone_access", "process_list",
    "battery_manage", "list_alarms"
)

/**
 * 通用兜底：新工具没登记规则时，按这些常见「目标键」顺序探测。
 * 值必须是「对象」而不是布尔/数量，否则宁可不显示。
 */
private val FALLBACK_KEYS = listOf(
    "path", "file", "files", "source", "sources", "target", "dest", "script",
    "url", "query", "keyword", "command", "args", "tool", "name", "title",
    "tag", "host", "pid", "message_id", "user_id", "target_id", "text", "prompt"
)

/** 摘要白名单：按此顺序输出，避免 JSON 键顺序随机导致同一次调用两次显示不一样 */
private val DIGEST_KEYS = listOf(
    "path", "paths", "files", "sources", "source", "target", "dest", "script",
    "url", "method", "query", "keyword", "glob", "command", "args", "tool",
    "count", "start", "index", "step_index", "status", "mode", "tag", "host",
    "port", "entry", "offset", "limit", "start_line", "end_line", "max_lines", "level",
    "brightness", "volume", "pattern",
    "duration_ms", "seconds", "minutes", "text", "prompt", "title", "type",
    "ratio", "size", "target_type", "target_id", "user_id", "message_id",
    "backup", "name", "deliver", "on", "enabled", "action", "algorithm",
    "direction", "value", "key", "password", "headers", "old_string", "new_string",
    "content", "code", "patch", "pluginJson", "params"
)

/** 内容型大字段：只报字数/行数，绝不把正文抄进状态行 */
private val BULK_KEYS = setOf("content", "body", "code", "patch", "pluginjson", "old_string", "new_string")

/**
 * 摘要区不展示 key=value 参数的工具：全部关键信息已收敛进 target（如 read_file 的「文件名 L1-9」），
 * 再在展开区铺一版「path=… · start_line=… · end_line=…」就是纯噪音（用户明确反感）。
 * 目标行之外的参数细节依旧保留给其余工具。
 */
private val NO_ARG_SUMMARY_TOOLS: Set<String> = setOf(
    "read_file", "read_files", "read_text_file", "archive_read_file"
)

private fun targetOf(tool: String, a: Args): String {
    val raw = TARGET_RULES[tool]?.invoke(a) ?: fallbackTarget(a)
    return cleanTarget(raw)
}

private fun fallbackTarget(a: Args): String {
    for (k in FALLBACK_KEYS) {
        val v = a.one(k)
        if (v.isNotBlank()) return if (k == "path" || k == "file") v else "$k=$v"
    }
    return ""
}

// ═══════════════════════════════════════════════════════════════════════
// 参数摘要
// ═══════════════════════════════════════════════════════════════════════

private fun argsDigest(
    tool: String,
    a: Args,
    singleLine: Boolean,
    maxLen: Int,
    suppress: List<String> = emptyList()
): String {
    if (a.isBlank()) return ""
    val parts = ArrayList<String>(DIGEST_KEYS.size)
    for (k in DIGEST_KEYS) {
        if (!a.has(k)) continue
        // 代码正文单独走「代码胶囊」，摘要里不再重复报字数
        if (suppress.any { it.equals(k, ignoreCase = true) }) continue
        val raw = a.raw(k)
        if (raw.isBlank()) continue
        val shown = when {
            isSecretKey(k) -> "***"
            k.lowercase() == "headers" -> "（已隐藏）"
            k.lowercase() in BULK_KEYS -> bulkHint(raw)
            k == "password" -> "***"
            else -> {
                val flat = oneLine(raw)
                if (flat.length > DIGEST_VALUE_MAX) bulkHint(raw) else flat
            }
        }
        if (shown.isNotBlank()) parts.add("$k=$shown")
    }
    if (parts.isEmpty()) return ""
    val joined = if (singleLine) parts.joinToString(" · ") else parts.joinToString("\n")
    return clip(joined, maxLen)
}

private fun isSecretKey(key: String): Boolean {
    val k = key.lowercase()
    return k == "key" || k.contains("password") || k.contains("passwd") || k.contains("token") ||
        k.contains("secret") || k.contains("apikey") || k.contains("api_key") ||
        k.contains("access_key") || k.contains("authorization") || k.contains("cookie") ||
        k.contains("credential")
}

private fun bulkHint(raw: String): String {
    val flat = raw.replace("\r\n", "\n")
    val lines = flat.count { it == '\n' } + 1
    val chars = flat.length
    return if (lines > 1) "（$chars 字 / $lines 行）" else "（$chars 字）"
}

// ═══════════════════════════════════════════════════════════════════════
// 拼装辅助
// ═══════════════════════════════════════════════════════════════════════

private fun arrow(a: String, b: String): String = when {
    a.isBlank() && b.isBlank() -> ""
    a.isBlank() -> b
    b.isBlank() -> a
    else -> "$a → $b"
}

private fun arrowList(list: List<String>, target: String): String {
    val head = when {
        list.isEmpty() -> ""
        list.size == 1 -> list.first()
        else -> "${list.first()} 等 ${list.size} 项"
    }
    return arrow(head, target)
}

private fun filesWithCount(list: List<String>): String = when {
    list.isEmpty() -> ""
    list.size == 1 -> list.first()
    else -> "${list.first()} 等 ${list.size} 个文件"
}

private fun filesWithPattern(list: List<String>, pattern: String): String {
    val head = filesWithCount(list)
    if (pattern.isBlank()) return head
    return if (head.isBlank()) "替换 \"$pattern\"" else "$head：替换 \"$pattern\""
}

private fun keywordScope(keyword: String, scope: String): String {
    if (keyword.isBlank()) return ""
    val s = scope.trim()
    return if (s.isBlank() || s == "." || s == "/") keyword else "$keyword（在 $s 中）"
}

private fun joinPath(a: String, b: String): String = when {
    a.isBlank() -> b
    b.isBlank() -> a
    else -> "$a/$b"
}

private fun withPort(host: String, port: String): String = when {
    host.isBlank() -> ""
    port.isBlank() -> host
    else -> "$host:$port"
}

private fun withSuffix(v: String, suffix: String): String = if (v.isBlank()) "" else v + suffix

private fun coords(x: String, y: String): String = if (x.isBlank() && y.isBlank()) "" else "($x, $y)"

private fun waitTarget(seconds: String, minutes: String): String = when {
    seconds.isNotBlank() -> "$seconds 秒"
    minutes.isNotBlank() -> "$minutes 分钟"
    else -> ""
}

private fun previewContent(content: String): String {
    val t = oneLine(content)
    return if (t.isBlank()) "" else "：${clip(t, 24)}"
}

private fun diffPair(a: String, b: String): String {
    val left = firstLine(a)
    val right = firstLine(b)
    return when {
        left.isBlank() && right.isBlank() -> ""
        right.isBlank() -> left
        left.isBlank() -> right
        else -> "${clip(left, 20)} ↔ ${clip(right, 20)}"
    }
}

private fun firstLine(raw: String): String =
    raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/**
 * read_file 类工具的目标：路径 + 行段后缀（如 "a.kt L1-9" / "a.kt L120"）。
 * 行段解析优先级与 ReadFileChip 保持一致：start_line/end_line > offset+limit > max_lines。
 * 拿不到行段时退化为纯路径，不硬凑。
 */
private fun readTarget(a: Args): String {
    val path = a.one("path")
    if (path.isBlank()) return ""
    val range = readRangeSuffix(a)
    return if (range.isBlank()) path else "$path $range"
}

private fun readRangeSuffix(a: Args): String {
    val start = firstNum(a, "start_line", "line_start", "startline", "from_line", "from", "start")
    val end = firstNum(a, "end_line", "line_end", "endline", "to_line", "to", "end")
    if (start != null || end != null) {
        val s = start ?: end!!
        val e = end ?: s
        return if (e == s) "L$s" else "L$s-$e"
    }
    val offset = firstNum(a, "offset", "start_offset")
    val limit = firstNum(a, "limit", "max_lines", "lines", "count")
    if (offset != null && limit != null && limit > 0) {
        // offset 按 0 基行偏移理解（与 ReadFileChip 换算一致）：offset=0 从第 1 行开始
        val s = if (offset <= 0) 1 else offset + 1
        val e = s + limit - 1
        return if (e == s) "L$s" else "L$s-$e"
    }
    if (limit != null && limit > 0) {
        val e = limit
        return if (e == 1) "L1" else "L1-$e"
    }
    return ""
}

/** 从多个候选键里取第一个整数（容忍 "第120行" / "L120" 之类带前缀的写法） */
private fun firstNum(a: Args, vararg keys: String): Int? {
    for (k in keys) {
        val v = a.raw(k).trim()
        if (v.isEmpty()) continue
        Regex("""\d{1,7}""").find(v)?.value?.toIntOrNull()?.let { return it }
    }
    return null
}

/**
 * 代码 / 命令类工具的「目标」：前 [TARGET_PREVIEW_LINES] 条**有效语句**（跳过空行 / 注释 / import），
 * 还有更多行时末尾附「（共 N 行）」；状态行的单行版由 [statusLineOf] 收敛成「首行（共 N 行）」。
 *
 * 用户实测（截图）：跑 Python 脚本时，详情面板的「目标」只显示 `import os` ——
 * 那是脚本的导入行，作为「这次调用在干什么」的说明毫无信息量（原话：「这有什么屁用吗」）。
 * 只给第一条有效语句也不够：用户后续反馈「万一代码量多，就知道一行有什么作用呢？应该多知道几行啊」，
 * 因此明细目标直接给到 3 行，再多的靠「（共 N 行）」与详情面板里的代码胶囊兜底。
 * 整段全是 import / 注释的罕见脚本退化成「N 行代码」。
 *
 * @param noun 退化文案里的量词（代码 / 命令）
 */
private fun codeTarget(raw: String, noun: String = "代码"): String {
    if (raw.isBlank()) return ""
    val total = raw.lines().count { it.isNotBlank() }
    val stmts = raw.lineSequence()
        .map { it.trim() }
        .filter { t ->
            t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("//") &&
                !t.startsWith("import ") && !t.startsWith("from ")
        }
        .take(TARGET_PREVIEW_LINES)
        .toList()
    return when {
        stmts.isNotEmpty() ->
            if (total > stmts.size) stmts.joinToString("\n") + "\n（共 $total 行）"
            else stmts.joinToString("\n")
        total > 0 -> "$total 行$noun"
        else -> ""
    }
}

/** 只报脚本规模（如「共 24 行代码」），不摘语句：用户点开卡片看的是完整脚本 + 输出 */
private fun codeSizeTarget(raw: String, noun: String = "行代码"): String {
    val total = raw.lines().count { it.isNotBlank() }
    return if (total > 0) "共 $total $noun" else ""
}

// ═══════════════════════════════════════════════════════════════════════
// 执行结果 / 变更预览（内联卡片的「输出」「变更」块）
// ═══════════════════════════════════════════════════════════════════════

/** 结果 JSON 里带 stdout / stderr / error 的工具；其余工具不渲染输出块 */
private val OUTPUT_TOOLS = setOf("execute_python", "shell_exec", "serve_start", "run_script")
/** 能用参数直接算出变更预览的工具；其余工具不渲染变更块 */
private val DIFF_TOOLS = setOf("edit_file", "write_file", "append_file", "apply_patch")
/** 输出块字符上限：够看清结果与报错，又不把消息 JSON 撑大 */
private const val OUTPUT_MAX = 2000
/** 变更预览字符 / 行数上限（避免整文件内容进消息） */
private const val DIFF_MAX = 1500
private const val DIFF_MAX_LINES = 40

/**
 * 从工具返回的 JSON 里提取可展示的执行结果（stdout / stderr / 失败原因）。
 *
 * 以前这份数据只被 AI 内部读取后丢弃，用户点开卡片只能看到参数属性；
 * 现在随工具记录落盘，卡片里直接显示「这次到底跑出了什么」，失败时也能看到真实报错。
 * 非执行类工具、空结果统一返回空串（调用方据此不渲染输出块）。
 */
internal fun toolOutputOf(tool: String, resultJson: String): String {
    if (tool !in OUTPUT_TOOLS || resultJson.isBlank()) return ""
    val o = runCatching { JSONObject(resultJson) }.getOrNull() ?: return ""
    val ok = o.optBoolean("ok", true)
    // execute_python / shell_exec 用 stdout；serve_start 用 stdout_tail；serve_log 用 log_tail
    val out = listOf("stdout", "stdout_tail", "log_tail")
        .map { o.optString(it, "") }
        .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val err = o.optString("stderr", "").trim()
    val errorText = if (ok) "" else o.optString("error", "").trim()
    val sb = StringBuilder()
    if (out.isNotEmpty()) sb.append(out)
    // 失败时 error 文案里通常已经拼过 stderr，避免同一段报错显示两遍
    if (err.isNotEmpty() && !errorText.contains(err)) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(err)
    }
    if (sb.isEmpty() && errorText.isNotEmpty()) sb.append(errorText)
    if (sb.isEmpty()) sb.append(if (ok) "（无输出）" else "执行失败")
    return clip(sb.toString(), OUTPUT_MAX)
}

/**
 * 用工具参数直接生成变更预览（`- 旧行` / `+ 新行`），不需要读取整个文件：
 *  · edit_file：old_string → new_string（只覆盖被替换的那一段，因此不保证真实行号）；
 *  · write_file / append_file：content 全部按新增行展示；
 *  · apply_patch：patch 本身已是 diff，原样保留 +/-/@@ 行。
 * 其余工具返回空串（调用方据此不渲染变更块）。
 */
internal fun toolDiffOf(tool: String, argsJson: String): String {
    if (tool !in DIFF_TOOLS || argsJson.isBlank()) return ""
    val a = runCatching { Args(argsJson) }.getOrNull() ?: return ""
    val lines: List<String> = when (tool) {
        "edit_file" -> {
            val half = DIFF_MAX_LINES / 2
            val removed = markLines(a.raw("old_string"), "- ", half)
            val added = markLines(a.raw("new_string"), "+ ", half)
            removed + added
        }
        "write_file", "append_file" -> markLines(a.raw("content"), "+ ", DIFF_MAX_LINES)
        "apply_patch" -> a.raw("patch").replace("\r\n", "\n").lines()
            .take(DIFF_MAX_LINES)
            .map { it.trimEnd() }
        else -> emptyList()
    }
    val body = lines.filter { it.isNotEmpty() }.joinToString("\n")
    return if (body.isBlank()) "" else clip(body, DIFF_MAX)
}

/** 给每行加前缀（`- ` / `+ `），超过 [maxLines] 时截断并标注 */
private fun markLines(text: String, mark: String, maxLines: Int): List<String> {
    val raw = text.replace("\r\n", "\n").lines()
    if (raw.isEmpty() || text.isBlank()) return emptyList()
    val kept = raw.take(maxLines).map { mark + it }
    return if (raw.size > maxLines) kept + "…（已截断）" else kept
}

// ═══════════════════════════════════════════════════════════════════════
// 清洗 / 截断
// ═══════════════════════════════════════════════════════════════════════

/**
 * 目标清洗：协议控制字符（含 `\u0001` / `\u0002`）压掉，**换行保留** ——
 * 代码类工具的明细目标本来就是多行预览（见 [codeTarget]），折行交给 UI；
 * 每行再去首尾空白、把连续水平空白压成一个空格，防止超长缩进把面板撑宽。
 */
private fun cleanTarget(v: String): String = v
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
    .split('\n')
    .joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
    .trim()

/** 「（共 N 行）」尾标记：多行目标（代码预览）截断时自带的真实总行数，状态行要把它挪到首行末尾 */
private val TRAILING_COUNT_RE = Regex("（共 (\\d+) 行）\\s*$")

/**
 * 多行目标 → 状态行单行版：取第一条非空行，末尾补「（共 N 行）」。
 *
 * N 的取值顺序：
 *  ① 目标自带的「（共 N 行）」尾标记 —— 代码预览只截 3 行，真实总行数只有它知道；
 *  ② 退化为非空行数（如 `ls -la\necho hi` → 「ls -la（共 2 行）」，与旧行为一致）。
 *
 * 单行目标原样返回：旧消息与绝大多数工具的目标本就是单行，显示完全不变。
 */
internal fun statusLineOf(raw: String): String {
    if (raw.isEmpty()) return ""
    if (!raw.contains('\n')) return raw.trim()
    val lines = raw.lines().filter { it.isNotBlank() }
    val head = lines.firstOrNull()?.trim().orEmpty()
    if (head.isEmpty()) return ""
    val marked = TRAILING_COUNT_RE.find(lines.last().trim())
    val total = marked?.groupValues?.get(1)?.toIntOrNull() ?: lines.size
    return "$head（共 $total 行）"
}

private fun oneLine(v: String): String = v
    .replace("\r\n", " ")
    .replace('\r', ' ')
    .replace('\n', ' ')
    .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

/** 按 code point 截断，避免把 emoji / 生僻字切一半变成乱码 */
internal fun clip(v: String, max: Int): String {
    if (max <= 0 || v.isEmpty()) return ""
    val count = v.codePointCount(0, v.length)
    if (count <= max) return v
    val end = v.offsetByCodePoints(0, (max - 1).coerceAtLeast(0))
    return v.substring(0, end) + "…"
}

// ═══════════════════════════════════════════════════════════════════════
// 子 AI 工具轨迹
// ═══════════════════════════════════════════════════════════════════════

/**
 * 把子 AI（验收 / 规划 / 并行子执行）内部的工具调用转成「可读的轨迹行」。
 *
 * 用户要求「一个不漏」：子 AI 内部的工具调用以前完全不产生任何可见记录，
 * 用户只能看到一句「验收 AI 接手中…」。这里按发生顺序生成
 * `已读取文件：xxx.kt` 这样的具体行，随报告块一起持久化到消息正文。
 *
 * @param max 最多展示多少条（超出折叠成「另有 N 次调用」，防止消息被刷屏）
 */
internal fun agentToolTraceLines(
    records: List<ChatMsg.ToolStageRecord>,
    max: Int = 12
): List<String> {
    if (records.isEmpty()) return emptyList()
    val shown = records.take(max).map { r ->
        buildString {
            // 轨迹行是「一行一条」的纯文本：多行目标（代码预览）必须先收敛成单行，否则一条记录会占好几行
            append(toolStatusText(r.tool, r.state, statusLineOf(r.target)))
            if (r.reason.isNotBlank()) append("（").append(clip(r.reason, 40)).append("）")
        }
    }.toMutableList()
    if (records.size > max) shown.add("另有 ${records.size - max} 次工具调用（详见「调用 N 个工具」）")
    return shown
}

// ═══════════════════════════════════════════════════════════════════════
// 参数访问器
// ═══════════════════════════════════════════════════════════════════════

/** argsJson 的轻量只读视图：容错解析、统一清洗、数组安全展开 */
private class Args(json: String) {
    val obj: JSONObject = try {
        if (json.isBlank()) JSONObject() else JSONObject(json)
    } catch (_: Throwable) {
        JSONObject()
    }

    fun isBlank(): Boolean = obj.length() == 0

    fun has(key: String): Boolean = obj.has(key) && !obj.isNull(key)

    /** 单行值（数组用「, 」连接并注明总数） */
    fun one(key: String): String = oneLine(raw(key))

    /** 原始值（保留换行，仅去控制字符），供 code / patch 这类需要行数的场景 */
    fun raw(key: String): String {
        if (!has(key)) return ""
        val v = obj.opt(key) ?: return ""
        return when (v) {
            is JSONArray -> (0 until v.length())
                .mapNotNull { i -> v.opt(i)?.toString()?.takeIf { it.isNotBlank() && it != "null" } }
                .joinToString(", ")
            is JSONObject -> v.toString()
            else -> v.toString()
        }.replace(Regex("[\\u0001\\u0002]"), " ")
    }

    /** 字符串数组（files / paths / sources / tasks …）；元素本身是对象时退化为 JSON 文本 */
    fun list(key: String): List<String> {
        if (!has(key)) return emptyList()
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val e = arr.opt(i)) {
                null, JSONObject.NULL -> null
                is String -> e.trim().takeIf { it.isNotEmpty() }
                is JSONObject -> e.optString("question", "")
                    .ifBlank { e.optString("task", "") }
                    .ifBlank { e.toString() }
                    .trim().takeIf { it.isNotEmpty() }
                else -> e.toString().trim().takeIf { it.isNotEmpty() }
            }
        }
    }
}
