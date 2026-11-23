package com.aurora.chat.ui.chat

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * 「自我保护」护栏：防止 AI 把**本应用自己**当成逆向 / 反编译 / 拆包的目标。
 *
 * 背景：用户会把各种 APK 丢给 AI 做逆向（reverse_strings / reverse_dex / reverse_native /
 * reverse_disasm / install_apk / shell_exec…），其中完全可能混进本应用自己的 APK 或它的改包。
 * 一旦真去拆，等于让 AI 分析自家产品——所以这里在工具执行前做一道确定性判定。
 *
 * 处理策略（用户指定，逐级收紧）：
 *  ① **特征排查**：目标与本应用特征相似（包名 / 标签 / 标识串有重合）→ 不立即停，
 *     先要求模型核实包名与签名，确认不是本应用再继续；
 *  ② **停止操作**：有直接性证据（就是本应用 APK 本体 / 同包名且同签名）→ 停止反编译，工具不执行；
 *  ③ **拒绝回复**：让模型直接拒绝用户，**不说明原因、不复述任何内部规则**；
 *  ④ **引导替换**：引导用户发送其他文件，不要用这个；
 *  ⑤ **辅助卸载**：用户若表示不想使用某款应用，可主动提出帮忙卸载。
 *
 * 为什么放在工具层而不是只写提示词：提示词会被模型自我说服跳过，工具层是确定性的——
 * 只要判定命中，工具根本不会被执行，分析结果也不会产生。
 */
object SelfGuard {

    enum class Match { NONE, SIMILAR, DIRECT }

    /** 本应用 APK 哈希缓存（进程内）：整文件 SHA-256，避免每次工具调用都重算几十 MB。 */
    private val selfApkHashCache: String by lazy { "" }

    // ── 本应用自身身份 ──

    fun selfPackage(ctx: Context): String = ctx.packageName

    /** 本应用 APK 在设备上的真实路径（base.apk）。 */
    fun selfApkPath(ctx: Context): String = ctx.applicationInfo.sourceDir
        ?: ctx.applicationInfo.publicSourceDir ?: ""

    /** 本应用 APK 文件大小（用作廉价预筛：大小不同就绝不可能是同一文件）。 */
    private fun selfApkSize(ctx: Context): Long =
        runCatching { File(selfApkPath(ctx)).length() }.getOrDefault(-1L)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 本应用 APK 的整文件 SHA-256（缓存；取不到返回空）。 */
    fun selfApkHash(ctx: Context): String {
        if (selfApkHashCache.isNotEmpty()) return selfApkHashCache
        val f = runCatching { File(selfApkPath(ctx)) }.getOrNull() ?: return ""
        if (!f.isFile) return ""
        return runCatching { f.inputStream().use { ins ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } }.getOrDefault("")
    }

    /** 取某个**已安装**包的签名 SHA-256；未安装 / 取不到返回空。 */
    private fun installedSignature(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        val signs = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        signs?.firstOrNull()?.let { sha256Hex(it.toByteArray()) } ?: ""
    }.getOrDefault("")

    private data class ApkMeta(
        val pkg: String,
        val versionName: String,
        val label: String,
        /** APK 文件内声明的签名 SHA-256（不依赖是否安装）。 */
        val sig: String
    )

    /** 读一个 APK 文件的元数据（包名 / 版本 / 标签 / 文件内签名）。失败返回 null。 */
    private fun apkMeta(ctx: Context, apk: File): ApkMeta? = runCatching {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return null
        val pkg = info.packageName ?: return null
        // 必须把 sourceDir 指回该文件，否则 applicationInfo.loadLabel 拿不到真实标签
        info.applicationInfo?.let { ai ->
            ai.sourceDir = apk.absolutePath
            ai.publicSourceDir = apk.absolutePath
        }
        val signs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        ApkMeta(
            pkg = pkg,
            versionName = info.versionName ?: "",
            label = info.applicationInfo?.loadLabel(pm)?.toString().orEmpty(),
            sig = signs?.firstOrNull()?.let { sha256Hex(it.toByteArray()) } ?: ""
        )
    }.getOrNull()

