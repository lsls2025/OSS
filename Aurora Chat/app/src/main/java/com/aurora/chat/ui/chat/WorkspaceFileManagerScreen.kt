package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.ui.components.FileEditorHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.compose.material3.AlertDialog

private val WsBlue = Color(0xFF1E40AF)   // 品牌深蓝(与 MainActivity 等主操作一致)
private val WsPurple = Color(0xFF7C3AED)
private val WsRed = Color(0xFFDC2626)

/** 文件列表条目快照:扫描时在 IO 线程一次性取好 name/类型/大小/时间,
 *  列表项渲染只读内存字段——主线程组合期零磁盘系统调用(性能关键)。 */
private data class WsEntry(
    val f: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long
)

/** 文件列表分页:首屏条数 / 每次追加条数(滚动到底前 20 项时自动续载) */
private const val WS_PAGE_FIRST = 100
private const val WS_PAGE_STEP = 150
/** 全库搜索的目录遍历与结果上限(防超大树拖死 IO) */
private const val WS_SEARCH_MAX_RESULTS = 500
private const val WS_SEARCH_MAX_DIRS = 4000

/**
 * AI 工作区文件管理：从右侧滑入的全屏面板。
 * 展示 ai_files 沙盒内全部文件与目录(含 .开头的隐藏项),支持面包屑导航、
 * 新建文件/新建文件夹、打开文本文件编辑保存、重命名与删除——
 * UI 模式复用网站托管的 SandboxFileBrowser(新建弹窗/编辑器/行组件样式)。
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkspaceFileManagerScreen(
    onBack: () -> Unit,
    onOpenBackup: () -> Unit = {},
    /** 打开面板后要定位的文件引用名（来自聊天中点击文件胶囊），null=不定位 */
    locateName: String? = null,
    /** true=定位后自动打开编辑器/查看; false=仅定位到文件所在目录并高亮 */
    locateOpen: Boolean = false,
    /** true=「选择文件」模式：点中文件即把该文件交给当前对话（文件夹仍为进入下级） */
    pickMode: Boolean = false,
    /** ⋮ 菜单项文案（随会话类型变化：发送给 AI / 发送给群聊 / 发送给好友） */
    referenceLabel: String = "发送给 AI",
    /** 把工作区文件的相对路径交给当前对话（⋮ 菜单项与选择模式共用）。null=不提供该能力 */
    onPickToChat: ((String) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { AiChatManager.agentRoot(ctx) }
    val noRipple = remember { MutableInteractionSource() }
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var currentDir by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<WsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    // ── 搜索(分割线下方输入框):空=浏览当前目录;非空=全库递归按名搜索 ──
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<WsEntry>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    // ── 分页懒加载:首屏 WS_PAGE_FIRST 条,滚动到底前 20 项自动续载 WS_PAGE_STEP 条 ──
    var visibleCount by remember { mutableIntStateOf(WS_PAGE_FIRST) }

    // ── 文件引用定位:高亮目标文件并滚动到可视区（locateName 非空时触发一次）──
    var highlightFileName by remember { mutableStateOf<String?>(null) }
    var locateHandled by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // ── 新建（合并 文件 / 文件夹）──
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newIsFolder by remember { mutableStateOf(false) }

    // ── 删除/重命名 ──
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var deleteLoading by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var renameName by remember { mutableStateOf("") }

    // ── 编辑器 ──
    var editingFile by remember { mutableStateOf<File?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var editLoading by remember { mutableStateOf(false) }

    // ── 图片查看 / APK 安装 ──
    var viewingImage by remember { mutableStateOf<File?>(null) }
    var installTarget by remember { mutableStateOf<File?>(null) }
    var installLaunching by remember { mutableStateOf(false) }

    // ── 多选模式 ──
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<File>>(emptySet()) }


    // ── 批量删除确认(多选模式下) ──
    var deleteBatchConfirm by remember { mutableStateOf(false) }

    // ── 单个文件下载时暂存目标,再触发目录选择 ──
    var downloadTarget by remember { mutableStateOf<File?>(null) }

    // ── 复制/移动 剪贴板(移动到别的目录用顶部「粘贴」) ──
    var clipboard by remember { mutableStateOf<WsClipboard?>(null) }

    // ── 通用文件操作(解压/压缩/复制/移动)进度 ──
    var opLoading by remember { mutableStateOf(false) }
    var opHint by remember { mutableStateOf("") }

    // ── 文件详情弹窗目标 ──
    var detailTarget by remember { mutableStateOf<File?>(null) }

    // ── 回收站（应用私有目录;删除的文件统一移入此处,不物理删除）──
    var showRecycleBin by remember { mutableStateOf(false) }
    val recycleBinDir = remember { File(ctx.filesDir, "trash") }

    // ── 顶栏三点菜单（列表驱动,日后新增项直接往 topMenuItems 追加）──
    var topMenuOpen by remember { mutableStateOf(false) }
    val topMenuItems = remember(onOpenBackup) {
        listOf(
            WsTopMenuItem("备份", Icons.Filled.Backup, WsBlue) { onOpenBackup() }
        )
    }

    // ── 返回键拦截:多选模式优先退出多选,再触发面板关闭 ──
    BackHandler(enabled = multiSelectMode || deleteBatchConfirm || deleteTarget != null || renameTarget != null || showRecycleBin || viewingImage != null || opLoading) {
        when {
            multiSelectMode -> { multiSelectMode = false; selectedFiles = emptySet() }
            deleteBatchConfirm -> deleteBatchConfirm = false
            deleteTarget != null -> deleteTarget = null
            renameTarget != null -> renameTarget = null
            showRecycleBin -> showRecycleBin = false
            viewingImage != null -> viewingImage = null
            else -> {}
        }
    }

    fun relPath(f: File): String =
        f.absolutePath.removePrefix(root.absolutePath).trimStart('/')

    fun validName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') &&
            name != "." && name != ".." && !name.contains('\u0000')

    fun toast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** 去掉会让写盘异常或被判成路径的非法字符(路径分隔符 / 与空字符),并兜底空名/危险名 */
    fun sanitizeUploadName(raw: String): String {
        val cleaned = raw.replace('\\', '/').substringAfterLast('/')
            .replace(Regex("[\\x00/]"), "_").trim()
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "upload_${System.currentTimeMillis()}" else cleaned
    }

    /** 从 content:// URI 正确解析原始文件名。
     *  OpenMultipleDocuments 返回的是内容 URI,其 lastPathSegment 往往是提供方的内部文档 ID
     *  (某些文件管理器/云盘会返回 "msf" 这类串)——这就是上传后文件名被强制改成乱码的根因。
     *  多级 fallback:① OpenableColumns.DISPLAY_NAME → ② DocumentsContract.Document.COLUMN_DISPLAY_NAME →
     *  ③ DocumentsContract.getDocumentId 解析(形如 "primary:Download/real_name.zip") → ④ lastPathSegment */
    fun resolveUploadName(uri: android.net.Uri): String {
        if (uri.scheme == "content") {
            // ① 标准 OpenableColumns.DISPLAY_NAME
            runCatching {
                ctx.contentResolver.query(
                    uri, arrayOf(
                        android.provider.OpenableColumns.DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        for (col in arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,
                            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)) {
                            val i = c.getColumnIndex(col)
                            if (i >= 0) {
                                val n = c.getString(i)?.trim()
                                if (!n.isNullOrBlank()) return sanitizeUploadName(n)
                            }
                        }
                    }
                }
            }
            // ③ 从 DocumentsContract.getDocumentId 解析(形如 "primary:Download/real.zip" → 取最后一段)
            runCatching {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                if (!docId.isNullOrBlank()) {
                    // 有些 docId 格式 "volume:path/to/file",取冒号后的部分再取最后一段
                    val afterColon = docId.substringAfter(':', missingDelimiterValue = docId)
                    val candidate = afterColon.substringAfterLast('/')
                        .substringAfterLast('\\')
                    if (candidate.isNotBlank()) return sanitizeUploadName(candidate)
                }
            }
        }
        // ④ 回退:lastPathSegment 最后一段
        val seg = uri.lastPathSegment ?: "upload_${System.currentTimeMillis()}"
        return sanitizeUploadName(seg.substringAfterLast('/').substringAfterLast('\\').trim()
            .ifBlank { "upload_${System.currentTimeMillis()}" })
    }

    var lastDirSig by remember { mutableStateOf("") }
    // 廉价轮询依据:目录自身 mtime(仅条目增删/改名时变化)。无变化时轮询 O(1) 跳过,
    // 几千文件的目录也不会每 2 秒全量 stat 扫描(旧实现无条件 O(N) 扫是卡顿根因之一)。
    var lastDirMtime by remember { mutableStateOf(0L) }
    // FileObserver 事件置位:目录 mtime 不变但文件内容变过(写文件不改目录 mtime),下次轮询强制全扫
    val pollKick = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // 扫描序号:同目录两次扫描可能乱序完成(慢的旧扫描后到),只有最新一次允许落盘,防旧数据覆盖新数据
    val scanSeq = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    // 目录快照签名:名称|类型|大小|修改时间 拼接。只有扫描结果真实变化才更新 entries。
    fun dirSignature(list: List<WsEntry>): String =
        list.joinToString(";") { "${it.name}|${it.isDir}|${it.size}|${it.mtime}" }

    /** 全量扫描当前目录(suspend,IO 线程一次取齐快照字段,渲染期零磁盘调用) */
    suspend fun refresh() {
        if (entries.isEmpty()) loading = true
        val dir = currentDir
        val seq = scanSeq.incrementAndGet()
        val snapshot = withContext(Dispatchers.IO) {
            runCatching {
                (dir.listFiles() ?: emptyArray()).map { f ->
                    WsEntry(f, f.name, f.isDirectory, if (f.isFile) f.length() else 0L, f.lastModified())
                }
            }.getOrDefault(emptyList())
        }
        val sorted = snapshot.sortedWith(compareByDescending<WsEntry> { it.isDir }.thenBy { it.name.lowercase() })
        val sig = dirSignature(sorted)
        if (dir == currentDir && seq == scanSeq.get()) {
            lastDirMtime = runCatching { dir.lastModified() }.getOrDefault(0L)
            if (sig != lastDirSig) {
                entries = sorted; lastDirSig = sig
                // 目录内容变化后同步校正多选集合（改后缀/重命名/外部删除后旧路径失效）：
                // ① 仍存在的原路径保留；② 原路径已不存在但目录里有 大小+修改时间 完全一致的条目
                //    → 重映射到新路径（保留选中，展示新文件名）；③ 其余失效项剔除，
                //    保证「已选 N 项」与删除确认弹窗列表与实际文件一致，不出现改前的旧文件。
                if (selectedFiles.isNotEmpty()) {
                    // 多选可能含文件夹,校正不再排除目录
                    val files = sorted
                    val livePaths = files.mapTo(HashSet()) { it.f.absolutePath }
                    val keyOf = { f: File -> f.length() to f.lastModified() }
                    val liveByKey = files.associateBy { keyOf(it.f) }
                    selectedFiles = selectedFiles.mapNotNull { f ->
                        when {
                            f.absolutePath in livePaths -> f
                            else -> liveByKey[keyOf(f)]?.f
                        }
                    }.toSet()
                }
            }
            loading = false
        }
    }

    // ── 上传/下载 launcher ──
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val safeCurrentDir = currentDir
        val safeRoot = root
        scope.launch(Dispatchers.IO) {
            var okCount = 0
            val failedDetails = mutableListOf<String>()
            for (uri in uris) {
                val safeName = resolveUploadName(uri)
                val dest = File(safeCurrentDir, safeName)
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    okCount++
                } catch (e: Exception) {
                    failedDetails.add("${safeName}: ${e.message}")
                }
            }
            withContext(Dispatchers.Main) {
                val msg = if (okCount == uris.size) "已上传 $okCount 个文件"
                         else "成功 $okCount / ${uris.size} 个" + if (failedDetails.isNotEmpty()) "\n${failedDetails.take(3).joinToString("\n")}" else ""
                toast(msg)
                scope.launch { refresh() }
            }
        }
    }

    // 下载目录选择 + 执行
    val treePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        // 持久化 URI 权限，让后续能持续往这个目录写
        try {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ctx.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        } catch (_: Exception) {}
        scope.launch(Dispatchers.IO) {
            val targetFiles = selectedFiles.ifEmpty { downloadTarget?.let { setOf(it) } ?: emptySet() }
            if (targetFiles.isEmpty()) return@launch
            val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, treeUri) ?: return@launch
            // 递归把本地文件夹整树写入 SAF 目录(文件夹参与批量下载)
            fun copyDirInto(docDir: androidx.documentfile.provider.DocumentFile, dir: File) {
                dir.listFiles()?.forEach { child ->
                    if (child.isDirectory) {
                        val sub = docDir.createDirectory(child.name) ?: return@forEach
                        copyDirInto(sub, child)
                    } else {
                        try {
                            val outDoc = docDir.createFile("*/*", child.name)
                            if (outDoc != null) {
                                ctx.contentResolver.openOutputStream(outDoc.uri)?.use { out ->
                                    child.inputStream().use { it.copyTo(out) }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            var okCount = 0
            for (f in targetFiles) {
                if (!f.exists()) continue
                try {
                    if (f.isFile) {
                        val outDoc = rootDoc.createFile("*/*", f.name)
                        if (outDoc != null) {
                            ctx.contentResolver.openOutputStream(outDoc.uri)?.use { out ->
                                f.inputStream().use { it.copyTo(out) }
                            }
                            okCount++
                        }
                    } else {
                        val dirDoc = rootDoc.createDirectory(f.name)
                        if (dirDoc != null) {
                            copyDirInto(dirDoc, f)
                            okCount++
                        }
                    }
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                toast(if (okCount == targetFiles.size) "已下载 $okCount 项" else "成功 $okCount / ${targetFiles.size} 项")
                selectedFiles = emptySet()
                multiSelectMode = false
            }
        }
    }

    /** 文本类扩展名白名单:命中才允许进编辑器。
     *  故意不在这里的二进制格式(apk/zip/pdf/图片/音视频等)靠 isImageFileWs/isVideoFileWs/isPdfFileWs
     *  等分流,不命中任何白名单的文件再走 [isTextContent] 做二进制检测兜底(无扩展名文件如 Makefile/.gitignore/.env 就能正确进编辑器)。 */
    val TEXT_EXTENSIONS = setOf(
        // 标记/结构
        "md", "markdown", "mdx", "txt", "rst", "adoc", "org",
        // Web
        "html", "htm", "xhtml", "mhtml", "css", "scss", "sass", "less", "styl",
        "js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte", "astro",
        // 数据/配置
        "json", "jsonc", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
        "csv", "tsv", "log", "env", "editorconfig", "gitignore", "dockerignore",
        // Java/Kotlin 生态
        "java", "kt", "kts", "gradle", "pro",
        // C/C++ 家族
        "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx", "inl", "cs",
        // 系统/脚本
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "psm1", "tcl", "awk",
        "rb", "py", "pyw", "pl", "pm", "php", "lua", "groovy",
        // 函数式/JVM
        "rs", "go", "swift", "m", "mm", "dart", "scala", "sc", "clj", "cljs", "r",
        // 其他源码
        "sql", "svg", "tex", "latex", "bib", "asm", "s", "v", "nim",
        // 模板
        "tmpl", "tpl", "jinja", "jinja2", "ejs", "mustache", "handlebars",
    )

    /** 文件头二进制兜底:扩展名不在白名单时,读前 8KB 判断是否含 NUL 字节。
     *  纯二进制(apk/zip/pdf/图片)一定会有 0x00;UTF-8/ASCII 文本不会有。 */
    fun isTextFileWs(file: File): Boolean {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext in TEXT_EXTENSIONS) return true
        // 已知二进制扩展名直接拒绝,省一次 IO
        if (ext in setOf("apk", "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "pdf",
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "ico",
                "mp4", "mov", "avi", "mkv", "webm", "3gp", "mpg", "mpeg", "flv", "wmv",
                "mp3", "wav", "ogg", "flac", "aac", "m4a", "wma", "opus", "mid", "midi",
                "class", "jar", "so", "dll", "exe", "bin", "dat", "db", "sqlite", "sqlite3",
                "woff", "woff2", "ttf", "otf", "eot", "psd", "ai", "sketch",
                "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
                "msg", "eml", "ics", "vcf",
                "png.", "jpeg.", "gif.", "webp.", "bmp.")) return false
        // 无扩展名或未知扩展名:读前 8KB 扫 NUL 字节
        return try {
            file.inputStream().use { ins ->
                val buf = ByteArray(8192)
                val n = ins.read(buf)
                if (n <= 0) return@use true // 空文件当文本
                buf.copyOf(n).none { it == 0.toByte() }
            }
        } catch (_: Exception) { false }
    }

    fun isImageFileWs(name: String): Boolean = listOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif"
    ).contains(name.substringAfterLast('.', "").lowercase())

    fun isVideoFileWs(name: String): Boolean = listOf(
        "mp4", "mov", "avi", "mkv", "webm", "3gp", "3gpp", "mpg", "mpeg", "flv", "wmv"
    ).contains(name.substringAfterLast('.', "").lowercase())

    fun isAudioFileWs(name: String): Boolean = listOf(
        "mp3", "wav", "ogg", "flac", "aac", "m4a", "wma", "opus"
    ).contains(name.substringAfterLast('.', "").lowercase())

    fun isPdfFileWs(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() == "pdf"


    /** 用系统 Intent 全屏打开工作区文件：视频/音频/PDF 都走这条，系统支持的格式最全。 */
    fun openWithSystem(file: File, mime: String? = null) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime ?: ctx.contentResolver.getType(uri))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            toast("无法打开:${e.message}")
        }
    }

    /** 按扩展名推断 MIME(分享给外部应用时填写,系统据此挑选可接收的应用)。 */
    fun mimeOfWs(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
            "webp" -> "image/webp"; "bmp" -> "image/bmp"; "heic" -> "image/heic"; "heif" -> "image/heif"
            "mp4" -> "video/mp4"; "mov" -> "video/quicktime"; "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"; "3gp", "3gpp" -> "video/3gpp"; "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"; "flac" -> "audio/flac"
            "aac" -> "audio/aac"; "m4a" -> "audio/mp4"; "opus" -> "audio/opus"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"; "md" -> "text/markdown"; "csv" -> "text/csv"
            "html", "htm" -> "text/html"; "xml" -> "text/xml"; "css" -> "text/css"
            "json" -> "application/json"; "js" -> "text/javascript"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }

    /**
     * 把文件夹整树压缩到 cache(仅供分享使用)。
     * QQ / 微信等外部应用不接收"文件夹"这种形态,必须打包成 ZIP 才能发出去;
     * 输出落在 cacheDir/aurora_share/,与工作区目录隔离,不会污染用户文件。
     */
    fun zipDirToCache(dir: File): File? = runCatching {
        val outDir = File(ctx.cacheDir, "aurora_share").apply { mkdirs() }
        val out = File(outDir, (dir.name.ifBlank { "folder" }) + ".zip")
        if (out.exists()) out.delete()
        val base = dir.absolutePath
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            dir.walkTopDown().forEach { f ->
                val rel = f.absolutePath.removePrefix(base).trimStart('/', '\\')
                if (rel.isEmpty()) return@forEach
                if (f.isDirectory) {
                    zos.putNextEntry(ZipEntry("$rel/"))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { ins -> ins.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        out
    }.getOrNull()

    /**
     * 分享工作区文件到**外部应用**(QQ / 微信 / 邮件等系统分享目标),而不是发到应用内部。
     *
     * 实现要点:
     * ① 经 FileProvider 授予一次性读权限(FLAG_GRANT_READ_URI_PERMISSION),无需任何存储权限;
     * ② 文件夹先压缩成临时 ZIP 再分享(外部应用不接收文件夹);
     * ③ 主动排除本应用自身 —— 本项目也注册了 SEND 接收(见 ShareReceiveHandler),
     *    若不排除,"分享"列表里会出现 Aurora Chat 自己,与"分享到外部"的语义相矛盾;
     * ④ 单个文件走 ACTION_SEND,多个文件走 ACTION_SEND_MULTIPLE。
     */
    fun shareToExternal(targets: Collection<File>) {
        if (targets.isEmpty()) { toast("请先选择要分享的文件"); return }
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                targets.mapNotNull { f ->
                    when {
                        f.isFile -> f
                        f.isDirectory -> zipDirToCache(f)
                        else -> null
                    }
                }
            }
            if (prepared.isEmpty()) { toast("没有可分享的文件"); return@launch }
            val uris = prepared.mapNotNull { f ->
                runCatching {
                    androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                }.getOrNull()
            }
            if (uris.isEmpty()) { toast("分享失败,请重试"); return@launch }
            // 类型:所有待分享文件类型一致时用它,否则用 */* 交给系统兜底
            val mime = prepared.map { mimeOfWs(it.name) }.distinct().singleOrNull() ?: "*/*"
            val send = android.content.Intent(
                if (uris.size == 1) android.content.Intent.ACTION_SEND
                else android.content.Intent.ACTION_SEND_MULTIPLE
            ).apply {
                type = mime
                if (uris.size == 1) putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
                else putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val self = android.content.ComponentName(ctx, com.aurora.chat.MainActivity::class.java)
            val exclude = arrayOf<android.os.Parcelable>(self)
            send.putExtra(android.content.Intent.EXTRA_EXCLUDE_COMPONENTS, exclude)
            val chooser = android.content.Intent.createChooser(send, "分享到")
            chooser.putExtra(android.content.Intent.EXTRA_EXCLUDE_COMPONENTS, exclude)
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(chooser) }.isFailure) toast("分享失败,请重试")
        }
    }

    /** 拉起系统安装器安装工作区 APK(经 FileProvider 授权,与 AI 的 install_apk 工具同机制) */
    fun launchInstall(apk: File) {
        installLaunching = true
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(intent)
                installTarget = null
            } else toast("未找到系统安装器,无法安装")
        } catch (e: Exception) {
            toast("无法安装:${e.message ?: "未知错误"}")
        } finally {
            installLaunching = false
        }
    }



    /**
     * 进入目录(点文件夹/面包屑/返回上级统一走这里):
     * ① 立即清空旧列表并显示加载态——绝不把上一层的内容挂在新目录名下展示;
     * ② 无条件刷新——就算 currentDir 值没变(重复点同一个文件夹)也重新扫描,
     *    修复「AI 写文件期间进入子目录看到上一层内容、再点没反应」的竞态。
     */
    fun navigateTo(dir: File) {
        currentDir = dir
        entries = emptyList()
        lastDirSig = ""
        lastDirMtime = 0L
        searchQuery = ""
        searchResults = emptyList()
        loading = true
        scope.launch { refresh() }
    }

    fun createNewFile() {
        if (!validName(newName)) { toast("文件名非法"); return }
        scope.launch(Dispatchers.IO) {
            val f = File(currentDir, newName)
            if (f.exists()) { withContext(Dispatchers.Main) { toast("同名文件已存在") } ; return@launch }
            val ok = runCatching { f.createNewFile() }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) { toast("已创建 ${newName}"); showCreateDialog = false; newName = ""; scope.launch { refresh() } }
                else toast("创建失败")
            }
        }
    }

    fun createNewFolder() {
        if (!validName(newName)) { toast("文件夹名非法"); return }
        scope.launch(Dispatchers.IO) {
            val f = File(currentDir, newName)
            if (f.exists()) { withContext(Dispatchers.Main) { toast("同名文件夹已存在") } ; return@launch }
            val ok = runCatching { f.mkdirs() }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) { toast("已创建文件夹 ${newName}"); showCreateDialog = false; newName = ""; scope.launch { refresh() } }
                else toast("创建失败")
            }
        }
    }

    /** 移入回收站：按原相对路径存到应用私有目录（保留原位置,便于恢复）;回收站内重名时追加序号 */
    fun moveToRecycleBin(f: File): Boolean {
        return runCatching {
            val dstRaw = File(recycleBinDir, relPath(f))
            val dst = if (dstRaw.exists()) {
                val dir = dstRaw.parentFile ?: recycleBinDir
                val base = dstRaw.name
                val dot = base.lastIndexOf('.')
                val hasExt = dot > 0 && dot < base.length - 1 && base.indexOf('/') < 0
                val nameNoExt = if (hasExt) base.substring(0, dot) else base
                val ext = if (hasExt) base.substring(dot) else ""
                var i = 1
                var c: String
                do { c = "${nameNoExt}_$i$ext"; i++ } while (File(dir, c).exists())
                File(dir, c)
            } else dstRaw
            dst.parentFile?.mkdirs()
            f.renameTo(dst)
        }.getOrDefault(false)
    }

    fun confirmDelete() {
        val target = deleteTarget ?: return
        deleteLoading = true
        scope.launch(Dispatchers.IO) {
            val ok = moveToRecycleBin(target)
            withContext(Dispatchers.Main) {
                deleteLoading = false
                if (ok) { toast("已移入回收站"); deleteTarget = null; scope.launch { refresh() } }
                else toast("删除失败")
            }
        }
    }

    fun confirmRename() {
        val target = renameTarget ?: return
        if (!validName(renameName)) { toast("名称非法"); return }
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { target.renameTo(File(target.parentFile, renameName)) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) { toast("已重命名"); renameTarget = null; scope.launch { refresh() } }
                else toast("重命名失败")
            }
        }
    }

    // ═══════ 文件操作:重名自动追加序号 ═══════
    fun resolveUniqueName(base: String, dir: File): String {
        if (!File(dir, base).exists()) return base
        val dot = base.lastIndexOf('.')
        val hasExt = dot > 0 && dot < base.length - 1 && base.indexOf('/') < 0
        val nameNoExt = if (hasExt) base.substring(0, dot) else base
        val ext = if (hasExt) base.substring(dot) else ""
        var i = 1
        var candidate: String
        do {
            candidate = "${nameNoExt}_$i$ext"
            i++
        } while (File(dir, candidate).exists())
        return candidate
    }

    // 递归复制(文件 / 目录)
    fun copyRecursively(src: File, dst: File): Boolean {
        return if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) return false
            val kids = src.listFiles() ?: return true
            var ok = true
            for (k in kids) ok = ok && copyRecursively(k, File(dst, k.name))
            ok
        } else {
            try {
                dst.parentFile?.mkdirs()
                src.inputStream().use { ins -> dst.outputStream().use { outs -> ins.copyTo(outs) } }
                true
            } catch (_: Exception) { false }
        }
    }

    // 复制:只记录到剪贴板(非剪切),到目标目录点顶部「粘贴」才真正落地
    fun doCopy(f: File) { clipboard = WsClipboard(listOf(f), false) }

    // 解压 ZIP 到当前目录下的同名文件夹(防 Zip Slip)
    fun doUnzip(zipFile: File) {
        opLoading = true; opHint = "正在解压…"
        scope.launch(Dispatchers.IO) {
            val baseName = zipFile.name.removeSuffix(".zip").removeSuffix(".ZIP")
            val dirName = resolveUniqueName(baseName, currentDir)
            val outDir = File(currentDir, dirName)
            var ok = true; var count = 0
            try {
                outDir.mkdirs()
                val outCanon = outDir.canonicalPath
                ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val rel = entry.name.replace('\\', '/')
                        val target = File(outDir, rel).canonicalFile
                        if (target.canonicalPath != outCanon &&
                            !target.canonicalPath.startsWith(outCanon + File.separator)) {
                            zis.closeEntry(); entry = zis.nextEntry; continue
                        }
                        if (entry.isDirectory) target.mkdirs()
                        else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { os -> zis.copyTo(os) }
                            count++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (_: Exception) { ok = false }
            withContext(Dispatchers.Main) {
                opLoading = false
                toast(if (ok) "已解压到 $dirName/（$count 个文件）" else "解压失败（仅支持 ZIP 格式）")
                if (ok) refresh()
            }
        }
    }

    // 把文件 / 文件夹压缩成 ZIP 到当前目录
    fun doZip(target: File) {
        opLoading = true; opHint = "正在压缩…"
        scope.launch(Dispatchers.IO) {
            val zipName = resolveUniqueName(target.name + ".zip", currentDir)
            val zipFile = File(currentDir, zipName)
            var ok = true
            try {
                ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                    fun addFile(f: File, rel: String) {
                        if (f.isDirectory) {
                            zos.putNextEntry(ZipEntry(rel)); zos.closeEntry()
                            f.listFiles()?.forEach { addFile(it, "$rel${it.name}/") }
                        } else {
                            zos.putNextEntry(ZipEntry(rel + f.name))
                            f.inputStream().buffered().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    if (target.isDirectory) {
                        zos.putNextEntry(ZipEntry(target.name + "/")); zos.closeEntry()
                        target.listFiles()?.forEach { addFile(it, target.name + "/" + it.name) }
                    } else addFile(target, target.name)
                }
            } catch (_: Exception) { ok = false; runCatching { zipFile.delete() } }
            withContext(Dispatchers.Main) {
                opLoading = false
                toast(if (ok) "已压缩为 $zipName" else "压缩失败")
                if (ok) refresh()
            }
        }
    }

    // 批量压缩:选中多个文件/文件夹打包成一个 ZIP 到当前目录
    fun doZipBatch(files: Set<File>) {
        if (files.isEmpty()) return
        opLoading = true; opHint = "正在压缩…"
        scope.launch(Dispatchers.IO) {
            val zipName = resolveUniqueName("archive.zip", currentDir)
            val zipFile = File(currentDir, zipName)
            var ok = true
            try {
                ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                    fun addFile(f: File, rel: String) {
                        if (f.isDirectory) {
                            zos.putNextEntry(ZipEntry(rel)); zos.closeEntry()
                            f.listFiles()?.forEach { addFile(it, "$rel${it.name}/") }
                        } else {
                            zos.putNextEntry(ZipEntry(rel + f.name))
                            f.inputStream().buffered().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    for (f in files.sortedBy { it.name }) {
                        if (f.isDirectory) {
                            zos.putNextEntry(ZipEntry(f.name + "/")); zos.closeEntry()
                            f.listFiles()?.forEach { addFile(it, f.name + "/" + it.name) }
                        } else addFile(f, f.name)
                    }
                }
            } catch (_: Exception) { ok = false; runCatching { zipFile.delete() } }
            withContext(Dispatchers.Main) {
                opLoading = false
                toast(if (ok) "已压缩为 $zipName" else "压缩失败")
                if (ok) { refresh(); multiSelectMode = false; selectedFiles = emptySet() }
            }
        }
    }

    // 剪切到剪贴板(移动),到目标目录后点顶部「粘贴」
    fun doCut(f: File) { clipboard = WsClipboard(listOf(f), true) }

    // 批量复制/剪切:整个选中集(含文件夹)进剪贴板,到目标目录点顶部「粘贴」统一落地
    fun doCopyBatch(files: Set<File>) {
        if (files.isEmpty()) return
        clipboard = WsClipboard(files.sortedBy { it.name }, false)
        multiSelectMode = false; selectedFiles = emptySet()
        toast("已复制 ${files.size} 项,可到目标目录粘贴")
    }
    fun doCutBatch(files: Set<File>) {
        if (files.isEmpty()) return
        clipboard = WsClipboard(files.sortedBy { it.name }, true)
        multiSelectMode = false; selectedFiles = emptySet()
        toast("已剪切 ${files.size} 项,可到目标目录粘贴")
    }

    // 粘贴(移动 / 复制)到当前目录;支持剪贴板中多个来源(多选复制/剪切)
    fun doPaste() {
        val item = clipboard ?: return
        val srcs = item.items.filter { it.exists() }
        if (srcs.isEmpty()) {
            clipboard = null
            toast("源文件已不存在")
            return
        }
        opLoading = true; opHint = if (item.isCut) "正在移动…" else "正在复制…"
        scope.launch(Dispatchers.IO) {
            var ok = 0
            val fails = mutableListOf<String>()
            for (src in srcs) {
                val sameDir = runCatching { src.parentFile?.canonicalPath == currentDir.canonicalPath }.getOrDefault(false)
                if (item.isCut && sameDir) { ok++; continue } // 剪切且已在当前目录:视为已完成
                val dstName = resolveUniqueName(src.name, currentDir)
                val dst = File(currentDir, dstName)
                val done = if (item.isCut) {
                    val moved = runCatching { src.renameTo(dst) }.getOrDefault(false)
                    if (moved) true else {
                        val c = copyRecursively(src, dst)
                        if (c) runCatching { if (src.isDirectory) src.deleteRecursively() else src.delete() }.getOrDefault(false) else false
                    }
                } else copyRecursively(src, dst)
                if (done) ok++ else fails.add(src.name)
            }
            withContext(Dispatchers.Main) {
                opLoading = false
                if (fails.isEmpty()) {
                    toast(if (item.isCut) "已移动 $ok 项到当前目录" else "已复制 $ok 项到当前目录")
                    if (item.isCut) clipboard = null
                } else toast(if (item.isCut) "移动完成 $ok / ${srcs.size} 项" else "复制完成 $ok / ${srcs.size} 项")
                refresh()
            }
        }
    }


    fun openEditor(f: File) {
        val size = f.length()
        // 只给警告,不再拒绝。大文件(>2MB)读入本身是 IO 问题,编辑器的卡顿由编辑器模块内部的高亮/撤销栈降级处理。
        when {
            size > 20 * 1024 * 1024L -> toast("超大文件(${(size / 1024 / 1024)}MB),加载与编辑都会很慢,请耐心等待")
            size > 5 * 1024 * 1024L  -> toast("文件较大(${(size / 1024 / 1024)}MB),加载可能稍慢")
        }
        editingFile = f
        editLoading = true
        scope.launch(Dispatchers.IO) {
            val content = runCatching {
                f.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse { "（无法读取:${it.message ?: "未知错误"}）" }
            withContext(Dispatchers.Main) { editingContent = content; editLoading = false }
        }
    }

    /**
     * 保存编辑器内容。保存后【不退出编辑器】,用户可继续编辑(保留退出场景由 closeAfter 控制,
     * 「保存并退出」用);快照同步更新,保存按钮随之变为「已保存」态,文件列表后台刷新大小/时间。
     */
    suspend fun saveEditor(text: String, closeAfter: Boolean) {
        val f = editingFile ?: return
        editLoading = true
        withContext(Dispatchers.IO) {
            val ok = runCatching { f.writeText(text) }.isSuccess
            withContext(Dispatchers.Main) {
                editLoading = false
                if (ok) {
                    toast(if (closeAfter) "已保存并退出" else "已保存")
                    refresh()
                    if (closeAfter) editingFile = null
                } else toast("保存失败")
            }
        }
    }

    // 返回键:先关编辑器,再回上一级目录,最后退面板。
    // 覆盖层(多选/删除确认/重命名/HTML预览/回收站/图片查看/加载)打开时禁用本组，
    // 避免后注册优先抢占 L183 的覆盖层返回处理导致穿透(如回收站返回直接关掉整个工作区)。
    val wsOverlayActive = multiSelectMode || deleteBatchConfirm || deleteTarget != null ||
        renameTarget != null || showRecycleBin || viewingImage != null || opLoading
    BackHandler(enabled = !wsOverlayActive && editingFile == null && currentDir != root) {
        navigateTo(currentDir.parentFile ?: root)
    }
    BackHandler(enabled = !wsOverlayActive && editingFile == null && currentDir == root) { onBack() }

    // 目录变化时自动刷新列表(进入面板/进入子目录/面包屑跳转都走这里)。
    // 廉价轮询:每 2s 只做 1 次目录 mtime stat——没变化且观察者没踢人时整轮跳过;
    // 文件内容级变化(写文件不改目录 mtime)由 FileObserver 置位 pollKick 强制全扫兜底。
    LaunchedEffect(currentDir) {
        lastDirSig = ""
        lastDirMtime = 0L
        refresh()
        while (true) {
            kotlinx.coroutines.delay(2000)
            val mtime = runCatching { currentDir.lastModified() }.getOrDefault(0L)
            if (pollKick.getAndSet(false) || mtime != lastDirMtime || entries.isEmpty()) scope.launch { refresh() }
        }
    }

    // 实时刷新:用 FileObserver(inotify 内核级)监听当前目录——AI 的写工具(进程内或 shell 子进程)
    // 改文件都会触发事件,防抖 400ms 后自动刷新列表,做到「AI 一边改,我一边看」无需重进。
    // 监听目标跟随 currentDir:切换目录时旧 observer 停止、新 observer 启动。
    DisposableEffect(currentDir) {
        val interesting = android.os.FileObserver.CREATE or android.os.FileObserver.DELETE or
            android.os.FileObserver.MOVED_FROM or android.os.FileObserver.MOVED_TO or
            android.os.FileObserver.MODIFY or android.os.FileObserver.CLOSE_WRITE or
            android.os.FileObserver.ATTRIB or android.os.FileObserver.DELETE_SELF
        val observer = object : android.os.FileObserver(currentDir.absolutePath, interesting) {
            private var debounceScheduled = false
            override fun onEvent(event: Int, path: String?) {
                if (path == null || path == ".") return
                if (event and interesting == 0) return
                if (debounceScheduled) return
                debounceScheduled = true
                pollKick.set(true)
                scope.launch {
                    kotlinx.coroutines.delay(400)
                    debounceScheduled = false
                    refresh()
                }
            }
        }
        observer.startWatching()
        onDispose { observer.stopWatching() }
    }

    // 全库搜索:防抖 300ms;非空时从当前目录递归按名匹配(IO 线程,遍历目录数与结果数有上限防拖死)
    LaunchedEffect(searchQuery, currentDir) {
        val q = searchQuery.trim()
        if (q.isEmpty()) { searchResults = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(300)
        val base = currentDir
        val results = withContext(Dispatchers.IO) {
            val out = ArrayList<WsEntry>()
            val queue = ArrayDeque<File>(); queue.add(base)
            var visited = 0
            while (queue.isNotEmpty() && out.size < WS_SEARCH_MAX_RESULTS && visited < WS_SEARCH_MAX_DIRS) {
                val d = queue.removeFirst(); visited++
                val kids = runCatching { d.listFiles() ?: emptyArray() }.getOrDefault(emptyArray())
                for (k in kids) {
                    if (out.size < WS_SEARCH_MAX_RESULTS && k.name.contains(q, ignoreCase = true)) {
                        out.add(WsEntry(k, k.name, k.isDirectory, if (k.isFile) k.length() else 0L, k.lastModified()))
                    }
                    if (k.isDirectory) queue.add(k)
                }
            }
            out.sortedWith(compareByDescending<WsEntry> { it.isDir }.thenBy { it.name.lowercase() })
        }
        // 期间用户改了词/切了目录就丢弃本次结果
        if (base == currentDir && q == searchQuery.trim()) { searchResults = results; searching = false }
    }

    val rel = relPath(currentDir)
    val segments: List<String> = if (rel.isEmpty()) emptyList() else rel.split("/")

        // 工作区是全屏不透明滑入面板(覆盖在聊天页之上),本身已阻断点击穿透,无需再叠 clickable。
        // 关键:若在此叠 clickable,会抢先消费下拉手势,导致下方 LazyColumn 收不到拖动,
        // 下拉刷新(PullToRefreshBox 依赖嵌套滚动的越界)直接失效——故此处刻意不加任何手势拦截。
        // 状态栏区域的白色由顶栏 Row 的背景负责(其 statusBarsPadding 在 background 之后,背景铺满含状态栏)。
        Column(
            Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg())
        ) {
        // ═══ 顶栏 ═══
        Row(
            Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) { onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = com.aurora.chat.ui.theme.appTextPrimary()) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("工作区", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Text(
                    if (rel.isEmpty()) "ai_files 根目录" else "ai_files/$rel",
                    fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint(), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            // 回收站（删除的文件统一移入应用私有目录内的回收站;点击进入查看/恢复/清空）
            Box(
                Modifier.size(36.dp)
                    .clickable(interactionSource = noRipple, indication = null) { showRecycleBin = true },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Delete, contentDescription = "回收站", tint = Color(0xFFDC2626), modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(4.dp))
            // 上传（从系统文件管理器选文件，拷进当前目录）
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) { uploadLauncher.launch(arrayOf("*/*")) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.UploadFile, contentDescription = "上传", tint = Color(0xFF059669), modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(4.dp))
            // 新建（合并 文件 / 文件夹，弹窗内选择类型）
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) { newName = ""; newIsFolder = false; showCreateDialog = true },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.NoteAdd, contentDescription = "新建", tint = WsBlue, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(4.dp))
            Spacer(Modifier.width(4.dp))
            // 三点菜单（更多操作;菜单项由列表驱动,日后新增项直接往 topMenuItems 追加）
            Box {
                Box(
                    Modifier.size(36.dp)
                        .clickable(interactionSource = noRipple, indication = null) { topMenuOpen = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.size(22.dp)) }
                DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                    topMenuItems.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label, fontSize = 14.sp) },
                            leadingIcon = { Icon(item.icon, null, Modifier.size(18.dp), tint = item.tint) },
                            onClick = { topMenuOpen = false; item.onClick() }
                        )
                    }
                }
            }
        }
        // ── 粘贴(剪贴板有内容时显示):把复制/移动的文件落到当前目录 ──
        if (clipboard != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) { doPaste() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴", tint = WsBlue, modifier = Modifier.size(22.dp)) }
        }
        // ═══ 多选模式批量操作条 ═══
        if (multiSelectMode) {
            Column(
                Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSelectedBg()).padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // 第一行:选中统计 + 退出
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = WsBlue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("已选 ${selectedFiles.size} 项", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { multiSelectMode = false; selectedFiles = emptySet() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("取消", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                }
                // 第二行:全部批量操作(FlowRow 自动换行,空间不足时不再挤掉功能)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 全选(含文件夹)
                    val all = entries.map { it.f }
                    TextButton(enabled = all.isNotEmpty(), onClick = {
                        selectedFiles = if (selectedFiles.size == all.size) emptySet() else all.toSet()
                    }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(if (selectedFiles.size == all.size && all.isNotEmpty()) "取消全选" else "全选", fontSize = 13.sp, color = Color(0xFF1E40AF))
                    }
                    // 分享到外部应用(QQ / 微信 / 邮件等);选中的文件夹会自动压缩成 ZIP
                    TextButton(enabled = selectedFiles.isNotEmpty(), onClick = { shareToExternal(selectedFiles) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Icon(Icons.Filled.Share, null, Modifier.size(16.dp), tint = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                        Spacer(Modifier.width(4.dp))
                        Text("分享", fontSize = 13.sp, color = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                    }
                    // 复制
                    TextButton(enabled = selectedFiles.isNotEmpty(), onClick = { doCopyBatch(selectedFiles) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Icon(Icons.Filled.CopyAll, null, Modifier.size(16.dp), tint = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                        Spacer(Modifier.width(4.dp))
                        Text("复制", fontSize = 13.sp, color = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                    }
                    // 剪切
                    TextButton(enabled = selectedFiles.isNotEmpty(), onClick = { doCutBatch(selectedFiles) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Icon(Icons.Filled.ContentCut, null, Modifier.size(16.dp), tint = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                        Spacer(Modifier.width(4.dp))
                        Text("剪切", fontSize = 13.sp, color = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                    }
                    // 压缩
                    TextButton(enabled = selectedFiles.isNotEmpty(), onClick = { doZipBatch(selectedFiles) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Icon(Icons.Filled.Archive, null, Modifier.size(16.dp), tint = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                        Spacer(Modifier.width(4.dp))
                        Text("压缩", fontSize = 13.sp, color = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                    }
                    // 批量下载
                    TextButton(enabled = selectedFiles.isNotEmpty(), onClick = { treePickerLauncher.launch(null) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Icon(Icons.Filled.Download, null, Modifier.size(16.dp), tint = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                        Spacer(Modifier.width(4.dp))
                        Text("下载", fontSize = 13.sp, color = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                    }
                    // 批量删除
                    TextButton(enabled = selectedFiles.isNotEmpty(), onClick = {
                        deleteTarget = null // 单个的不用
                        deleteBatchConfirm = true
                    }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Icon(Icons.Filled.DeleteOutline, null, Modifier.size(16.dp), tint = if (selectedFiles.isNotEmpty()) WsRed else Color(0xFFD1D5DB))
                        Spacer(Modifier.width(4.dp))
                        Text("删除", fontSize = 13.sp, color = if (selectedFiles.isNotEmpty()) WsRed else Color(0xFFD1D5DB))
                    }
                }
            }
        }
        // ═══ 面包屑路径 ═══
        // 固定单行高度(32dp) + 横向滚动:路径再深也不撑高面板,超宽部分左右滑动查看。
        // 每段文字不省略号截断,保持完整宽度供滑动露出。
        Box(
            Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .height(32.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "根目录", fontSize = 12.sp,
                    color = if (segments.isEmpty()) WsBlue else com.aurora.chat.ui.theme.appTextSecondary(),
                    fontWeight = if (segments.isEmpty()) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable(interactionSource = noRipple, indication = null) { navigateTo(root) }
                )
                segments.forEachIndexed { idx, seg ->
                    Text("  /  ", fontSize = 12.sp, color = Color(0xFFD1D5DB))
                    val isLast = idx == segments.lastIndex
                    Text(
                        seg, fontSize = 12.sp,
                        color = if (isLast) WsBlue else com.aurora.chat.ui.theme.appTextSecondary(),
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.clickable(interactionSource = noRipple, indication = null) {
                            navigateTo(File(root.absolutePath + "/" + segments.take(idx + 1).joinToString("/")))
                        }
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

        // ═══ 搜索栏(分割线下方):空=浏览当前目录;输入即从当前目录全库递归按名搜索 ═══
        Row(
            Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface()).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = "搜索", tint = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(WsBlue),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (searchQuery.isEmpty()) Text("搜索文件/文件夹(全工作区)", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        inner()
                    }
                }
            )
            if (searchQuery.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close, contentDescription = "清空搜索", tint = com.aurora.chat.ui.theme.appTextHint(),
                    modifier = Modifier.size(18.dp)
                        .clickable(interactionSource = noRipple, indication = null) { searchQuery = "" }
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

        // ═══ 文件列表(支持下拉刷新,与聊天/社区等板块同一套 Material3 交互) ═══
        // 列表数据源/分页状态必须在 PullToRefreshBox 之外计算,不能塞进 content lambda——
        // 否则每次下拉刷新重组 PullToRefreshBox 都会重算并重置这些状态,下拉刷新"卡住"无响应。
        val showSearch = searchQuery.isNotBlank()
        val displayList = if (showSearch) searchResults else entries
        val pagedList = if (visibleCount >= displayList.size) displayList else displayList.take(visibleCount)
        // 数据源(切目录/搜索词)变化时重置分页
        LaunchedEffect(displayList) { visibleCount = WS_PAGE_FIRST }

    // ── 从文件引用切入：定位目标文件（逐层直达所在目录，可选自动打开编辑器/查看）──
    LaunchedEffect(locateName, root) {
        val name = locateName ?: return@LaunchedEffect
        val raw = name.trim('`', '"', '\'', ' ')
        if (raw.isEmpty()) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            // root 规范路径统一去尾部斜杠，避免 rootPath + File.separator 拼出双斜杠导致前缀校验误拒
            val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath).trimEnd('/', '\\')
            // 0) 引用名规范化：剥 ./ 前缀；若带了工作区根目录名前缀（如 ai_files/xxx）则剥掉，
            //    避免把工作区内相对路径误拼成 root/ai_files/xxx；/ 开头按绝对路径解析
            var clean = raw.removePrefix("./").removePrefix("\\")
            while (clean.startsWith(root.name + "/") || clean.startsWith(root.name + "\\")) {
                clean = clean.drop(root.name.length + 1)
            }
            if (clean.isEmpty()) return@withContext root
            val direct = if (clean.startsWith("/")) File(clean) else File(root, clean)
            val insideRoot: (File) -> Boolean = { f ->
                val p = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath).trimEnd('/', '\\')
                p == rootPath || p.startsWith(rootPath + File.separator)
            }
            val directCanon = runCatching { direct.canonicalFile }.getOrDefault(direct)
            // 1) 相对路径直解（防 `../` 越界：解析后必须仍在 root 内）
            if (directCanon.exists() && insideRoot(directCanon)) return@withContext directCanon
            // 2) 递归按名搜索：用引用名的末段（兼容带目录前缀/大小写差异的写法），
            //    目录也参与匹配；精确优先、忽略大小写兜底；限制深度防卡
            val seg = clean.substringAfterLast('/').substringAfterLast('\\').trim()
            if (seg.isEmpty()) return@withContext null
            // 产物行常带描述（如「已生成文件：report.md」「report.md 位于工作区」），
            // 提取冒号后/首个空白前的片段作为补充候选，扩大命中面
            val segCandidates = buildList {
                add(seg)
                seg.substringAfter('：').substringAfter(':').trim().takeIf { it.isNotEmpty() && it != seg }?.let { add(it) }
                seg.substringBefore(' ').substringBefore('\t').trim().takeIf { it.isNotEmpty() && it != seg }?.let { add(it) }
                // 形态「已生成 report.md」「文件 report.md」：路径在末尾，取最后一段
                seg.substringAfterLast(' ').substringAfterLast('\t').trim().takeIf { it.isNotEmpty() && it != seg }?.let { add(it) }
            }.distinct()
            var hit: File? = null
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.add(root to 0)
            while (stack.isNotEmpty() && hit == null) {
                val (dir, depth) = stack.removeLast()
                if (depth > 20) continue
                val list = dir.listFiles() ?: continue
                for (f in list) {
                    val nameOk = segCandidates.any { c ->
                        f.name == c || f.nameWithoutExtension == c ||
                            f.name.equals(c, ignoreCase = true) || f.nameWithoutExtension.equals(c, ignoreCase = true)
                    }
                    if (f.isDirectory) {
                        if (depth < 20) stack.add(f to depth + 1)
                        if (nameOk) { hit = f; break }
                    } else if (nameOk) {
                        hit = f
                        break
                    }
                }
            }
            hit
        }
        if (found == null) {
            android.util.Log.d(
                "WsLocate",
                "未找到 locateName=$name root=${runCatching { root.absolutePath }.getOrDefault("")}"
            )
            toast("工作区中未找到「$raw」")
            return@LaunchedEffect
        }
        highlightFileName = found.name
        if (found.isDirectory) {
            navigateTo(found)
        } else {
            navigateTo(found.parentFile ?: root)
            locateHandled = true
            if (locateOpen) {
                when {
                    isImageFileWs(found.name) -> viewingImage = found
                    isVideoFileWs(found.name) -> openWithSystem(found, "video/*")
                    isAudioFileWs(found.name) -> openWithSystem(found, "audio/*")
                    isPdfFileWs(found.name) -> openWithSystem(found, "application/pdf")
                    found.extension.lowercase() == "apk" -> installTarget = found
                    isTextFileWs(found) -> openEditor(found)
                    else -> openWithSystem(found)
                }
            }
        }
    }

    // ── 定位到高亮文件：自动滚动到可视区（每目录仅滚动一次）──
    LaunchedEffect(entries, searchResults, highlightFileName, visibleCount, locateHandled) {
        if (!locateHandled) return@LaunchedEffect
        val name = highlightFileName ?: return@LaunchedEffect
        val display = if (showSearch) searchResults else entries
        val idx = display.indexOfFirst { it.name == name }
        if (idx < 0) return@LaunchedEffect
        if (idx >= visibleCount) {
            visibleCount = idx + 1
            return@LaunchedEffect
        }
        listState.scrollToItem(idx)
        locateHandled = false
    }
        val busy = if (showSearch) searching else loading
        // 列表区直接是 LazyColumn。工作区已有 2s 轮询自动刷新(检测目录 mtime / pollKick / 空目录),
        // 不需要手动下拉刷新,移除 PullToRefreshBox 以消除"卡住"问题。
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (busy && displayList.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = WsBlue, strokeWidth = 3.dp)
                    }
                }
            } else if (displayList.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (showSearch) Icons.Filled.SearchOff else Icons.Filled.FolderOpen,
                                contentDescription = null, tint = Color(0xFFD1D5DB), modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (showSearch) "没有匹配「${searchQuery.trim()}」的文件" else "工作区是空的",
                                fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()
                            )
                            Text(
                                if (showSearch) "换个关键词试试,搜索范围是当前目录及全部子目录" else "点击右上角按钮新建文件或文件夹",
                                fontSize = 12.sp, color = Color(0xFFC4C8CF)
                            )
                        }
                    }
                }
            }
            items(pagedList, key = { it.f.absolutePath }) { e ->
                            var menuOpen by remember { mutableStateOf(false) }
                            Card(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(com.aurora.chat.ui.theme.appSurface())
                                    .combinedClickable(
                                        interactionSource = noRipple, indication = null,
                                        onClick = {
                                            if (multiSelectMode) {
                                                // 多选模式:文件/文件夹统一切换选中(文件夹参与复制/剪切/压缩/删除)
                                                selectedFiles = if (selectedFiles.contains(e.f)) selectedFiles - e.f else selectedFiles + e.f
                                                return@combinedClickable
                                            }
                                            // 选择模式:文件夹照常进入下级,文件直接交给当前对话(不打开预览)
                                            if (pickMode && !e.isDir && onPickToChat != null) {
                                                onPickToChat(relPath(e.f))
                                                return@combinedClickable
                                            }
                                            when {
                                                e.isDir -> navigateTo(e.f)
                                                isImageFileWs(e.name) -> viewingImage = e.f
                                                isVideoFileWs(e.name) -> openWithSystem(e.f, "video/*")
                                                isAudioFileWs(e.name) -> openWithSystem(e.f, "audio/*")
                                                isPdfFileWs(e.name) -> openWithSystem(e.f, "application/pdf")
                                                e.f.extension.lowercase() == "apk" -> installTarget = e.f
                                                // HTML 点击进编辑器(与其它文本一致)
                                                isTextFileWs(e.f) -> openEditor(e.f)
                                                else -> openWithSystem(e.f)
                                            }
                                        },
                                        onLongClick = {
                                            if (!multiSelectMode) {
                                                multiSelectMode = true
                                                selectedFiles = setOf(e.f)
                                            } else {
                                                selectedFiles = if (selectedFiles.contains(e.f)) selectedFiles - e.f else selectedFiles + e.f
                                            }
                                        }
                                    ),
                                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (highlightFileName == e.name)
                                                Modifier.clip(RoundedCornerShape(10.dp)).background(com.aurora.chat.ui.theme.appSelectedBg())
                                            else Modifier
                                        )
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 多选模式:左边显示选中状态(文件夹同样参与多选)
                                    if (multiSelectMode) {
                                        Icon(
                                            imageVector = if (selectedFiles.contains(e.f)) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (selectedFiles.contains(e.f)) WsBlue else com.aurora.chat.ui.theme.appTextHint(),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Icon(
                                        imageVector = if (e.isDir) Icons.Filled.Folder else getFileIconWs(e.name),
                                        contentDescription = null,
                                        tint = if (e.isDir) WsBlue else getFileIconColorWs(e.name),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(e.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        // 副标题全部读快照字段,渲染期零磁盘调用;搜索模式展示所在相对目录
                                        Text(
                                            when {
                                                showSearch -> "位于 " + relPath(e.f).substringBeforeLast('/', "").ifEmpty { "根目录" }
                                                e.isDir -> "文件夹"
                                                else -> "${formatSizeWs(e.size)} · ${timeFmt.format(Date(e.mtime))}"
                                            },
                                            fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint(), maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    // 多选模式下隐藏 MoreVert 菜单(避免和批量操作混淆)
                                    if (!multiSelectMode) Box {
                                        Icon(
                                            Icons.Filled.MoreVert, contentDescription = "更多",
                                            tint = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.size(18.dp)
                                                .clickable(interactionSource = noRipple, indication = null) { menuOpen = true }
                                        )
                                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                            // 分享到外部应用(QQ / 微信 / 邮件等):不走应用内部;文件夹会先压缩成 ZIP
                                            DropdownMenuItem(
                                                text = { Text("分享", fontSize = 14.sp) },
                                                leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(18.dp), tint = WsBlue) },
                                                onClick = { menuOpen = false; shareToExternal(listOf(e.f)) }
                                            )
                                            // 发送到当前对话:只把该文件挂起来(不直接发送),由用户点发送才发出
                                            if (!e.isDir && onPickToChat != null) {
                                                DropdownMenuItem(
                                                    text = { Text(referenceLabel, fontSize = 14.sp) },
                                                    leadingIcon = {
                                                        Icon(
                                                            Icons.AutoMirrored.Filled.Send, null,
                                                            Modifier.size(18.dp), tint = WsBlue
                                                        )
                                                    },
                                                    onClick = {
                                                        menuOpen = false
                                                        onPickToChat(relPath(e.f))
                                                        onBack()
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("下载到...", fontSize = 14.sp) },
                                                leadingIcon = { Icon(Icons.Filled.Download, null, Modifier.size(18.dp), tint = WsBlue) },
                                                onClick = {
                                                    menuOpen = false
                                                    if (e.isDir) { toast("文件夹暂不支持下载,请在多选模式下批量选文件下载"); return@DropdownMenuItem }
                                                    selectedFiles = setOf(e.f); downloadTarget = e.f; treePickerLauncher.launch(null)
                                                }
                                            )
                                            // 解压(仅 ZIP 文件)
                                            if (!e.isDir && e.name.lowercase().endsWith(".zip")) {
                                                DropdownMenuItem(
                                                    text = { Text("解压", fontSize = 14.sp) },
                                                    leadingIcon = { Icon(Icons.Filled.Unarchive, null, Modifier.size(18.dp), tint = WsBlue) },
                                                    onClick = { menuOpen = false; doUnzip(e.f) }
                                                )
                                            }
                                            // 压缩成 ZIP(文件 / 文件夹)
                                            DropdownMenuItem(
                                                text = { Text("压缩成 ZIP", fontSize = 14.sp) },
                                                leadingIcon = { Icon(Icons.Filled.Archive, null, Modifier.size(18.dp), tint = WsBlue) },
                                                onClick = { menuOpen = false; doZip(e.f) }
                                            )
                                            // 复制:记录到剪贴板,到目标目录点顶部「粘贴」
                                            DropdownMenuItem(
                                                text = { Text("复制", fontSize = 14.sp) },
                                                leadingIcon = { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp), tint = com.aurora.chat.ui.theme.appTextSecondary()) },
                                                onClick = { menuOpen = false; doCopy(e.f); toast("已复制,到目标目录点顶部「粘贴」") }
                                            )
                                            // 剪切:记录到剪贴板,到目标目录点顶部「粘贴」
                                            DropdownMenuItem(
                                                text = { Text("剪切", fontSize = 14.sp) },
                                                leadingIcon = { Icon(Icons.Filled.ContentCut, null, Modifier.size(18.dp), tint = Color(0xFF2563EB)) },
                                                onClick = { menuOpen = false; doCut(e.f); toast("已剪切,到目标目录点顶部「粘贴」") }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("重命名", fontSize = 14.sp) },
                                                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null, Modifier.size(18.dp), tint = com.aurora.chat.ui.theme.appTextSecondary()) },
                                                onClick = { menuOpen = false; renameTarget = e.f; renameName = e.name }
                                            )
                                            // 详情/属性
                                            DropdownMenuItem(
                                                text = { Text("详情", fontSize = 14.sp) },
                                                leadingIcon = { Icon(Icons.Filled.Info, null, Modifier.size(18.dp), tint = com.aurora.chat.ui.theme.appTextSecondary()) },
                                                onClick = { menuOpen = false; detailTarget = e.f }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("删除", fontSize = 14.sp) },
                                                leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, Modifier.size(18.dp), tint = WsRed) },
                                                onClick = { menuOpen = false; deleteTarget = e.f }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // 分页续载 footer:滚到底部出现在视口时自动追加下一页(LazyColumn 按需组合,进视口才触发)
                        if (pagedList.size < displayList.size) {
                            item(key = "ws_load_more") {
                                LaunchedEffect(displayList, visibleCount) {
                                    kotlinx.coroutines.delay(60)
                                    visibleCount = (visibleCount + WS_PAGE_STEP).coerceAtMost(displayList.size)
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(Modifier.size(16.dp), color = WsBlue, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("已加载 ${pagedList.size} / ${displayList.size} 项", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                            }
                        }
        }
        }

    // ═══════ 新建弹窗（合并 文件 / 文件夹）═══════
    if (showCreateDialog) {
        Dialog(
            onDismissRequest = { showCreateDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(28.dp)) {
                Column {
                    Text("新建", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("输入名称，并选择新建文件还是文件夹", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(value = newName, onValueChange = { newName = it },
                        placeholder = { Text("名称（含扩展名，如 notes.md）", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (validName(newName)) { if (newIsFolder) createNewFolder() else createNewFile() } }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = WsBlue, cursorColor = WsBlue))
                    Spacer(Modifier.height(16.dp))
                    // 类型切换:分段单选控件(选中段填充品牌蓝,未选中保持描边风格),与下方"取消/创建"普通按钮形成明显区分
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = newIsFolder,
                            onClick = { newIsFolder = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            modifier = Modifier.weight(1f),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = WsBlue,
                                activeContentColor = Color.White,
                                inactiveContainerColor = Color.White,
                                inactiveContentColor = com.aurora.chat.ui.theme.appTextSecondary()
                            )
                        ) { Text("文件夹") }
                        SegmentedButton(
                            selected = !newIsFolder,
                            onClick = { newIsFolder = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            modifier = Modifier.weight(1f),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = WsBlue,
                                activeContentColor = Color.White,
                                inactiveContainerColor = Color.White,
                                inactiveContentColor = com.aurora.chat.ui.theme.appTextSecondary()
                            )
                        ) { Text("文件") }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { showCreateDialog = false }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = com.aurora.chat.ui.theme.appTextSecondary())) { Text("取消") }
                        Button(onClick = { if (newIsFolder) createNewFolder() else createNewFile() }, modifier = Modifier.weight(1f),
                            enabled = newName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = WsBlue)) { Text("创建") }
                    }
                }
            }
        }
    }

    // ═══════ 删除确认弹窗 ═══════
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!deleteLoading) deleteTarget = null },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = WsRed, modifier = Modifier.size(28.dp)) },
            title = { Text("确认删除", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("确定要删除${if (target.isDirectory) "文件夹（含全部内容）" else "文件"}", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEF2F2)).padding(12.dp)) {
                        Text("\"${target.name}\"", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF991B1B))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("删除后将移入回收站，可在回收站中恢复。", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            },
            confirmButton = {
                Button(onClick = { confirmDelete() }, enabled = !deleteLoading, colors = ButtonDefaults.buttonColors(containerColor = WsRed)) {
                    if (deleteLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text("删除", color = Color.White)
                }
            },
            dismissButton = { TextButton(onClick = { if (!deleteLoading) deleteTarget = null }) { Text("取消") } }
        )
    }

    // ═══════ 重命名弹窗 ═══════
    renameTarget?.let { target ->
        Dialog(
            onDismissRequest = { renameTarget = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(28.dp)) {
                Column {
                    Text("重命名", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(target.name, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(value = renameName, onValueChange = { renameName = it },
                        placeholder = { Text("新名称", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (validName(renameName)) confirmRename() }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = WsBlue, cursorColor = WsBlue))
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { renameTarget = null }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = com.aurora.chat.ui.theme.appTextSecondary())) { Text("取消") }
                        Button(onClick = { confirmRename() }, modifier = Modifier.weight(1f),
                            enabled = renameName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = WsBlue)) { Text("确认") }
                    }
                }
            }
        }
    }

    // ═══════ 全屏编辑器（统一使用 FileEditorHost 模块）═══════
    if (editingFile != null) {
        FileEditorHost(
            title = editingFile?.name ?: "",
            subtitle = "工作区文件",
            initialContent = editingContent,
            isLoading = editLoading,
            onSave = { text, closeAfter -> saveEditor(text, closeAfter) },
            onDismiss = { editingFile = null; editingContent = "" },
            rootFile = editingFile
        )
    }

    // ═══════ 全屏图片查看器（点图片=直接查看,绝不进文本编辑器）═══════
    viewingImage?.let { img ->
        val imgZoom = remember(img.absolutePath) { mutableFloatStateOf(1f) }
        BackHandler { viewingImage = null }
        Box(
            Modifier.fillMaxSize().background(Color(0xFF111827))
                .clickable(interactionSource = noRipple, indication = null) { viewingImage = null }
                .statusBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) { viewingImage = null },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(img.name, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appDivider(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${(imgZoom.floatValue * 100).toInt()}%", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
            var fileAspect by remember(img.absolutePath) { mutableStateOf(0f) }
            // 缩放挂在容器上（原来是挂在图片自身的 modifier 上），这样区域解码高清层
            // 可以和预览图共用同一套变换：放大后从原文件取 1:1 像素，长截图不再糊。
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 52.dp)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            imgZoom.floatValue = (imgZoom.floatValue * zoom).coerceIn(0.5f, 6f)
                        }
                    }
                    .graphicsLayer {
                        scaleX = imgZoom.floatValue
                        scaleY = imgZoom.floatValue
                    }
            ) {
                coil.compose.AsyncImage(
                    model = img,
                    contentDescription = "图片查看",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    onSuccess = { res ->
                        val d = res.result.drawable
                        if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                            fileAspect = d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat()
                        }
                    }
                )
                com.aurora.chat.ui.chat.media.RegionZoomOverlay(
                    url = img.absolutePath,
                    aspect = fileAspect,
                    scale = imgZoom.floatValue,
                    offset = androidx.compose.ui.geometry.Offset.Zero,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // ═══════ APK 安装确认弹窗（点 APK=询问是否安装,绝不进文本编辑器）═══════
    installTarget?.let { apk ->
        BackHandler(enabled = !installLaunching) { installTarget = null }
        AlertDialog(
            onDismissRequest = { if (!installLaunching) installTarget = null },
            icon = { Icon(Icons.Filled.Archive, contentDescription = null, tint = WsBlue, modifier = Modifier.size(28.dp)) },
            title = { Text("安装应用", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("是否要安装这个 APK 文件？", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appSelectedBg()).padding(12.dp)) {
                        Text("\"${apk.name}\"", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = WsBlue)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("大小: ${formatSizeWs(apk.length())}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            },
            confirmButton = {
                Button(onClick = { launchInstall(apk) }, enabled = !installLaunching, colors = ButtonDefaults.buttonColors(containerColor = WsBlue)) {
                    if (installLaunching) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text("安装", color = Color.White)
                }
            },
            dismissButton = { TextButton(onClick = { if (!installLaunching) installTarget = null }) { Text("取消") } }
        )
    }

    // ═══════ 通用文件操作进度(解压/压缩/复制/移动) ═══════
    if (opLoading) {
        Dialog(
            onDismissRequest = { },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
        ) {
            Box(
                Modifier.width(240.dp).clip(RoundedCornerShape(20.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), color = WsBlue, strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(opHint, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        }
    }

    // ═══════ 批量删除确认弹窗(多选模式下) ═══════
    if (deleteBatchConfirm && selectedFiles.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteBatchConfirm = false },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = WsRed, modifier = Modifier.size(28.dp)) },
            title = { Text("批量删除确认", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("确定要删除选中的 ${selectedFiles.size} 个项目？", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEF2F2)).padding(12.dp)) {
                        Column {
                            selectedFiles.take(5).forEach { f ->
                                Text("· ${f.name}", fontSize = 13.sp, color = Color(0xFF991B1B), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            if (selectedFiles.size > 5) Text("· ... 还有 ${selectedFiles.size - 5} 个", fontSize = 12.sp, color = Color(0xFF991B1B))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("删除后将移入回收站，可在回收站中恢复。", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            },
            confirmButton = {
                Button(onClick = {
                    // 双保险：确认弹窗打开后若文件已被外部改名/删除，剔除已失效路径再执行
                    val toDelete = selectedFiles.filter { it.exists() }
                    deleteBatchConfirm = false
                    multiSelectMode = false
                    selectedFiles = emptySet()
                    scope.launch(Dispatchers.IO) {
                        var ok = 0
                        for (f in toDelete) {
                            // 统一移入回收站(保留原相对路径,便于恢复)
                            if (moveToRecycleBin(f)) ok++
                        }
                        withContext(Dispatchers.Main) {
                            toast(if (ok == toDelete.size) "已移入回收站 $ok 个" else "成功 $ok / ${toDelete.size} 个")
                            refresh()
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = WsRed)) { Text("删除", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { deleteBatchConfirm = false }) { Text("取消") } }
        )
    }

    // ═══════ 文件详情 / 属性弹窗 ═══════
    detailTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { detailTarget = null },
            icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = WsBlue, modifier = Modifier.size(28.dp)) },
            title = { Text("文件详情", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    WsDetailRow("名称", f.name)
                    WsDetailRow("类型", if (f.isDirectory) "文件夹" else (f.extension.ifBlank { "文件" }))
                    WsDetailRow("大小", if (f.isDirectory) "${f.listFiles()?.size ?: 0} 项" else formatSizeWs(f.length()))
                    WsDetailRow("修改时间", timeFmt.format(Date(f.lastModified())))
                    WsDetailRow("路径", relPath(f).ifEmpty { "ai_files 根目录" })
                }
            },
            confirmButton = { TextButton(onClick = { detailTarget = null }) { Text("关闭") } }
        )
    }



    // ═══════ 回收站页面(全屏覆盖在项目文件列表之上:列出已删除文件,支持恢复/清空) ═══════
    if (showRecycleBin) {
        WsRecycleBinScreen(
            recycleDir = recycleBinDir,
            rootDir = root,
            onBack = { showRecycleBin = false },
            toast = { toast(it) }
        )
    }
}

/** 按扩展名取文件图标(与 SandboxFileBrowser 同思路) */
private fun getFileIconWs(name: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    name.endsWith(".md", true) || name.endsWith(".txt", true) -> Icons.Filled.Description
    name.endsWith(".kt", true) || name.endsWith(".java", true) || name.endsWith(".py", true) ||
        name.endsWith(".js", true) || name.endsWith(".ts", true) || name.endsWith(".go", true) ||
        name.endsWith(".php", true) ||
        name.endsWith(".html", true) || name.endsWith(".css", true) || name.endsWith(".json", true) ||
        name.endsWith(".xml", true) || name.endsWith(".sh", true) -> Icons.Filled.Code
    name.endsWith(".png", true) || name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) ||
        name.endsWith(".gif", true) || name.endsWith(".webp", true) || name.endsWith(".bmp", true) ||
        name.endsWith(".heic", true) || name.endsWith(".heif", true) -> Icons.Filled.Image
    name.endsWith(".mp4", true) || name.endsWith(".mov", true) || name.endsWith(".avi", true) ||
        name.endsWith(".mkv", true) || name.endsWith(".webm", true) || name.endsWith(".3gp", true) ||
        name.endsWith(".mpg", true) || name.endsWith(".mpeg", true) || name.endsWith(".flv", true) -> Icons.Filled.VideoLibrary
    name.endsWith(".mp3", true) || name.endsWith(".wav", true) || name.endsWith(".ogg", true) ||
        name.endsWith(".flac", true) || name.endsWith(".aac", true) || name.endsWith(".m4a", true) -> Icons.Filled.MusicNote
    name.endsWith(".pdf", true) -> Icons.Filled.PictureAsPdf
    name.endsWith(".zip", true) || name.endsWith(".apk", true) || name.endsWith(".rar", true) ||
        name.endsWith(".7z", true) -> Icons.Filled.Archive
    else -> Icons.Filled.InsertDriveFile
}

private fun getFileIconColorWs(name: String): Color = when {
    name.endsWith(".md", true) || name.endsWith(".txt", true) -> Color(0xFF2563EB)
    name.endsWith(".kt", true) || name.endsWith(".java", true) || name.endsWith(".py", true) ||
        name.endsWith(".js", true) || name.endsWith(".ts", true) || name.endsWith(".go", true) ||
        name.endsWith(".php", true) ||
        name.endsWith(".html", true) || name.endsWith(".css", true) || name.endsWith(".json", true) ||
        name.endsWith(".xml", true) || name.endsWith(".sh", true) -> Color(0xFF059669)
    name.endsWith(".png", true) || name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) ||
        name.endsWith(".gif", true) || name.endsWith(".webp", true) || name.endsWith(".bmp", true) ||
        name.endsWith(".heic", true) || name.endsWith(".heif", true) -> Color(0xFFD97706)
    name.endsWith(".mp4", true) || name.endsWith(".mov", true) || name.endsWith(".avi", true) ||
        name.endsWith(".mkv", true) || name.endsWith(".webm", true) || name.endsWith(".flv", true) -> Color(0xFFDC2626)
    name.endsWith(".mp3", true) || name.endsWith(".wav", true) || name.endsWith(".flac", true) ||
        name.endsWith(".aac", true) || name.endsWith(".m4a", true) -> Color(0xFF9333EA)
    name.endsWith(".pdf", true) -> Color(0xFFB91C1C)
    name.endsWith(".zip", true) || name.endsWith(".apk", true) || name.endsWith(".rar", true) ||
        name.endsWith(".7z", true) -> WsPurple
    else -> com.aurora.chat.ui.theme.appTextSecondary()
}

private fun formatSizeWs(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 复制/移动 剪贴板条目:记录源文件与是否为「剪切」(true=移动,false=复制) */
private data class WsClipboard(val items: List<File>, val isCut: Boolean)

/** 详情弹窗里的「标签 / 值」一行 */
@Composable
private fun WsDetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
        Text(value, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** 顶栏三点菜单项（列表驱动:日后新增项直接往 topMenuItems 追加,无需改 UI 结构） */
private data class WsTopMenuItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: Color,
    val onClick: () -> Unit
)

// ═══════ 回收站页面(全屏):列出已删除文件,支持恢复(还原回原工作区原路径)/清空 ═══════
@Composable
private fun WsRecycleBinScreen(
    recycleDir: File,
    rootDir: File,
    onBack: () -> Unit,
    toast: (String) -> Unit
) {
    // 回收站页独立拦截返回键（后注册优先于主面板 L183/L869/L872），
    // 保证返回只退一层：关闭回收站，不穿透到目录导航或直接关闭整个工作区/会话
    BackHandler(enabled = true) { onBack() }
    val scope = rememberCoroutineScope()
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val noRipple = remember { MutableInteractionSource() }
    var entries by remember { mutableStateOf<List<WsEntry>>(emptyList()) }
    var busyRel by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    suspend fun scan() {
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val out = ArrayList<WsEntry>()
                recycleDir.walkTopDown().forEach { f ->
                    if (f != recycleDir) out.add(WsEntry(f, f.name, f.isDirectory, if (f.isFile) f.length() else 0L, f.lastModified()))
                }
                out
            }.getOrDefault(emptyList())
        }
        entries = list.sortedWith(compareByDescending<WsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }
    LaunchedEffect(Unit) { scan() }

    /** 回收站内文件 → 原工作区相对路径（回收站按原相对路径结构存储,天然保留原位置信息） */
    fun relOf(f: File) = f.absolutePath.removePrefix(recycleDir.absolutePath).trimStart('/')

    /** 目标目录下生成不重名（与工作区 resolveUniqueName 同思路,独立于内部函数） */
    fun uniqueNameIn(base: String, dir: File): String {
        if (!File(dir, base).exists()) return base
        val dot = base.lastIndexOf('.')
        val hasExt = dot > 0 && dot < base.length - 1 && base.indexOf('/') < 0
        val nameNoExt = if (hasExt) base.substring(0, dot) else base
        val ext = if (hasExt) base.substring(dot) else ""
        var i = 1
        var c: String
        do { c = "${nameNoExt}_$i$ext"; i++ } while (File(dir, c).exists())
        return c
    }

    fun restore(item: File) {
        val rel = relOf(item)
        if (rel.isEmpty()) return
        busyRel = rel
        scope.launch(Dispatchers.IO) {
            val target = File(rootDir, rel)
            var finalTarget = target
            var renamed = false
            if (target.exists()) {
                // 原位置已有同名:自动改名恢复并提示
                val parent = target.parentFile ?: rootDir
                finalTarget = File(parent, uniqueNameIn(target.name, parent))
                renamed = true
            }
            finalTarget.parentFile?.mkdirs()
            val ok = runCatching { item.renameTo(finalTarget) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                busyRel = null
                if (ok) {
                    toast(if (renamed) "原位置已有同名,已恢复为 ${finalTarget.name}" else "已恢复 ${item.name}")
                    scope.launch { scan() }
                } else toast("恢复失败")
            }
        }
    }

    fun clearAll() {
        showClearConfirm = false
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { recycleDir.listFiles()?.all { it.deleteRecursively() } ?: true }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                toast(if (ok) "回收站已清空" else "清空失败")
                scope.launch { scan() }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg())) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) { onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = com.aurora.chat.ui.theme.appTextPrimary()) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("回收站", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Text(
                    if (entries.isEmpty()) "暂无已删除文件" else "共 ${entries.size} 项 · 删除后保留原路径信息",
                    fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint(), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (entries.isNotEmpty()) {
                TextButton(onClick = { showClearConfirm = true }) {
                    Icon(Icons.Filled.DeleteSweep, null, Modifier.size(16.dp), tint = WsRed)
                    Spacer(Modifier.width(4.dp))
                    Text("清空", fontSize = 13.sp, color = WsRed)
                }
            }
        }
        // 列表
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.DeleteOutline, null, Modifier.size(48.dp), tint = Color(0xFFD1D5DB))
                    Spacer(Modifier.height(8.dp))
                    Text("回收站是空的", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(entries, key = { it.f.absolutePath }) { e ->
                    val rel = relOf(e.f)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (e.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null,
                            Modifier.size(22.dp), tint = if (e.isDir) WsBlue else com.aurora.chat.ui.theme.appTextSecondary()
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "原位置: ${if (rel.isEmpty()) "ai_files 根目录" else "ai_files/$rel"} · ${timeFmt.format(Date(e.mtime))}",
                                fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint(), maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (busyRel != rel) {
                            TextButton(onClick = { restore(e.f) }) {
                                Icon(Icons.Filled.Restore, null, Modifier.size(16.dp), tint = WsBlue)
                                Spacer(Modifier.width(4.dp))
                                Text("恢复", fontSize = 13.sp, color = WsBlue)
                            }
                        } else {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = WsBlue)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = com.aurora.chat.ui.theme.appDivider())
                }
            }
        }
    }

    // 清空确认弹窗
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = WsRed, modifier = Modifier.size(28.dp)) },
            title = { Text("清空回收站", fontWeight = FontWeight.Bold) },
            text = { Text("确定要永久删除回收站中的 ${entries.size} 个项目？此操作不可恢复。", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary()) },
            confirmButton = { Button(onClick = { clearAll() }, colors = ButtonDefaults.buttonColors(containerColor = WsRed)) { Text("清空", color = Color.White) } },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
        )
    }
}
