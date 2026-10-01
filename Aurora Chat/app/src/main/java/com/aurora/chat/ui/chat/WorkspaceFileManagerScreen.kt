package com.aurora.chat.ui.chat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.aurora.chat.ui.components.CodeEditor
import com.aurora.chat.host.*
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
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceFileManagerScreen(onBack: () -> Unit, onOpenBackup: () -> Unit = {}) {
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

    // ── HTML 应用内预览 ──
    var viewingHtml by remember { mutableStateOf<File?>(null) }

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

    // ── 虚拟主机（site_server）：登录与切换 ──
    var showHostLogin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { HostConfig.loadFromPrefs(ctx) }

    // ── 返回键拦截:多选模式优先退出多选,再触发面板关闭 ──
    BackHandler(enabled = multiSelectMode || deleteBatchConfirm || deleteTarget != null || renameTarget != null || viewingHtml != null || viewingImage != null || opLoading) {
        when {
            multiSelectMode -> { multiSelectMode = false; selectedFiles = emptySet() }
            deleteBatchConfirm -> deleteBatchConfirm = false
            deleteTarget != null -> deleteTarget = null
            renameTarget != null -> renameTarget = null
            viewingHtml != null -> viewingHtml = null
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
                    val files = sorted.filter { !it.isDir }
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
            var okCount = 0
            for (f in targetFiles) {
                if (!f.exists() || !f.isFile) continue
                try {
                    val outDoc = rootDoc.createFile("*/*", f.name)
                    if (outDoc != null) {
                        ctx.contentResolver.openOutputStream(outDoc.uri)?.use { out ->
                            f.inputStream().use { it.copyTo(out) }
                        }
                        okCount++
                    }
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                toast(if (okCount == targetFiles.size) "已下载 $okCount 个文件" else "成功 $okCount / ${targetFiles.size} 个")
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

    fun isHtmlFileWs(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("html", "htm", "mhtml", "xhtml")

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

    fun confirmDelete() {
        val target = deleteTarget ?: return
        deleteLoading = true
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                if (target.isDirectory) target.walkBottomUp().all { it.delete() } else target.delete()
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                deleteLoading = false
                if (ok) { toast("已删除"); deleteTarget = null; scope.launch { refresh() } }
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
    fun doCopy(f: File) { clipboard = WsClipboard(f, false) }

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

    // 剪切到剪贴板(移动),到目标目录后点顶部「粘贴」
    fun doCut(f: File) { clipboard = WsClipboard(f, true) }

    // 粘贴(移动 / 复制)到当前目录
    fun doPaste() {
        val item = clipboard ?: return
        val src = item.src
        opLoading = true; opHint = if (item.isCut) "正在移动…" else "正在复制…"
        scope.launch(Dispatchers.IO) {
            if (!src.exists()) {
                withContext(Dispatchers.Main) { opLoading = false; clipboard = null; toast("源文件已不存在") }
                return@launch
            }
            val sameDir = runCatching { src.parentFile?.canonicalPath == currentDir.canonicalPath }.getOrDefault(false)
            if (item.isCut && sameDir) {
                withContext(Dispatchers.Main) { opLoading = false; toast("已在同一目录"); clipboard = null }
                return@launch
            }
            val dstName = resolveUniqueName(src.name, currentDir)
            val dst = File(currentDir, dstName)
            val ok = if (item.isCut) {
                val moved = runCatching { src.renameTo(dst) }.getOrDefault(false)
                if (moved) true else {
                    val c = copyRecursively(src, dst)
                    if (c) runCatching { if (src.isDirectory) src.deleteRecursively() else src.delete() }.getOrDefault(false) else false
                }
            } else copyRecursively(src, dst)
            withContext(Dispatchers.Main) {
                opLoading = false
                if (ok) {
                    toast(if (item.isCut) "已剪切到当前目录（$dstName）" else "已复制到当前目录（$dstName）")
                    if (item.isCut) clipboard = null
                    refresh()
                } else toast(if (item.isCut) "剪切失败" else "复制失败")
            }
        }
    }

    fun openEditor(f: File) {
        val size = f.length()
        // 只给警告,不再拒绝。大文件(>2MB)读入本身是 IO 问题,编辑器的卡顿由 CodeEditor 内部的高亮/撤销栈降级处理。
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

    // 返回键:先关编辑器,再回上一级目录,最后退面板
    BackHandler(enabled = editingFile == null && currentDir != root) {
        navigateTo(currentDir.parentFile ?: root)
    }
    BackHandler(enabled = editingFile == null && currentDir == root) { onBack() }

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
            Modifier.fillMaxSize().background(Color(0xFFF3F4F6))
        ) {
        // ═══ 顶栏 ═══
        Row(
            Modifier.fillMaxWidth().background(Color.White).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) { onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937)) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("工作区", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Text(
                    if (rel.isEmpty()) "ai_files 根目录" else "ai_files/$rel",
                    fontSize = 11.sp, color = Color(0xFF9CA3AF), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            // 虚拟主机（点击登录；登录后点击在“虚拟主机目录 / 工作区”间切换）
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) {
                        if (!HostSession.loggedIn.value) showHostLogin = true
                        else HostConfig.saveHostMode(!HostSession.inHostMode.value)
                    },
                contentAlignment = Alignment.Center
            ) {
                val hostTint = if (HostSession.inHostMode.value) WsBlue
                    else if (HostSession.loggedIn.value) WsPurple else Color(0xFF9CA3AF)
                Icon(Icons.Filled.Cloud, contentDescription = "虚拟主机", tint = hostTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(4.dp))
            // 备份（点击切入独立全屏「备份与恢复」界面,绝不在本面板内嵌）
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) { onOpenBackup() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Backup, contentDescription = "备份", tint = WsBlue, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(4.dp))
            // 多选
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) {
                        multiSelectMode = !multiSelectMode
                        if (!multiSelectMode) selectedFiles = emptySet()
                    },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.CheckBox, contentDescription = "多选", tint = if (multiSelectMode) WsBlue else Color(0xFF6B7280), modifier = Modifier.size(22.dp)) }
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
            Row(
                Modifier.fillMaxWidth().background(Color(0xFFEFF6FF)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = WsBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("已选 ${selectedFiles.size} 项", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                Spacer(Modifier.weight(1f))
                // 全选
                TextButton(onClick = {
                    val allFiles = entries.filter { !it.isDir }.map { it.f }
                    selectedFiles = if (selectedFiles.size == allFiles.size) emptySet() else allFiles.toSet()
                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(if (selectedFiles.size == entries.filter { !it.isDir }.size && entries.any { !it.isDir }) "取消全选" else "全选", fontSize = 13.sp, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(2.dp))
                // 批量下载
                TextButton(enabled = selectedFiles.isNotEmpty(), onClick = { treePickerLauncher.launch(null) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Icon(Icons.Filled.Download, null, Modifier.size(16.dp), tint = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                    Spacer(Modifier.width(4.dp))
                    Text("下载", fontSize = 13.sp, color = if (selectedFiles.isNotEmpty()) WsBlue else Color(0xFFD1D5DB))
                }
                Spacer(Modifier.width(2.dp))
                // 批量删除
                TextButton(enabled = selectedFiles.isNotEmpty(), onClick = {
                    if (selectedFiles.isNotEmpty()) {
                        deleteTarget = null // 单个的不用
                        deleteBatchConfirm = true
                    }
                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Icon(Icons.Filled.DeleteOutline, null, Modifier.size(16.dp), tint = if (selectedFiles.isNotEmpty()) WsRed else Color(0xFFD1D5DB))
                    Spacer(Modifier.width(4.dp))
                    Text("删除", fontSize = 13.sp, color = if (selectedFiles.isNotEmpty()) WsRed else Color(0xFFD1D5DB))
                }
                Spacer(Modifier.width(4.dp))
                // 退出多选
                TextButton(onClick = { multiSelectMode = false; selectedFiles = emptySet() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("取消", fontSize = 13.sp, color = Color(0xFF6B7280))
                }
            }
        }
        // ═══ 面包屑路径 ═══
        // 固定单行高度(32dp) + 横向滚动:路径再深也不撑高面板,超宽部分左右滑动查看。
        // 每段文字不省略号截断,保持完整宽度供滑动露出。
        Box(
            Modifier.fillMaxWidth().background(Color.White)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .height(32.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "根目录", fontSize = 12.sp,
                    color = if (segments.isEmpty()) WsBlue else Color(0xFF6B7280),
                    fontWeight = if (segments.isEmpty()) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable(interactionSource = noRipple, indication = null) { navigateTo(root) }
                )
                segments.forEachIndexed { idx, seg ->
                    Text("  /  ", fontSize = 12.sp, color = Color(0xFFD1D5DB))
                    val isLast = idx == segments.lastIndex
                    Text(
                        seg, fontSize = 12.sp,
                        color = if (isLast) WsBlue else Color(0xFF6B7280),
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.clickable(interactionSource = noRipple, indication = null) {
                            navigateTo(File(root.absolutePath + "/" + segments.take(idx + 1).joinToString("/")))
                        }
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

        // ═══ 搜索栏(分割线下方):空=浏览当前目录;输入即从当前目录全库递归按名搜索 ═══
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = "搜索", tint = Color(0xFF9CA3AF), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = Color(0xFF1F2937)),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(WsBlue),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (searchQuery.isEmpty()) Text("搜索文件/文件夹(全工作区)", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                        inner()
                    }
                }
            )
            if (searchQuery.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close, contentDescription = "清空搜索", tint = Color(0xFF9CA3AF),
                    modifier = Modifier.size(18.dp)
                        .clickable(interactionSource = noRipple, indication = null) { searchQuery = "" }
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

        // ═══ 文件列表(支持下拉刷新,与聊天/社区等板块同一套 Material3 交互) ═══
        // 列表数据源/分页状态必须在 PullToRefreshBox 之外计算,不能塞进 content lambda——
        // 否则每次下拉刷新重组 PullToRefreshBox 都会重算并重置这些状态,下拉刷新"卡住"无响应。
        val showSearch = searchQuery.isNotBlank()
        val displayList = if (showSearch) searchResults else entries
        val pagedList = if (visibleCount >= displayList.size) displayList else displayList.take(visibleCount)
        // 数据源(切目录/搜索词)变化时重置分页
        LaunchedEffect(displayList) { visibleCount = WS_PAGE_FIRST }
        val busy = if (showSearch) searching else loading
        // 列表区直接是 LazyColumn。工作区已有 2s 轮询自动刷新(检测目录 mtime / pollKick / 空目录),
        // 不需要手动下拉刷新,移除 PullToRefreshBox 以消除"卡住"问题。
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            state = rememberLazyListState(),
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
                                fontSize = 13.sp, color = Color(0xFF9CA3AF)
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
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .combinedClickable(
                                        interactionSource = noRipple, indication = null,
                                        onClick = {
                                            if (multiSelectMode) {
                                                // 多选模式:文件夹不参与批量操作(没法批量导航),文件切换选中
                                                if (e.isDir) { navigateTo(e.f); return@combinedClickable }
                                                selectedFiles = if (selectedFiles.contains(e.f)) selectedFiles - e.f else selectedFiles + e.f
                                                return@combinedClickable
                                            }
                                            when {
                                                e.isDir -> navigateTo(e.f)
                                                isImageFileWs(e.name) -> viewingImage = e.f
                                                isVideoFileWs(e.name) -> openWithSystem(e.f, "video/*")
                                                isAudioFileWs(e.name) -> openWithSystem(e.f, "audio/*")
                                                isPdfFileWs(e.name) -> openWithSystem(e.f, "application/pdf")
                                                e.f.extension.lowercase() == "apk" -> installTarget = e.f
                                                // HTML 点击进编辑器(与其它文本一致),应用内预览仅保留在三点菜单
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
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    // 多选模式:左边显示选中状态(文件夹不参与多选所以不显示)
                                    if (multiSelectMode && !e.isDir) {
                                        Icon(
                                            imageVector = if (selectedFiles.contains(e.f)) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (selectedFiles.contains(e.f)) WsBlue else Color(0xFF9CA3AF),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Icon(
                                        imageVector = if (e.isDir) Icons.Filled.Folder else getFileIconWs(e.name),
                                        contentDescription = null,
                                        tint = if (e.isDir) WsPurple else getFileIconColorWs(e.name),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(e.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        // 副标题全部读快照字段,渲染期零磁盘调用;搜索模式展示所在相对目录
                                        Text(
                                            when {
                                                showSearch -> "位于 " + relPath(e.f).substringBeforeLast('/', "").ifEmpty { "根目录" }
                                                e.isDir -> "文件夹"
                                                else -> "${formatSizeWs(e.size)} · ${timeFmt.format(Date(e.mtime))}"
                                            },
                                            fontSize = 11.sp, color = Color(0xFF9CA3AF), maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    // 多选模式下隐藏 MoreVert 菜单(避免和批量操作混淆)
                                    if (!multiSelectMode) Box {
                                        Icon(
                                            Icons.Filled.MoreVert, contentDescription = "更多",
                                            tint = Color(0xFF9CA3AF), modifier = Modifier.size(18.dp)
                                                .clickable(interactionSource = noRipple, indication = null) { menuOpen = true }
                                        )
                                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                            // HTML 文件:额外加应用内预览选项
                                            if (!e.isDir && isHtmlFileWs(e.name)) {
                                                DropdownMenuItem(
                                                    text = { Text("应用内预览", fontSize = 14.sp) },
                                                    leadingIcon = { Icon(Icons.Filled.Preview, null, Modifier.size(18.dp), tint = Color(0xFF059669)) },
                                                    onClick = { menuOpen = false; viewingHtml = e.f }
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
                                                    leadingIcon = { Icon(Icons.Filled.Unarchive, null, Modifier.size(18.dp), tint = Color(0xFFD97706)) },
                                                    onClick = { menuOpen = false; doUnzip(e.f) }
                                                )
                                            }
                                            // 压缩成 ZIP(文件 / 文件夹)
                                            DropdownMenuItem(
                                                text = { Text("压缩成 ZIP", fontSize = 14.sp) },
                                                leadingIcon = { Icon(Icons.Filled.Archive, null, Modifier.size(18.dp), tint = Color(0xFF7C3AED)) },
                                                onClick = { menuOpen = false; doZip(e.f) }
                                            )
                                            // 复制:记录到剪贴板,到目标目录点顶部「粘贴」
                                            DropdownMenuItem(
                                                text = { Text("复制", fontSize = 14.sp) },
                                                leadingIcon = { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp), tint = Color(0xFF6B7280)) },
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
                                                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null, Modifier.size(18.dp), tint = Color(0xFF6B7280)) },
                                                onClick = { menuOpen = false; renameTarget = e.f; renameName = e.name }
                                            )
                                            // 详情/属性
                                            DropdownMenuItem(
                                                text = { Text("详情", fontSize = 14.sp) },
                                                leadingIcon = { Icon(Icons.Filled.Info, null, Modifier.size(18.dp), tint = Color(0xFF6B7280)) },
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
                                    Text("已加载 ${pagedList.size} / ${displayList.size} 项", fontSize = 12.sp, color = Color(0xFF9CA3AF))
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
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(28.dp)) {
                Column {
                    Text("新建", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("输入名称，并选择新建文件还是文件夹", fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(value = newName, onValueChange = { newName = it },
                        placeholder = { Text("名称（含扩展名，如 notes.md）", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (validName(newName)) { if (newIsFolder) createNewFolder() else createNewFile() } }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = WsBlue, cursorColor = WsBlue))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { newIsFolder = true }, modifier = Modifier.weight(1f),
                            colors = if (newIsFolder) ButtonDefaults.buttonColors(containerColor = WsPurple) else ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6B7280))) { Text("文件夹") }
                        OutlinedButton(onClick = { newIsFolder = false }, modifier = Modifier.weight(1f),
                            colors = if (!newIsFolder) ButtonDefaults.buttonColors(containerColor = WsBlue) else ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6B7280))) { Text("文件") }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { showCreateDialog = false }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6B7280))) { Text("取消") }
                        Button(onClick = { if (newIsFolder) createNewFolder() else createNewFile() }, modifier = Modifier.weight(1f),
                            enabled = newName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = WsBlue)) { Text("创建") }
                    }
                }
            }
        }
    }

    // ═══════ 虚拟主机登录弹窗 ═══════
    if (showHostLogin) {
        Dialog(
            onDismissRequest = { showHostLogin = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var domain by remember { mutableStateOf("") }
            var card by remember { mutableStateOf(HostConfig.cardKey) }
            var password by remember { mutableStateOf("") }
            var loadingLogin by remember { mutableStateOf(false) }
            var err by remember { mutableStateOf<String?>(null) }
            val doLogin = {
                scope.launch {
                    if (domain.isBlank() || card.isBlank() || password.isBlank()) { err = "请填写绑定域名、卡密与密码"; return@launch }
                    loadingLogin = true; err = null
                    val r = HostFiles.activate(ctx, domain, card, password)
                    loadingLogin = false
                    if (r.isSuccess) { showHostLogin = false; HostConfig.saveHostMode(true) }
                    else err = r.exceptionOrNull()?.message ?: "登录失败"
                }
            }
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(28.dp)) {
                Column {
                    Text("登录虚拟主机", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("用卡密登录后，可在工作区与虚拟主机目录间切换", fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(value = domain, onValueChange = { domain = it }, placeholder = { Text("绑定域名", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = WsBlue, cursorColor = WsBlue))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = card, onValueChange = { card = it }, placeholder = { Text("卡密", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = WsBlue, cursorColor = WsBlue))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = password, onValueChange = { password = it }, placeholder = { Text("密码", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { doLogin() }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = WsBlue, cursorColor = WsBlue))
                    if (err != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(err ?: "", fontSize = 12.sp, color = WsRed)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { showHostLogin = false }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6B7280))) { Text("取消") }
                        Button(onClick = { doLogin() }, modifier = Modifier.weight(1f),
                            enabled = domain.isNotBlank() && card.isNotBlank() && password.isNotBlank() && !loadingLogin,
                            colors = ButtonDefaults.buttonColors(containerColor = WsBlue)) {
                            if (loadingLogin) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            else Text("登录")
                        }
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
                    Text("确定要删除${if (target.isDirectory) "文件夹（含全部内容）" else "文件"}", fontSize = 14.sp, color = Color(0xFF374151))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEF2F2)).padding(12.dp)) {
                        Text("\"${target.name}\"", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF991B1B))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("此操作不可撤销。", fontSize = 13.sp, color = Color(0xFF9CA3AF))
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
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(28.dp)) {
                Column {
                    Text("重命名", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(target.name, fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(value = renameName, onValueChange = { renameName = it },
                        placeholder = { Text("新名称", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (validName(renameName)) confirmRename() }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = WsBlue, cursorColor = WsBlue))
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { renameTarget = null }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6B7280))) { Text("取消") }
                        Button(onClick = { confirmRename() }, modifier = Modifier.weight(1f),
                            enabled = renameName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = WsBlue)) { Text("确认") }
                    }
                }
            }
        }
    }

    // ═══════ 全屏编辑器（统一使用 CodeEditor 组件）═══════
    if (editingFile != null) {
        CodeEditor(
            title = editingFile?.name ?: "",
            subtitle = "工作区文件",
            initialContent = editingContent,
            isLoading = editLoading,
            onSave = { text, closeAfter -> saveEditor(text, closeAfter) },
            onDismiss = { editingFile = null; editingContent = "" },
            luaRootFile = editingFile
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
                Text(img.name, fontSize = 14.sp, color = Color(0xFFE5E7EB), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${(imgZoom.floatValue * 100).toInt()}%", fontSize = 11.sp, color = Color(0xFF9CA3AF))
            }
            coil.compose.AsyncImage(
                model = img,
                contentDescription = "图片查看",
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            imgZoom.floatValue = (imgZoom.floatValue * zoom).coerceIn(0.5f, 6f)
                        }
                    }
                    .graphicsLayer {
                        scaleX = imgZoom.floatValue
                        scaleY = imgZoom.floatValue
                    }
                    .padding(top = 52.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }
    }

    // ═══════ APK 安装确认弹窗（点 APK=询问是否安装,绝不进文本编辑器）═══════
    installTarget?.let { apk ->
        BackHandler(enabled = !installLaunching) { installTarget = null }
        AlertDialog(
            onDismissRequest = { if (!installLaunching) installTarget = null },
            icon = { Icon(Icons.Filled.Archive, contentDescription = null, tint = WsPurple, modifier = Modifier.size(28.dp)) },
            title = { Text("安装应用", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("是否要安装这个 APK 文件？", fontSize = 14.sp, color = Color(0xFF374151))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFF5F3FF)).padding(12.dp)) {
                        Text("\"${apk.name}\"", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF5B21B6))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("大小: ${formatSizeWs(apk.length())}", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                }
            },
            confirmButton = {
                Button(onClick = { launchInstall(apk) }, enabled = !installLaunching, colors = ButtonDefaults.buttonColors(containerColor = WsPurple)) {
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
                Modifier.width(240.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), color = WsBlue, strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(opHint, fontSize = 14.sp, color = Color(0xFF374151))
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
                    Text("确定要删除选中的 ${selectedFiles.size} 个文件？", fontSize = 14.sp, color = Color(0xFF374151))
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
                    Text("此操作不可撤销。", fontSize = 13.sp, color = Color(0xFF9CA3AF))
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
                            runCatching { f.delete() }.onSuccess { if (it) ok++ }
                        }
                        withContext(Dispatchers.Main) {
                            toast(if (ok == toDelete.size) "已删除 $ok 个" else "成功 $ok / ${toDelete.size} 个")
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

    // ═══════ HTML 应用内 WebView 预览 ═══════
    viewingHtml?.let { htmlFile ->
        val contextView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            view?.loadUrl(request?.url?.toString() ?: "")
                            return true
                        }
                    }
                    loadUrl("file://")
                }
            },
            modifier = Modifier.fillMaxSize().background(Color.White)
        )
        // 返回栏 + WebView 叠层(上面的 AndroidView 占了全屏,用 Box wrap 才是 Compose 写法——改下面这个)
    }
}

