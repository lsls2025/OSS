package com.aurora.chat.ui.chat

import android.content.Context
import com.aurora.chat.ModelLogger
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * AI 任务的**追加式执行日志**（方案 B）。
 *
 * ## 它解决什么
 * 工具调用明细与文件改动记录原先**只活在内存**，任务收尾时才挂到消息上（`aiToolStages` / `aiFileChanges`）。
 * 于是只要进程被 SIGKILL（Android Studio 停止运行、后台被清理、被强行停止、ANR），
 * `finally` 一律不执行 —— 消息里那两个字段永远是空，用户完全无法追溯"那段时间调过的工具和改过的文件"。
 *
 * ## 为什么追加式能扛住 SIGKILL
 * SIGKILL 只杀进程，**杀不掉已经交给内核的数据**。只要把每条记录 `write` + `flush` 进文件，
 * 数据就落在内核页缓存里，进程死了文件内容照样在。所以这里每条记录写完立即 flush，
 * 全程不依赖任何 `finally`、不依赖协程收尾、不依赖应用还活着。
 *
 * ## 生命周期
 * - `bind`  任务开始：新建 `task_<ts>_<conv>.jsonl`，写一条 `begin`
 * - `tool`  每次工具调用完成：追加一条 `tool`
 * - `change` 每次文件改动：追加一条 `change`
 * - `finish` 正常收尾：写 `end` → 关闭 → **删除文件**（正常结束不留垃圾）
 * - 进程被强杀：`finish` 不会执行 → 文件留着且没有 `end` → 下次启动 [recoverAll] 判定为"被中断的任务"
 *
 * ## 边界（不吹牛）
 * - 如果 Android Studio 执行的是**卸载重装 / Clear app data**，`filesDir` 整个被清，任何应用内方案都留不下东西。
 *   只有"杀进程后重启"（AS 的常规 Run）才在覆盖范围内。
 * - 日志有容量上限（见 [MAX_BYTES] / [MAX_LINES]），超限后写入 `truncated` 标记并停止追加；
 *   此时"发生过什么"仍可追溯，只是最末一小段细节会缺。
 * - `change` 记录里带 oldText/newText（与消息里 `ai_file_changes` 同一格式），因此恢复后**退回功能照样可用**；
 *   代价是大文件改动会占体积，受上限保护。
 */
object AIJournal {

    private const val DIR = "agent_journal"
    /** 单个任务的日志体积上限：超过后停止追加（只写一条 truncated 标记） */
    private const val MAX_BYTES = 12L * 1024 * 1024
    /** 单个任务的记录条数上限 */
    private const val MAX_LINES = 6000
    /** 残留日志保留天数：超过则清理（避免长期堆积） */
    private const val KEEP_DAYS = 3L

    /** 写入锁：agent_fork 的多个子执行者会并发调用 [change] / [tool] */
    private val writeLock = Any()

    @Volatile private var activeFile: File? = null
    private var writer: OutputStreamWriter? = null
    private var lineCount = 0
    private var byteCount = 0L
    private var truncated = false
    /** 绑定时的 applicationContext：文件改动漏斗 [AiChatManager.recordFileChange] 拿不到 ctx，靠它补齐 */
    @Volatile private var appCtx: Context? = null

    // ===== 任务生命周期 =====