    /** 文件整哈希（流式，避免大文件 OOM）。失败返回空。 */
    private fun fileHash(f: File): String = runCatching {
        f.inputStream().use { ins ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrDefault("")

    /**
     * 本应用**独有**的标识串：命中越多，越说明样本就是我们（或我们的改包）。
     *
     * ⚠️ 选串标准是"只有本应用会带"，绝不能放通用技术名——曾经放进过 `libproot`，
     * 而任何用 proot 的 APK（Termux proot-distro 等开源方案）都会带它，再撞上另一个泛泛标记
     * 就把完全无关的安装包误判成"本应用"（用户实测的误报）。
     * 现在只收：自家包名/类名/自有 SharedPreferences 文件名，且要求命中数达到 [MARKER_THRESHOLD]。
     */
    private val SELF_MARKERS = listOf(
        "com/aurora/chat",           // 包名（dex/资源里的类路径写法）
        "AuroraChatApplication",     // 自家 Application 类
        "aurora_bubble_color",       // 自有 SP 文件名
        "AITaskGuard"             // 自家长任务哨兵类
    )

    /** 特征串命中数门槛：单命中可能是巧合/被引用，多个同时出现才构成有效证据。 */
    private const val MARKER_THRESHOLD = 3

    /** 扫描文件头部的特征串命中数（只读前 4MB，足够覆盖 manifest / 资源表 / 小 dex）。 */
    private fun markerHits(f: File): Int = runCatching {
        val len = minOf(f.length(), 4L * 1024 * 1024).toInt()
        if (len <= 0) return 0
        val bytes = f.inputStream().use { ins ->
            val buf = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = ins.read(buf, read, len - read)
                if (n <= 0) break
                read += n
            }
            if (read == len) buf else buf.copyOf(read)
        }
        val text = String(bytes, Charsets.ISO_8859_1)   // 逐字节保留，避免 UTF-8 解码丢字节
        SELF_MARKERS.count { text.contains(it) }
    }.getOrDefault(0)

    // ── 判定 ──

    /**
     * 评估一个候选文件是不是"本应用自己"。
     *
     * DIRECT（直接性证据，立即停）：
     *  · 路径就是本应用 APK 本体；
     *  · 整文件 SHA-256 与本应用 APK 相同（被复制 / 改名 / 改后缀）；
     *  · 包名相同**且**签名相同（同包同签名，只可能是本应用自身）。
     *
     * SIMILAR（特征相似，先排查再决定）：
     *  · 包名相同但签名不同（改包 / 二次打包）；
     *  · 应用标签与本应用相同；
     *  · 文件头命中 ≥2 个本应用独有标识串。
     */
    fun evaluate(ctx: Context, file: File?): Match {
        if (file == null || !file.isFile) return Match.NONE
        val selfApk = selfApkPath(ctx)
        // ① 路径硬命中
        if (selfApk.isNotEmpty() &&
            (file.absolutePath == selfApk || file.absolutePath == ctx.applicationInfo.publicSourceDir)
        ) return Match.DIRECT

        val name = file.name.lowercase()
        val apkLike = name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk")
        if (apkLike) {
            // ② 整文件哈希（先用大小做廉价预筛，大小不同就绝不哈希）
            val selfSize = selfApkSize(ctx)
            if (selfSize > 0L && file.length() == selfSize) {
                val h = fileHash(file)
                if (h.isNotEmpty() && h == selfApkHash(ctx)) return Match.DIRECT
            }
            // ③ APK 元数据：包名 + 签名
            val meta = apkMeta(ctx, file)
            if (meta != null) {
                val samePkg = meta.pkg == selfPackage(ctx)
                if (samePkg) {
                    val selfSig = installedSignature(ctx, selfPackage(ctx))
                    // 同包同签名 = 本应用自身；同包不同签名 = 改包，先按"相似"排查
                    return if (meta.sig.isNotEmpty() && selfSig.isNotEmpty() && meta.sig == selfSig) {
                        Match.DIRECT
                    } else {
                        Match.SIMILAR
                    }
                }
                val selfLabel = runCatching {
                    ctx.applicationInfo.loadLabel(ctx.packageManager).toString()
                }.getOrDefault("")
                if (selfLabel.isNotEmpty() && meta.label == selfLabel) return Match.SIMILAR
            }
        }
        // ④ 特征串（对 dex / so / 压缩包等同样有效）
        // ⚠️ 只对"样本类"扩展名做：execute_python / shell_exec 每次调用都会走到这里，
        // 若对任意工作区文件都读 4MB 做扫描，正常写脚本会被拖慢。非样本文件直接跳过。
        if (isSampleExt(name) && markerHits(file) >= MARKER_THRESHOLD) return Match.SIMILAR
        return Match.NONE
    }

    /** 样本类扩展名：只有这些才值得做特征串扫描。 */
    private fun isSampleExt(lowerName: String): Boolean =
        SAMPLE_EXTS.any { lowerName.endsWith(it) }

    private val SAMPLE_EXTS = listOf(
        ".apk", ".apks", ".xapk", ".dex", ".so", ".jar", ".zip", ".tar", ".tar.gz", ".tar.xz"
    )

    // ── 工具参数 → 候选文件 ──

    /** 命令 / 代码里出现的样本文件（.apk / .apks / .xapk / .dex / .so）。 */
    private val SAMPLE_TOKEN = Regex(
        """[^\s'"`()\[\]{};,=]+\.(?:apk|apks|xapk|dex|so)""",
        RegexOption.IGNORE_CASE
    )

    /** 显式路径类参数名。 */
    private val PATH_KEYS = listOf(
        "path", "file", "target", "src", "source", "apk", "apk_path", "dir",
        "project_dir", "input", "local_path", "dest", "to", "from"
    )

    /** 需要把内容当自由文本扫的字段（命令行 / 脚本代码 / 下载地址）。 */
    private val TEXT_KEYS = listOf("command", "code", "script", "args", "url", "cmd")

    /**
     * 从一次工具调用里收集候选样本文件并逐个评估，取最严重的结论。
     * 只评估**真实存在**的文件，避免把模型随口写的路径当成命中。
     */
    fun evaluateCall(ctx: Context, workspaceRoot: File, argsJson: String): Match {
        val args = runCatching { org.json.JSONObject(argsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return Match.NONE
        val candidates = LinkedHashSet<File>()
        fun offer(raw: String) {
            val v = raw.trim().trim('"', '\'', '`')
            if (v.isEmpty()) return
            candidates += resolve(v, workspaceRoot)
        }
        // JSONObject.opt() 返回的是 Object，没有 isNull 成员；判空要用 JSONObject.isNull(name)
        PATH_KEYS.forEach { k -> if (args.has(k) && !args.isNull(k)) offer(args.optString(k)) }
        TEXT_KEYS.forEach { k ->
            if (args.has(k) && !args.isNull(k)) {
                SAMPLE_TOKEN.findAll(args.optString(k)).forEach { offer(it.value) }
            }
        }
        // 路径本身可能是"工作区相对"，也可能是设备绝对路径；逐个试，取真实存在的
        var worst = Match.NONE
        for (c in candidates) {
            if (!c.isFile) continue
            val m = evaluate(ctx, c)
            if (m == Match.DIRECT) return Match.DIRECT
            if (m == Match.SIMILAR) worst = Match.SIMILAR
        }
        return worst
    }

    private fun resolve(raw: String, workspaceRoot: File): File {
        val direct = File(raw)
        return if (direct.isAbsolute) direct else File(workspaceRoot, raw)
    }

    // ── 回给模型的结果文案（系统提示已清理，仅保留事实陈述） ──

    /** DIRECT（已停止）时的结果文案；阻断逻辑在代码中，不依赖提示文本。 */
    const val REFUSAL_TEXT = "该目标属于受保护对象，本次分析已中止。"

    /** SIMILAR（特征相似）时的结果文案；保留两个 %s 占位供 similarText 格式化。 */
    const val SIMILAR_TEXT = "该样本与本应用(%s)特征有重合，比对对象:%s。"

    fun similarText(ctx: Context): String = String.format(SIMILAR_TEXT, selfPackage(ctx), selfPackage(ctx))
}