/** 按扩展名取文件图标(与 SandboxFileBrowser 同思路) */
private fun getFileIconWs(name: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    name.endsWith(".md", true) || name.endsWith(".txt", true) -> Icons.Filled.Description
    name.endsWith(".kt", true) || name.endsWith(".java", true) || name.endsWith(".py", true) ||
        name.endsWith(".js", true) || name.endsWith(".ts", true) || name.endsWith(".go", true) ||
        name.endsWith(".php", true) ||
        name.endsWith(".html", true) || name.endsWith(".css", true) || name.endsWith(".json", true) ||
        name.endsWith(".xml", true) || name.endsWith(".sh", true) || name.endsWith(".lua", true) -> Icons.Filled.Code
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
        name.endsWith(".xml", true) || name.endsWith(".sh", true) || name.endsWith(".lua", true) -> Color(0xFF059669)
    name.endsWith(".png", true) || name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) ||
        name.endsWith(".gif", true) || name.endsWith(".webp", true) || name.endsWith(".bmp", true) ||
        name.endsWith(".heic", true) || name.endsWith(".heif", true) -> Color(0xFFD97706)
    name.endsWith(".mp4", true) || name.endsWith(".mov", true) || name.endsWith(".avi", true) ||
        name.endsWith(".mkv", true) || name.endsWith(".webm", true) || name.endsWith(".flv", true) -> Color(0xFFDC2626)
    name.endsWith(".mp3", true) || name.endsWith(".wav", true) || name.endsWith(".flac", true) ||
        name.endsWith(".aac", true) || name.endsWith(".m4a", true) -> Color(0xFF9333EA)
    name.endsWith(".pdf", true) -> Color(0xFFB91C1C)
    name.endsWith(".zip", true) || name.endsWith(".apk", true) || name.endsWith(".rar", true) ||
        name.endsWith(".7z", true) -> Color(0xFF7C3AED)
    else -> Color(0xFF6B7280)
}

private fun formatSizeWs(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 复制/移动 剪贴板条目:记录源文件与是否为「剪切」(true=移动,false=复制) */
private data class WsClipboard(val src: File, val isCut: Boolean)

/** 详情弹窗里的「标签 / 值」一行 */
@Composable
private fun WsDetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF9CA3AF))
        Text(value, fontSize = 14.sp, color = Color(0xFF1F2937), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