    /**
     * 开始记录一个新任务。
     *
     * 用**每个任务一个文件**而不是复写同一个 `current.jsonl`：
     * 若上次是崩溃留下的残留，用户重进后很可能立刻发起新任务；单文件方案会在 bind 时把残留覆盖掉，
     * 等于把要恢复的东西自己删了。一任务一文件则天然并存，恢复只认"没有 end 的那些"。
     */
    fun bind(ctx: Context, userId: Long, convKey: String, msgId: Long, desc: String) {
        synchronized(writeLock) {
            closeQuietly()
            appCtx = ctx.applicationContext
            try {
                val dir = dir(ctx).apply { mkdirs() }
                cleanupOld(dir)
                val safeConv = convKey.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "ai" }
                val f = File(dir, "task_${System.currentTimeMillis()}_$safeConv.jsonl")
                activeFile = f
                writer = OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8)
                lineCount = 0
                byteCount = 0L
                truncated = false
                appendLocked(
                    JSONObject()
                        .put("k", "begin")
                        .put("ts", System.currentTimeMillis())
                        .put("uid", userId)
                        .put("conv", convKey)
                        .put("msg", msgId)
                        .put("desc", desc.take(120))
                )
            } catch (e: Throwable) {
                // 日志是"尽力而为"的旁路，绝不能因为它失败影响任务本身
                activeFile = null
                writer = null
                ModelLogger.warn(ctx, "JOURNAL", "执行日志创建失败：${e.message}")
            }
        }
    }

    /** 追加一条工具调用记录（每次调用完成后调用） */
    fun tool(rec: ChatMsg.ToolStageRecord) {
        if (activeFile == null) return
        append(
            appCtx,
            JSONObject()
                .put("k", "tool")
                .put("ts", rec.ts)
                .put("tool", rec.tool)
                .put("state", rec.state)
                .put("reason", rec.reason)
                .put("target", rec.target)
                .put("args", rec.argsSummary)
                .put("code", rec.code)
                .put("output", rec.output)
                .put("diff", rec.diff)
        )
    }

    /**
     * 追加一条文件改动记录。
     *
     * 调用点是 [AiChatManager.recordFileChange] —— 那是**所有文件类工具的唯一写入漏斗**
     * （write/edit/append/delete/rename/copy/apply_patch/batch_replace/restore/解压/压缩/搬移…），
     * 所以只在这里挂一次，就能覆盖全部工具，不用逐个工具去打点、也不会漏。
     */
    fun change(c: AiChatManager.FileChange) {
        if (activeFile == null) return
        append(
            appCtx,
            JSONObject().put("k", "change").put("ts", System.currentTimeMillis()).put("c", c.toJson())
        )
    }

    /**
     * 正常收尾：写 `end` 并删除日志文件。
     *
     * 只要这里被执行到，就说明任务**跑完了自己的收尾路径**（正常完成 / 出错退出 / 用户手动停止都算），
     * 消息里已经带上完整记录 —— 日志没有留存价值，删掉即可，不会堆垃圾。
     */
    fun finish(ctx: Context) {
        synchronized(writeLock) {
            val f = activeFile
            try {
                if (f != null && writer != null) {
                    appendLocked(JSONObject().put("k", "end").put("ts", System.currentTimeMillis()))
                }
            } catch (_: Throwable) {
                // end 写不进去就当作崩溃处理：留着让下次恢复，比悄悄丢掉强
            }
            closeQuietly()
            try {
                if (f != null) f.delete()
            } catch (_: Throwable) {
            }
            activeFile = null
        }
    }

    // ===== 内部写入 =====

    private fun append(ctx: Context?, obj: JSONObject) {
        synchronized(writeLock) {
            if (activeFile == null) return
            if (truncated) return
            if (lineCount >= MAX_LINES || byteCount >= MAX_BYTES) {
                truncated = true
                try {
                    appendLocked(JSONObject().put("k", "truncated").put("ts", System.currentTimeMillis()).put("lines", lineCount))
                } catch (_: Throwable) {
                }
                ctx?.let {
                    ModelLogger.warn(it, "JOURNAL", "执行日志已达上限（行 $lineCount / 字节 $byteCount），停止追加")
                }
                return
            }
            appendLocked(obj)
        }
    }

    /** 必须在 [writeLock] 内调用。写完立即 flush —— 这是扛住 SIGKILL 的关键。 */
    private fun appendLocked(obj: JSONObject) {
        val w = writer ?: return
        val line = obj.toString()
        w.write(line)
        w.write("\n")
        w.flush()
        lineCount++
        byteCount += line.length + 1L
    }

    private fun closeQuietly() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
    }

    private fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR)

    private fun cleanupOld(d: File) {
        try {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L
            d.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() }
            }
        } catch (_: Throwable) {
        }
    }

    // ===== 崩溃恢复 =====

    /** 一个"没跑到 finish 的任务"在日志里的全部可恢复内容 */
    class Recovered internal constructor(
        internal val file: File,
        val userId: Long,
        val convKey: String,
        val msgId: Long,
        val desc: String,
        val startedAt: Long,
        val tools: List<ChatMsg.ToolStageRecord>,
        val changes: List<AiChatManager.FileChange>,
        val truncated: Boolean,
        val hasEnd: Boolean
    )

    /**
     * 扫描残留日志。
     *
     * 判定依据只有一条：**正常收尾会把文件删掉**，所以"文件还在"就等于"上次没跑到收尾"。
     * 这比只看 [com.aurora.chat.AITaskGuard] 的标记更可靠 ——
     * 那个标记在应用数据被部分清理、或用户手动 forceReset 之后就不可信了；文件才是事实。
     */
    fun pending(ctx: Context): List<Recovered> {
        val out = mutableListOf<Recovered>()
        val files = try {
            dir(ctx).listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") } ?: return out
        } catch (_: Throwable) {
            return out
        }
        for (f in files) {
            try {
                val r = parseJournalFile(f)
                if (r == null) {
                    // 没有任何归属信息（begin 都没写进去）→ 无法折回任何消息，直接当垃圾清掉
                    runCatching { f.delete() }
                } else {
                    out.add(r)
                }
            } catch (e: Throwable) {
                ModelLogger.warn(ctx, "JOURNAL", "读取执行日志失败(${f.name})：${e.message}")
            }
        }
        return out
    }

    /**
     * 解析单个日志文件。**纯函数、不碰 Context**，便于纯 JVM 单测覆盖行格式的往返一致性。
     *
     * @return null 表示这份日志没有有效的归属信息（`begin` 缺失或 msgId 无效），无从折回
     */
    internal fun parseJournalFile(f: File): Recovered? {
        var userId = 0L
        var convKey = ""
        var msgId = 0L
        var desc = ""
        var startedAt = 0L
        var hasEnd = false
        var truncated = false
        val tools = mutableListOf<ChatMsg.ToolStageRecord>()
        val changes = mutableListOf<AiChatManager.FileChange>()
        f.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val o = try { JSONObject(line) } catch (_: Throwable) { return@forEachLine }
            when (o.optString("k")) {
                "begin" -> {
                    userId = o.optLong("uid", 0L)
                    convKey = o.optString("conv", "")
                    msgId = o.optLong("msg", 0L)
                    desc = o.optString("desc", "")
                    startedAt = o.optLong("ts", 0L)
                }
                "tool" -> tools.add(
                    ChatMsg.ToolStageRecord(
                        tool = o.optString("tool", ""),
                        state = o.optInt("state", 2),
                        ts = o.optLong("ts", 0L),
                        reason = o.optString("reason", ""),
                        target = o.optString("target", ""),
                        argsSummary = o.optString("args", ""),
                        code = o.optString("code", ""),
                        output = o.optString("output", ""),
                        diff = o.optString("diff", "")
                    )
                )
                "change" -> o.optJSONObject("c")?.let {
                    runCatching { AiChatManager.FileChange.fromJson(it) }.getOrNull()?.let { c -> changes.add(c) }
                }
                "truncated" -> truncated = true
                "end" -> hasEnd = true
            }
        }
        if (convKey.isBlank() || msgId <= 0L) return null
        return Recovered(f, userId, convKey, msgId, desc, startedAt, tools, changes, truncated, hasEnd)
    }

    /**
     * 把残留日志折回消息，让用户下次打开就能看到中断前调过哪些工具、改过哪些文件。
     *
     * 折叠内容：`aiToolStages` / `aiToolCallCount` / `aiFileChanges`，并把消息标成
     * `manualStopped = true` —— 这一条同时起到两个作用：
     *  ① 让「查看所有改动」「调用 N 个工具」两个入口渲染出来（它们受 isFinalDelivery 门控，
     *     而崩溃的消息 totalSeconds/thinkingSeconds 都是 0，不标记的话数据在也点不进去）；
     *  ② 语义上如实表达"这条没有正常收尾"。
     *
     * @return 成功恢复的任务数
     */
    suspend fun recoverAll(ctx: Context): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val items = pending(ctx)
        if (items.isEmpty()) return@withContext 0
        var restored = 0
        for (r in items) {
            try {
                // 同一条消息可能跨多次任务（如「继续处理任务」），先取已有记录做合并去重
                var existingTools = emptyList<ChatMsg.ToolStageRecord>()
                var existingChanges = emptyList<AiChatManager.FileChange>()
                val arr = loadLocalChatMessages(ctx, r.userId, r.convKey)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optLong("id", 0L) != r.msgId) continue
                        existingTools = parseToolStages(o)
                        existingChanges = AiChatManager.parseFileChanges(o.optString("ai_file_changes", ""))
                        break
                    }
                }
                val tools = (existingTools + r.tools)
                    .distinctBy { (it.ts / 1000L) to (it.tool + "\u0001" + it.target) }
                // 【第二道恢复】日志只能记到「经过工具」的改动；AI 用 shell_exec / execute_python
                // 直接在脚本里写文件时根本不经过 recordFileChange，日志里一条都没有。
                // 这类改动只有任务收尾时的内存快照 diff 能抓到，而进程被杀时那次 diff 根本不会执行。
                // 所以这里补一次「按磁盘事实反推」：任务开始时刻之后被修改过、且日志里没有的文件。
                val untracked = recoverUntrackedChanges(ctx, r)
                val changes = (existingChanges + r.changes + untracked)
                    .distinctBy { it.path + "\u0001" + it.op + "\u0001" + it.fromPath }
                patchAiReplyInFile(
                    ctx = ctx, userId = r.userId, key = r.convKey, msgId = r.msgId,
                    manualStopped = true,
                    fileChangesJson = AiChatManager.serializeFileChanges(changes),
                    toolCallCount = tools.size,
                    toolStages = tools
                )
                ModelLogger.info(
                    ctx, "JOURNAL",
                    "已恢复中断任务的执行记录 | 会话=${r.convKey} 消息=${r.msgId} " +
                        "工具=${tools.size} 改动=${changes.size}(日志 ${r.changes.size} + 反推 ${untracked.size}) 截断=${r.truncated}"
                )
                // 恢复成功才删日志；失败的留着，下次启动还能再试一次
                runCatching { r.file.delete() }
                restored++
            } catch (e: Throwable) {
                ModelLogger.warn(ctx, "JOURNAL", "恢复执行记录失败(${r.file.name})：${e.message}")
            }
        }
        if (restored > 0) com.aurora.chat.ui.chat.LocalChatRefreshTick.bump()
        restored
    }

    // ===== 磁盘反推（补日志覆盖不到的改动）=====

    /** 反推时单个文件读取正文的上限：超过只记元数据，避免一次恢复吃掉大量内存 */
    private const val RECOVER_TEXT_MAX = 64L * 1024
    /** 一次恢复最多反推多少条，防止脚本批量改写时列表爆炸 */
    private const val RECOVER_MAX_ITEMS = 60
    /** 时间容差：吸收文件系统 mtime 精度与 `bind()` 到首次写入之间的时间差 */
    private const val RECOVER_MTIME_SLACK_MS = 2_000L

    /** 反推时跳过的二进制/媒体扩展名（这些文件没有可读 diff，也算不动"改了什么"） */
    private val RECOVER_SKIP_EXTS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "heif",
        "mp3", "wav", "ogg", "m4a", "aac", "flac", "mp4", "mov", "avi", "mkv", "webm", "3gp",
        "zip", "rar", "7z", "gz", "bz2", "xz", "tar", "jar", "apk", "aab", "dex", "so", "dll",
        "exe", "bin", "class", "o", "a", "pyc", "woff", "woff2", "ttf", "otf", "db", "sqlite"
    )

    private fun normRel(p: String): String =
        p.trim().replace('\\', '/').removePrefix("./").trimStart('/').trimEnd('/')

    private fun capText(t: String): String =
        if (t.length > RECOVER_TEXT_MAX) t.take(RECOVER_TEXT_MAX.toInt()) + "\n…(已截断)" else t

    /**
     * 按**磁盘事实**反推「这次任务改了什么」，补上追加式日志覆盖不到的那部分。
     *
     * ## 为什么必须有这一层
     * 日志挂在 [AiChatManager.recordFileChange] 上，只能记到经过工具（write_file / edit_file / …）的改动。
     * 但 AI 常用的 `shell_exec`、`execute_python`、`run_script` 是**脚本自己写文件**，压根不经过那个漏斗。
     * 这类改动原本只靠任务收尾时的一次内存快照 diff（`mergeSandboxDiff`）来兜 —— 而进程被 SIGKILL 时
     * 收尾代码不会执行，那次 diff 就永远不会发生，改动清单里彻底没有它们。
     *
     * ## 判据
     * 文件 mtime ≥ 任务开始时刻（留 [RECOVER_MTIME_SLACK_MS] 容差），且该路径**没有被日志精确记录过**。
     * 已经被日志记过的路径一律以日志为准 —— 日志带 op/fromPath（renamed / copied 这类语义是反推不出来的）。
     *
     * ## 不吹牛的边界
     * ① 若脚本刻意保留 mtime（`cp -p`、`touch -r`），这里会漏判 —— 属于极少数情况；
     * ② 任务期间用户自己也动过工作区文件，会被一并算进来（但确实都是"任务期间变过的文件"）；
     * ③ 崩溃后到下次启动之间用户又改了文件，同样会被算进来；
     * ④ 二进制/媒体按扩展名跳过（没有可读 diff 的价值），单次最多 [RECOVER_MAX_ITEMS] 条。
     * 反向的取舍是明确的：**宁可多列几条并标注"可能不完整"，也不要让整段改动凭空消失**。
     */
    private fun recoverUntrackedChanges(ctx: Context, r: Recovered): List<AiChatManager.FileChange> {
        val root = try { AiChatManager.agentRoot(ctx) } catch (_: Throwable) { return emptyList() }
        if (!root.isDirectory) return emptyList()
        val since = r.startedAt - RECOVER_MTIME_SLACK_MS
        if (since <= 0L) return emptyList()

        // 日志已精确覆盖的路径（含重命名的源路径）：不再反推，避免同一条改动出现两种语义
        val covered = HashSet<String>()
        r.changes.forEach { c ->
            val p = normRel(c.path)
            if (p.isNotBlank()) covered.add(p)
            val f = normRel(c.fromPath)
            if (f.isNotBlank()) covered.add(f)
        }
        val out = ArrayList<AiChatManager.FileChange>()
        try {
            root.walkTopDown().forEach { f ->
                if (out.size >= RECOVER_MAX_ITEMS) return@forEach
                if (!f.isFile) return@forEach
                val len = try { f.length() } catch (_: Throwable) { 0L }
                if (len <= 0L) return@forEach
                if (len > RECOVER_TEXT_MAX) return@forEach
                val rel = try { f.relativeTo(root).invariantSeparatorsPath } catch (_: Throwable) { return@forEach }
                if (rel.isBlank()) return@forEach
                val key = normRel(rel)
                if (key.isBlank() || covered.contains(key)) return@forEach
                val ext = key.substringAfterLast('.', "").lowercase()
                if (ext in RECOVER_SKIP_EXTS) return@forEach
                val mtime = try { f.lastModified() } catch (_: Throwable) { 0L }
                if (mtime < since) return@forEach
                // 读正文：含 0x00 视为二进制，跳过（没有可读 diff）
                val newText = try {
                    val bytes = f.readBytes()
                    if (bytes.contains(0.toByte())) "" else String(bytes, Charsets.UTF_8)
                } catch (_: Throwable) { "" }
                if (newText.isBlank()) return@forEach
                // oldText 从**落盘的对话基线**读（任务前状态），不用内存里的任何东西。
                // 直接复用既有事实源 baselineTextForPath，避免这里再拼一遍路径规则、日后漂移。
                val oldText = try { baselineTextForPath(ctx, r.convKey, key).orEmpty() } catch (_: Throwable) { "" }
                out.add(
                    AiChatManager.FileChange(
                        path = rel,
                        // 基线里没有这个路径 → 这次任务新建的；有 → 修改过的
                        op = if (oldText.isBlank()) "created" else "modified",
                        oldText = capText(oldText),
                        newText = capText(newText),
                        size = len
                    )
                )
            }
        } catch (e: Throwable) {
            ModelLogger.warn(ctx, "JOURNAL", "反推未记录改动失败：${e.message}")
        }
        return out
    }

    /** 仅供排查：当前是否有一条正在写入的活动日志 */
    fun hasActive(): Boolean = activeFile != null
}
