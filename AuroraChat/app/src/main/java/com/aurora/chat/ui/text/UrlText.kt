package com.aurora.chat.ui.text
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.WebViewActivity

/**
 * 「选择」模式下的一次性自动全选标记。
 *
 * 一条消息里可能有多段可选中文字（Markdown 分段各自包了一层 SelectionContainer），
 * 用这个标记保证只有第一段会自动全选 —— 否则同时弹出多个系统选择菜单，互相抢焦点。
 */
class AutoSelectAllFlag {
    var used: Boolean = false
}

/** null = 当前不在「选择」模式：文字区只做普通可选，不自动全选。 */
val LocalAutoSelectAll = compositionLocalOf<AutoSelectAllFlag?> { null }

/** 需要从 URL 尾部剥离的字符（URL 中极少作为有效结尾的标点符号，含中文全角标点） */private val URL_TRAILING_TRIM_CHARS = setOf(
    ',', '.', '!', '?', ':', ';', ')', ']', '}', '>', '\'', '"', '*', '~',
    '」', '』', '〉', '》', '】', '〕', '、', '。', '，', '！', '？', '；', '：', '”', '’', '…', '·', '～', '）'
)

/** 剥离 URL 尾部多余的标点字符 */
private fun String.trimUrlTrailing(): String {
    var url = this
    while (url.isNotEmpty() && url.last() in URL_TRAILING_TRIM_CHARS) {
        url = url.dropLast(1)
    }
    return url
}

/** Markdown 加粗标记（**...**，支持下划线 __ 变体），用于 AI 对话文本 */
private val BOLD_PATTERN = Regex("(?:\\*\\*(.+?)\\*\\*|__(.+?)__)", RegexOption.DOT_MATCHES_ALL)

/**
 * Markdown 斜体（`*x*` / `_x_`）。
 *
 * 星号分支：按 CommonMark 允许**词内强调**（`中文abc*重点*def` 里的 `*重点*` 必须能斜）。
 * 旧实现在标记紧贴英文字母/数字时直接不匹配，于是 `*xx*` 左右只要有东西就整段失效、
 * 星号原样露出（用户反馈）。算式/通配符的误伤改在代码里按「内部是否含字母、外侧是否贴数字」
 * 逐条判断（见 [toUrlAnnotatedString] 里建斜体表的那段）。
 *
 * 下划线分支保持严格：`file_name` 这类 snake_case 是代码文本的常态，绝不能被当成强调；
 * 两侧带空格的星号（`2 * 3 * 4`）依旧不匹配。
 */
private val ITALIC_PATTERN = Regex(
    "(?<![*])\\*(?![\\s*])([^*\\n]+?)(?<!\\s)\\*(?!\\*)" +
        "|(?<![_A-Za-z0-9])_(?![\\s_])([^_\\n]+?)(?<!\\s)_(?![_A-Za-z0-9])"
)

/** Markdown 行内链接 `[文字](url)`：文字展示、括号内跳转（旧实现整串原样露出） */
private val MD_LINK_PATTERN = Regex("\\[([^\\]\\n]{1,120})\\]\\(([^)\\s]{1,500})\\)")

/** 常见文件扩展名（小写）：用于把文本中的文件引用（如 note.txt）与网址区分开。 */
private val FILE_EXT_SET: Set<String> = setOf(
    // ---------- 文本文档 / 办公 ----------
    "txt", "text", "md", "markdown", "mdx", "rst", "adoc", "asciidoc", "log", "rtf",
    "pdf", "doc", "docx", "docm", "dot", "dotx", "xls", "xlsx", "xlsm", "xlsb", "xlt",
    "xltx", "csv", "tsv", "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "odt",
    "ods", "odp", "odg", "pages", "numbers", "key", "wps", "et", "dps", "epub", "mobi",
    "azw", "azw3", "fb2", "djvu", "chm", "tex", "bib",

    // ---------- 数据 / 标记 / 配置 ----------
    "json", "json5", "jsonc", "jsonl", "ndjson", "geojson", "yaml", "yml", "xml", "xsd",
    "xsl", "xslt", "dtd", "plist", "toml", "ini", "cfg", "conf", "config", "properties",
    "prop", "env", "editorconfig", "gitignore", "gitattributes", "gitmodules", "dockerignore",
    "npmignore", "eslintignore", "prettierignore", "lock", "res", "rc", "reg", "inf",
    "manifest", "hcl", "tf", "tfvars", "proto", "graphql", "gql", "avsc", "parquet",
    "arrow", "feather", "orc", "avro", "sql", "ddl", "dml",

    // ---------- 网页 / 前端 ----------
    "html", "htm", "xhtml", "shtml", "vue", "svelte", "astro", "css", "scss", "sass",
    "less", "styl", "stylus", "js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx",
    "coffee", "dart", "wasm", "wat",

    // ---------- JVM / Android ----------
    "java", "kt", "kts", "groovy", "gradle", "scala", "sc", "clj", "cljs", "cljc",
    "edn", "jsp", "jspx", "jspf", "aidl", "smali", "kotlin", "iml", "apk", "aab", "aar",
    "jar", "war", "ear", "dex", "class", "jmod", "klib",

    // ---------- 其他语言 ----------
    "go", "py", "pyw", "pyi", "pyx", "ipynb", "rb", "erb", "gemspec", "rake", "ru",
    "php", "php3", "php4", "php5", "phtml", "pl", "pm", "t", "lua", "r", "rmd", "jl",
    "swift", "m", "mm", "cs", "csx", "vb", "vbs", "fs", "fsx", "fsi", "rs", "c", "h",
    "cc", "cpp", "cxx", "c++", "hpp", "hxx", "h++", "hh", "inl", "ipp", "tpp", "m4",
    "asm", "s", "d", "nim", "zig", "cr", "ex", "exs", "erl", "hrl", "el", "elc", "lisp",
    "lsp", "scm", "ss", "rkt", "hs", "lhs", "ml", "mli", "pas", "pp", "f", "f90", "f95",
    "for", "cob", "ada", "adb", "ads", "pro", "tcl", "awk", "sed", "sol", "move", "cairo",

    // ---------- 脚本 / 命令行 ----------
    "sh", "bash", "zsh", "fish", "ksh", "csh", "tcsh", "bat", "cmd", "ps1", "psm1",
    "psd1", "ps1xml", "awk", "pl", "vbs", "wsf", "sh", "service", "desktop",

    // ---------- 图片 / 图标 ----------
    "png", "jpg", "jpeg", "jpe", "jfif", "gif", "webp", "bmp", "dib", "tif", "tiff",
    "svg", "svgz", "ico", "cur", "icns", "heic", "heif", "avif", "jxl", "raw", "cr2",
    "cr3", "nef", "arw", "dng", "orf", "rw2", "pef", "srw", "psd", "psb", "ai", "eps",
    "sketch", "fig", "xd", "indd", "cdr", "xcf", "kra", "tga", "exr", "hdr", "dds",
    "ppm", "pgm", "pbm", "pnm",

    // ---------- 音频 ----------
    "mp3", "wav", "wave", "flac", "aac", "m4a", "m4b", "ogg", "oga", "opus", "wma",
    "aiff", "aif", "aifc", "ape", "alac", "amr", "ac3", "dts", "mid", "midi", "mka",
    "ra", "rm", "cda", "dsf", "dff",

    // ---------- 视频 ----------
    "mp4", "m4v", "avi", "mov", "qt", "wmv", "flv", "f4v", "mkv", "webm", "mpg",
    "mpeg", "mpe", "m2v", "m2ts", "mts", "ts", "vob", "ogv", "3gp", "3g2", "rmvb",
    "rm", "asf", "divx", "xvid", "mxf", "dv", "swf",

    // ---------- 压缩 / 打包 ----------
    "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz", "zst",
    "tzst", "lz", "lzma", "lzo", "z", "cab", "arj", "lzh", "ace", "iso", "img", "dmg",
    "vhd", "vhdx", "vmdk", "qcow", "qcow2", "wim", "esd", "pkg", "deb", "rpm", "msi",
    "msix", "appx", "snap", "flatpak", "appimage", "ipa", "xapk", "apks",

    // ---------- 可执行 / 二进制 / 库 ----------
    "exe", "com", "scr", "msc", "cpl", "dll", "ocx", "sys", "drv", "so", "dylib",
    "bundle", "o", "obj", "a", "lib", "lo", "la", "ko", "elf", "bin", "hex", "uf2",
    "elf", "out", "run",

    // ---------- 数据库 / 数据文件 ----------
    "db", "db3", "sqlite", "sqlite3", "sqlitedb", "mdb", "accdb", "dbf", "frm", "myd",
    "myi", "ibd", "fdb", "realm", "leveldb", "ldb", "dump", "bak", "backup", "old",
    "orig", "save", "sav", "tmp", "temp", "cache", "pid",

    // ---------- 字体 ----------
    "ttf", "otf", "woff", "woff2", "eot", "fon", "fnt", "pfb", "pfm", "afm", "ttc",

    // ---------- 证书 / 密钥 / 安全 ----------
    "pem", "crt", "cer", "der", "p7b", "p7c", "p12", "pfx", "jks", "keystore", "key",
    "pub", "gpg", "asc", "sig", "csr", "crl", "ovpn", "kdbx", "keychain",

    // ---------- 种子 / 下载 / 其他 ----------
    "torrent", "magnet", "part", "crdownload", "download", "aria2", "!ut", "meta",
    "dat", "idx", "pak", "vpk", "assets", "bundle", "map", "min", "d", "tsbuildinfo",

    // ---------- 工程 / 三维 / 科学 / 仿真 ----------
    "stl", "obj", "fbx", "dae", "3ds", "blend", "max", "ma", "mb", "c4d", "skp",
    "step", "stp", "iges", "igs", "dwg", "dxf", "gcode", "nc", "mat", "fig", "m",
    "slx", "mdl", "sldprt", "sldasm", "slddrw", "ipt", "iam", "prt", "asm", "catpart",
    "catproduct", "f3d", "scad", "vrml", "wrl", "x3d", "gltf", "glb", "usd", "usda",
    "usdc", "usdz", "abc", "ply", "pcd", "las", "laz", "e57",

    // ---------- 虚拟化 / 容器 / 云 ----------
    "ovf", "ova", "vbox", "vmx", "vmem", "vmsn", "vmsd", "nvram", "vmss", "vdi",
    "dockerfile", "containerfile", "helmignore", "tpl", "chart", "values",

    // ---------- 邮件 / 通讯 / 日程 ----------
    "eml", "msg", "mbox", "pst", "ost", "ics", "ical", "vcf", "vcard", "oft", "dbx",

    // ---------- 游戏 / 模拟器 / ROM ----------
    "rom", "nes", "snes", "gba", "gbc", "gb", "nds", "3ds", "cia", "nsp", "xci",
    "iso", "cso", "chd", "cue", "bin", "gcm", "wbfs", "wad", "pak", "pk3", "pk4",
    "bsp", "mdl", "vpk", "sav", "srm", "state", "ss0", "ss1"
)
private val FILE_EXTS = FILE_EXT_SET.joinToString("|")

/**
 * 文件引用匹配：把文本中出现的文件名（如 note.txt）识别出来，渲染为深色底文件元素，
 * 避免被当作「裸域名」误判成蓝色可点击链接。
 * 支持：裸文件名（note.txt）、路径（dir/file.md、C:\path\file.kt）、中文文件名。
 */
private val FILE_PATTERN = Regex(
    """(?<![\p{L}\p{N}])(?:(?:[\p{L}\p{N}_\-]+:?[/\\])+)?[\p{L}\p{N}_\-\u4E00-\u9FFF]+(?:\.[\p{L}\p{N}_\-]+)*\.($FILE_EXTS)(?![\p{L}\p{N}.])""",
    RegexOption.IGNORE_CASE
)

/**
 * 判断被 URL 正则匹配到的词是否其实是文件引用（如 note.txt）。
 * 仅「无协议前缀、无路径」的裸词才有可能是文件名；带 http(s):// 或 / 路径的完整链接
 * 即使末尾是扩展名也按链接处理（如 https://x.com/file.pdf 仍是链接）。
 */
private fun isFileExtName(s: String): Boolean {
    if (s.contains('/') || s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) return false
    val idx = s.lastIndexOf('.')
    if (idx <= 0 || idx == s.length - 1) return false
    return s.substring(idx + 1).lowercase() in FILE_EXT_SET
}

/**
 * 检测文本中的网址，被空格或文本结尾截断的链接也能正确识别。
 * 同时智能剥离 URL 尾部多余的标点符号（如 ) 、]、！等），
 * 确保 "www.example.com)。" 中只有 www.example.com 被识别为 URL。
 *
 * 识别两类：
 * 1. 以 www. 开头的域名（http(s):// 可选，如 www.example.com、http://www.example.com）
 * 2. 裸域名（http(s):// 可选，如 example.com、example.com、sub.domain.co.uk、https://example.com/path）
 *
 * http:// 与 https:// 前缀均作为可选项合并到各分支，二者都能被整体识别；
 * 没有协议前缀的裸域名（例如 example.com）也会被正确高亮，点击时统一按 https:// 打开，
 * 覆盖 com/net/org 等常见后缀，以及 asia/tech/ai 等较新后缀与多级域名。
 *
 * 链接显示为蓝色带下划线，可点击跳转（应用内/浏览器二选一）；其余文本保持原色。
 * 同时支持长按选中文本。
 */
private val URL_PATTERN = Regex(
    // 路径部分排除空白与中文/全角终止标点（「」『』、“”、（）等），
    // 避免链接后紧跟的中文符号被吞入 URL，导致后续文字一并被误解析为链接；
    // 半角标点（, . ! ? : ; 等）仍是 URL 合法字符，交由其后的 trimUrlTrailing 仅从尾部剥离
    """(?:https?://)?www\.(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,24}(?::\d+)?(?:/[^\s\u3000-\u303F\uFF01-\uFF5E\u2018\u2019\u201C\u201D\u2026\u00AB\u00BB]*)?""" +
    """|""" +
    // 裸域名（http(s):// 可选）：任意「标签.标签…….字母TLD」形态，支持多级域名、端口与路径
    // 例：example.com、example.com、sub.domain.co.uk、my-site.io:8080/path
    """(?:https?://)?[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*\.[a-zA-Z]{2,24}(?::\d+)?(?:/[^\s\u3000-\u303F\uFF01-\uFF5E\u2018\u2019\u201C\u201D\u2026\u00AB\u00BB]*)?""",
    RegexOption.IGNORE_CASE
)

/**
 * 判断文本中是否包含 URL（以文件扩展名结尾的词不算 URL，如 note.txt）
 */
fun String.containsUrl(): Boolean =
    URL_PATTERN.findAll(this).any { !isFileExtName(it.value.trimUrlTrailing()) }

/**
 * 取文本中第一个可展示的链接（供「链接预览卡片」解析标题/图标用）。
 *
 * 与高亮用的是同一个 URL 正则，因此卡片和蓝色链接永远指向同一个地址；
 * 裸域名补全 https:// 前缀，没有链接或只是文件名（note.txt）时返回 null。
 */
fun String.firstUrl(): String? {
    val raw = URL_PATTERN.findAll(this)
        .map { it.value.trimUrlTrailing() }
        .firstOrNull { it.isNotBlank() && !isFileExtName(it) } ?: return null
    return if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
        raw
    } else {
        "https://$raw"
    }
}

/** 反引号包裹的引用（如 `note.txt`、`qrauth.go`）：整体（含反引号）作为引用渲染，反引号不再外露 */
private val BACKTICK_PATTERN = Regex("`([^`\r\n]+)`")

/**
 * 文本引用区间：kind=0 链接 / kind=1 文件引用。
 *
 * @param value 展示文本（链接显示这串字；文件引用显示文件名）
 * @param target 点击跳转目标：Markdown 链接 `[文字](url)` 时与展示文本不同；为空则沿用 [value]
 */
private class RefSpan(
    val start: Int,
    val end: Int,
    val kind: Int,
    val value: String,
    val target: String = ""
)

/**
 * 统一收集文本中的引用区间（链接 kind=0 / 文件引用 kind=1），
 * [toUrlAnnotatedString] 与 [String.fileRefNames] 共用，保证渲染胶囊数量、顺序与点击注解完全一致。
 *
 * 优先级（重叠自动跳过）：
 * 1. 反引号包裹 `xxx`：整段（含反引号）作为引用，反引号被吞掉不再残留；
 *    内部以 http(s):// 开头按链接处理，否则一律按文件引用渲染（不严格要求文件名/扩展名）。
 * 2. 真 URL（裸域名、www、协议前缀等）。
 * 3. 裸文件名 / 路径（FILE_PATTERN，带扩展名）。
 */
private fun String.collectRefSpans(
    allowFileRef: Boolean = false,
    markdownLinks: Boolean = false
): List<RefSpan> {
    val spans = mutableListOf<RefSpan>()
    // 0. Markdown 行内链接 [文字](url)：优先级最高，文字展示、括号内作为跳转目标。
    //    只在 AI Markdown 文本里启用，避免把用户/好友消息里的方括号原样文本误判成链接。
    if (markdownLinks) for (match in MD_LINK_PATTERN.findAll(this)) {
        val label = match.groupValues[1].trim()
        val target = match.groupValues[2].trim()
        if (label.isEmpty() || target.isEmpty()) continue
        spans.add(RefSpan(match.range.first, match.range.last + 1, 0, label, target))
    }
    // 1. 反引号包裹（优先）：`xxx` 整体（含反引号）作为引用，反引号不再外露
    for (match in BACKTICK_PATTERN.findAll(this)) {
        val inner = match.groupValues[1].trim()
        if (inner.isEmpty()) continue
        val isUrl = inner.startsWith("http://", ignoreCase = true) || inner.startsWith("https://", ignoreCase = true)
        // 严格限制：只有 AI 发给用户的消息才允许把「文字引用」渲染成胶囊。
        // 其余场景（我发出的消息 / 好友 / 群聊等其他会话）即使文本含反引号也绝不渲染，原样保留文本；
        // 反引号内的 URL 仍按普通链接处理，不受影响。
        if (!isUrl && !allowFileRef) continue
        spans.add(RefSpan(match.range.first, match.range.last + 1, if (isUrl) 0 else 1, inner))
    }
    // 2. 真 URL（跳过已被反引号区间覆盖的部分；以文件扩展名结尾的「裸域名」如 note.txt 不是链接）
    for (match in URL_PATTERN.findAll(this)) {
        if (spans.any { it.start < match.range.last + 1 && match.range.first < it.end }) continue
        val cleanUrl = match.value.trimUrlTrailing()
        if (cleanUrl.isEmpty()) continue
        if (isFileExtName(cleanUrl)) continue
        spans.add(RefSpan(match.range.first, match.range.first + cleanUrl.length, 0, cleanUrl))
    }
    // 3. 裸文件名/路径（跳过与已有区间重叠的部分，如 `note.txt` 内部不再重复匹配）
    //    同样仅在 AI→用户 场景才渲染为胶囊；其余场景原样保留文本，不做任何特殊渲染。
    if (allowFileRef) for (match in FILE_PATTERN.findAll(this)) {
        if (spans.any { it.start < match.range.last + 1 && match.range.first < it.end }) continue
        spans.add(RefSpan(match.range.first, match.range.last + 1, 1, match.value))
    }
    return spans.sortedWith(compareBy<RefSpan> { it.start }.thenBy { it.end })
}

/**
 * 提取文本中的文件引用名列表（顺序与 [toUrlAnnotatedString] 内渲染的胶囊一一对应）。
 */
private fun String.fileRefNames(allowFileRef: Boolean = false): List<String> =
    collectRefSpans(allowFileRef).filter { it.kind == 1 }.map { it.value }

/**
 * 文件引用胶囊宽度（sp）：
 * - [FILE_CAPSULE_TEXT_MAX_WIDTH_SP]：胶囊内文字区封顶宽度，超出部分胶囊内部横向滑动查看，
 *   一律不使用省略号截断；
 * - 胶囊总宽 = 实测文字宽 + 16.dp 左右留白 + 2.dp 渲染冗余（抵消测量与渲染亚像素差），
 *   封顶 150.sp（即 [FILE_CAPSULE_MAX_WIDTH_SP]）。
 */
private const val FILE_CAPSULE_MAX_WIDTH_SP = 150f
private const val FILE_CAPSULE_TEXT_MAX_WIDTH_SP = FILE_CAPSULE_MAX_WIDTH_SP - 18f

/**
 * 将原始文本转换为 AnnotatedString，自动高亮 URL 并附带点击注解。
 * 自动剥离 URL 尾部多余的标点符号，确保 "www.example.com)。" 中
 * 只有 www.example.com 被识别为可点击的 URL。
 * 
 * @param isGroup 是否为群聊
 * @param groupMemberNames 群成员用户名集合（仅群聊时使用）
 */
fun String.toUrlAnnotatedString(
    urlColor: Color = Color(0xFF1E40AF),
    defaultColor: Color = Color.Unspecified,
    isGroup: Boolean = false,
    groupMemberNames: Set<String> = emptySet(),
    markdown: Boolean = false,
    inlineFileContent: Boolean = false,
    /** 是否允许把「文字引用/文件引用」渲染为引用胶囊：默认 false（一律不渲染）；只有「AI 对话内 AI 发给用户」的消息才显式传 true */
    allowFileRef: Boolean = false,
    /** @提及 高亮色：随气泡底色自适应（深底传白/亮色、浅底传品牌蓝）。
     *  默认跟随 [urlColor] —— urlColor 本身已是"随气泡底色自适应"（见 MessageBubble 的 myBubbleLink/otherBubbleLink）。
     *  旧实现把 @ 写死品牌蓝，气泡一旦设成品牌深蓝/天蓝等深底，@ 就整块糊进底色里看不见了（用户实测）。 */
    mentionColor: Color = urlColor
): AnnotatedString {
    return buildAnnotatedString {
        var fileIndex = 0
        // 深底气泡（链接/提及色为亮色）下，非成员 @ 也不能用深灰，否则同样糊在深底里看不清 → 取浅灰。
        val onDarkBubble = urlColor.luminance() > 0.5f
        // 气泡内文字色由气泡底色决定（见 BubbleColorStorage.bubbleTextColor），这里只降一档透明度，
        // 不再回取主题次级文字色 —— 深色主题下它是 #9E9E9E，压在白色气泡上偏灰、压在深色气泡上发糊。
        val invalidMentionColor =
            if (defaultColor != Color.Unspecified) defaultColor.copy(alpha = 0.62f)
            else if (onDarkBubble) Color(0xFFC7CFDB) else com.aurora.chat.ui.theme.appTextSecondary()
        /** 仅处理普通文本 + @mentions（不含加粗）；加粗由 [appendRun] 在统一扫描中统一控制 */
        fun appendPlain(text: String) {
            var last = 0
            val mentionPattern = Regex("@\\S+")
            for (match in mentionPattern.findAll(text)) {
                // 普通文本（@ 之前）
                if (match.range.first > last) {
                    val seg = text.substring(last, match.range.first)
                    if (defaultColor != Color.Unspecified) {
                        withStyle(SpanStyle(color = defaultColor)) { append(seg) }
                    } else {
                        append(seg)
                    }
                }
                // @用户名   → 群聊中始终添加可点击注解（即使该用户不在当前成员列表）
                // 已知群成员显示蓝色粗体，非成员显示灰色粗体
                val mentionName = match.value.trimStart('@')
                val isValidMention = isGroup && groupMemberNames.contains(mentionName)
                if (isGroup) {
                    pushStringAnnotation("MENTION", mentionName)
                    if (isValidMention) {
                        pushStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.Bold))
                    } else {
                        pushStyle(SpanStyle(color = invalidMentionColor, fontWeight = FontWeight.Bold))
                    }
                    append(match.value)
                    pop()
                    pop()
                } else {
                    append(match.value)
                }
                last = match.range.last + 1
            }
            // 剩余的普通文本
            if (last < text.length) {
                val rem = text.substring(last)
                if (defaultColor != Color.Unspecified) {
                    withStyle(SpanStyle(color = defaultColor)) { append(rem) }
                } else {
                    append(rem)
                }
            }
        }

        /** 当前是否处于加粗 / 斜体区间（由统一扫描在遇到 ** / * 标记时切换） */
        var bold = false
        var italic = false

        /** 追加一段文本：按当前状态套 Bold / Italic（可与引用区间任意交叉） */
        fun appendRun(text: String, isBold: Boolean, isItalic: Boolean) {
            if (text.isEmpty()) return
            if (isBold || isItalic) {
                withStyle(
                    SpanStyle(
                        fontWeight = if (isBold) FontWeight.Bold else null,
                        // 【斜体必须真的斜过来】
                        // FontStyle.Italic 只有在字体**自带 italic 字面**时才会变斜：国产 ROM
                        // （MiSans 等）普遍不提供 italic，平台会静默退回标准字形 —— 用户看到的正是
                        // 「星号没了，但字体还是正的、渲染失败」。
                        // 这里再叠一个几何倾斜：Compose 会把它落成 SkewXSpan（TextPaint.setTextSkewX），
                        // 由绘制层直接斜切，不依赖字体是否提供 italic，中英文都能稳定倾斜。
                        fontStyle = if (isItalic) FontStyle.Italic else null,
                        textGeometricTransform = if (isItalic) {
                            TextGeometricTransform(skewX = -0.35f)
                        } else null
                    )
                ) { appendPlain(text) }
            } else {
                appendPlain(text)
            }
        }

        /** 追加一个引用区间：URL 蓝色链接 / 文件引用胶囊（保持与 fileRefNames() 一一对应的顺序） */
        fun appendRef(s: RefSpan) {
            when (s.kind) {
                0 -> {
                    // 链接：蓝色下划线，可点击跳转（Markdown 链接显示文字、跳转 target）
                    val href = s.target.ifBlank { s.value }
                    pushStringAnnotation("URL", href)
                    pushStyle(
                        SpanStyle(
                            color = urlColor,
                            textDecoration = TextDecoration.Underline
                        )
                    )
                    append(s.value)
                    pop()
                    pop()
                }
                else -> {
                    // 文件引用：优先渲染为浅灰圆角胶囊（对齐 AI 思考过程样式）；
                    // 不支持内联组件时回退为深色底浅色字
                    pushStringAnnotation("FILE", s.value)
                    if (inlineFileContent) {
                        appendInlineContent("FILE_${fileIndex++}", " ${s.value} ")
                    } else {
                        pushStyle(
                            SpanStyle(
                                background = com.aurora.chat.ui.theme.appTextSecondary(),
                                color = com.aurora.chat.ui.theme.appDivider(),
                                fontWeight = FontWeight.Medium
                            )
                        )
                        append(" ${s.value} ")
                        pop()
                    }
                    pop()
                }
            }
        }

        // ── 统一线性扫描 ──
        // 引用区间（URL kind=0 / 文件引用 kind=1）与 Markdown 加粗标记（**x** / __x__）
        // 在同一次遍历中处理，二者可任意交叉（如 **已修改 `main.go`**）。
        // 旧实现「先按引用切段、再在每段内部找加粗」会让跨越引用的 ** 被从中间切断——
        // 结果是加粗失效且残留字面 **（同时标题/总结行根本不经过这里）。本条为根因修复。
        val spans = this@toUrlAnnotatedString.collectRefSpans(allowFileRef, markdownLinks = markdown)
        val lastIndex = this@toUrlAnnotatedString.length

        // 非 AI 文本：不做加粗解析，行为与改造前完全一致
        if (!markdown) {
            var pos = 0
            for (s in spans) {
                if (s.start < pos) continue
                if (s.start > pos) appendPlain(this@toUrlAnnotatedString.substring(pos, s.start))
                appendRef(s)
                pos = s.end
            }
            if (pos < lastIndex) appendPlain(this@toUrlAnnotatedString.substring(pos))
            return@buildAnnotatedString
        }

        // AI 文本：建立两张位置表——加粗开/闭标记、引用起点（引用优先于标记，二者不会同点）
        val boldOpen = HashMap<Int, Int>()   // 开标记起点 → 内容起点（跳过 ** / __）
        val boldClose = HashMap<Int, Int>()  // 闭标记起点 → 闭标记结束的位置（跳过 ** / __）
        for (m in BOLD_PATTERN.findAll(this@toUrlAnnotatedString)) {
            val inner = if (m.groups[1] != null) m.groups[1]!!.range else m.groups[2]!!.range
            boldOpen[m.range.first] = inner.first
            boldClose[inner.last + 1] = m.range.last + 1
        }
        // 斜体表单独建：与加粗区间重叠的（如 ***x***）直接跳过，宁可不渲染也不让样式互相串
        val italicOpen = HashMap<Int, Int>()
        val italicClose = HashMap<Int, Int>()
        for (m in ITALIC_PATTERN.findAll(this@toUrlAnnotatedString)) {
            val inner = (m.groups[1] ?: m.groups[2])?.range ?: continue
            // 星号分支：允许词内强调（`abc*xx*def`、`这是*重点*内容`），但要挡住算式/通配符。
            // 只在「标记紧贴两侧文字」时额外判断，普通写法（两侧空白/标点）行为完全不变：
            //  · 内部必须含字母（CJK 也算）→ `2*3*4`、`file*.*` 内部只有数字/符号，不放行；
            //  · 标记外侧不能贴数字      → `10*20*30`、`x*2*3` 这类乘法不放行。
            if (m.groups[1] != null) {
                val prev = this@toUrlAnnotatedString.getOrNull(m.range.first - 1)
                val next = this@toUrlAnnotatedString.getOrNull(m.range.last + 1)
                val intraword = prev?.isLetterOrDigit() == true || next?.isLetterOrDigit() == true
                if (intraword) {
                    if (this@toUrlAnnotatedString.substring(inner).none { it.isLetter() }) continue
                    if (prev?.isDigit() == true || next?.isDigit() == true) continue
                }
            }
            if (boldOpen.containsKey(m.range.first) || boldClose.containsKey(inner.last + 1)) continue
            italicOpen[m.range.first] = inner.first
            italicClose[inner.last + 1] = m.range.last + 1
        }
        val refAt = HashMap<Int, RefSpan>()
        for (s in spans) if (s.end > s.start) refAt[s.start] = s

        val buf = StringBuilder()
        fun flushBuf() {
            if (buf.isNotEmpty()) {
                appendRun(buf.toString(), bold, italic)
                buf.clear()
            }
        }

        var i = 0
        while (i < lastIndex) {
            // 引用优先：当前位置正好是引用起点 → 输出引用
            val ref = refAt[i]
            if (ref != null) {
                flushBuf()
                appendRef(ref)
                i = ref.end
                continue
            }
            // 加粗开标记：跳过标记，进入加粗态
            val openTo = boldOpen[i]
            if (openTo != null) {
                flushBuf()
                bold = true
                i = openTo
                continue
            }
            // 加粗闭标记：跳过标记，退出加粗态
            val closeTo = boldClose[i]
            if (closeTo != null) {
                flushBuf()
                bold = false
                i = closeTo
                continue
            }
            // 斜体开/闭标记：与加粗同一套线性扫描，样式可同时生效
            val italicTo = italicOpen[i]
            if (italicTo != null) {
                flushBuf()
                italic = true
                i = italicTo
                continue
            }
            val italicCloseTo = italicClose[i]
            if (italicCloseTo != null) {
                flushBuf()
                italic = false
                i = italicCloseTo
                continue
            }
            buf.append(this@toUrlAnnotatedString[i])
            i++
        }
        flushBuf()
    }
}

/**
 * 支持 URL 高亮和点击跳转的文本组件。
 *
 * 关键设计：只有当文本中实际包含 URL 时才使用 ClickableText，
 * 否则使用普通 Text 以避免干扰父容器的点击事件。
 * 非 URL 区域的点击会调用 [onClick] 回调（如果有），
 * 供父容器（如消息气泡）处理自己的点击逻辑。
 *
 * @param text 原始文本
 * @param modifier 修饰符
 * @param urlColor URL 文本颜色
 * @param defaultColor 普通文本颜色（默认不覆盖，由 style 决定）
 * @param fontSize 字号（可透传）
 * @param fontWeight 字重
 * @param lineHeight 行高
 * @param selectable 是否可长按选择文本（默认 true）
 * @param maxLines 最大行数
 * @param clickable 是否让 URL 响应点击（true=使用 ClickableText；false=仅蓝色高亮，不拦截点击事件）
 * @param onClick 非 URL 区域点击时的回调（用于向父容器传递点击事件）
 * @param isGroup 是否为群聊（@mention仅在群聊中生效）
 * @param groupMemberNames 群成员用户名集合（仅群聊时使用）
 */
@Composable
fun UrlText(
    text: String,
    modifier: Modifier = Modifier,
    urlColor: Color = Color(0xFF1E40AF),
    defaultColor: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    selectable: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    clickable: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onAtMentionClick: ((String) -> Unit)? = null,
    isGroup: Boolean = false,
    groupMemberNames: Set<String> = emptySet(),
    markdown: Boolean = false,
    /** 是否允许把「文字引用/文件引用」渲染为引用胶囊：默认 false（一律不渲染，原样显示文本）；只有「AI 对话内 AI 发给用户」的消息才显式传 true */
    allowFileRef: Boolean = false,
    /** 文件引用点击回调：非空时点击文件胶囊弹出操作弹窗（0=查看 1=所在位置）；为空时保持 Toast 提示 */
    onFileRefAction: ((fileName: String, action: Int) -> Unit)? = null,
    /** 文本被 maxLines 真实截断（视觉溢出）时实时回报：让「展开」按钮按实际排版显隐，
     *  而不是按字符数猜——旧做法用「字符数 >120」当判据，中文长文/多短行明明被截断却不给按钮。 */
    onOverflowChange: ((Boolean) -> Unit)? = null,
    /** 截断时末行样式：默认硬裁（Clip）；需要「…」提示内容未完时传 Ellipsis */
    overflow: TextOverflow = TextOverflow.Clip
) {
    val context = LocalContext.current
    val hasUrl = remember(text) { text.containsUrl() }
    val groupMembersKey = groupMemberNames.toList().sorted().joinToString(",")

    val annotatedText = remember(text, defaultColor, urlColor, isGroup, groupMembersKey, markdown, allowFileRef) {
        text.toUrlAnnotatedString(
            urlColor = urlColor,
            defaultColor = defaultColor,
            isGroup = isGroup,
            groupMemberNames = groupMemberNames,
            markdown = markdown,
            allowFileRef = allowFileRef,
            inlineFileContent = true
        )
    }

    // 文件引用内联胶囊：浅灰圆角（对齐 AI 思考过程样式 AiReasoningBlock），
    // 高度 20.sp、圆角 6.dp、背景 0xFFF3F4F6、文字 0xFF6B7280 10.sp。
    // 总宽 = 实测文字宽 + 16.dp 左右留白（Box 内 padding 8.dp 各一侧，视觉对称），
    // 超长封顶 150.sp；不再用字符估算，彻底消除右侧多余留白。
    val textMeasurer = rememberTextMeasurer()
    val capsuleDensity = LocalDensity.current
    val fileCapsuleSizes = remember(text, allowFileRef) {
        val capsuleStyle = TextStyle(
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 12.sp
        )
        text.fileRefNames(allowFileRef).map { name ->
            val rawPx = textMeasurer.measure(AnnotatedString(name), style = capsuleStyle).size.width.toFloat()
            with(capsuleDensity) {
                // 文字区封顶 132.sp，总宽再补 16.dp 留白 + 2.dp 渲染冗余，不超过 150.sp
                val maxTextPx = (FILE_CAPSULE_TEXT_MAX_WIDTH_SP).sp.toPx()
                val totalPx = rawPx.coerceAtMost(maxTextPx) + 16.dp.toPx() + 2.dp.toPx()
                (totalPx.toSp() to totalPx.toDp())
            }
        }
    }
    val fileCapsuleContent = remember(text, allowFileRef) {
        buildMap {
            text.fileRefNames(allowFileRef).forEachIndexed { index, name ->
                val capsuleSize = fileCapsuleSizes[index]
                put(
                    "FILE_$index",
                    InlineTextContent(
                        placeholder = Placeholder(
                            width = capsuleSize.first,
                            height = 20.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                        )
                    ) { _ ->
                        val capsuleWidth = capsuleSize.second
                        val capsuleHeight = with(LocalDensity.current) { 20.sp.toDp() }
                        Box(
                            modifier = Modifier
                                .width(capsuleWidth)
                                .height(capsuleHeight)
                                .clip(RoundedCornerShape(6.dp))
                                .background(com.aurora.chat.ui.theme.appCardBg())
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            // 胶囊内文字一律横向滑动查看完整名称，不使用省略号截断；
                            // 宽度 = 实测文字宽 + 16.dp 留白 + 2.dp 冗余，短文件不会溢出，
                            // 仅超长文件（封顶 132.sp）触发实际滑动
                            Text(
                                text = name,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = com.aurora.chat.ui.theme.appTextSecondary(),
                                maxLines = 1,
                                lineHeight = 12.sp,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                                modifier = Modifier
                                    .horizontalScroll(remember { ScrollState(0) })
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        }
                    }
                )
            }
        }
    }

    // URL 选择弹窗状态
    var showUrlChooser by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf("") }

    // 文件引用操作弹窗状态
    var showFileRefChooser by remember { mutableStateOf(false) }
    var pendingFileRef by remember { mutableStateOf("") }

    /** 在应用内打开 URL */
    fun openInternal(finalUrl: String) = openUrlInternal(context, finalUrl)

    /** 在系统浏览器中打开 URL */
    fun openExternal(finalUrl: String) = openUrlExternal(context, finalUrl)

    val textContent = @Composable {
        if (hasUrl && clickable) {
            // 使用 Text + pointerInput 替代 ClickableText（ClickableText 会吞掉长按事件，
            // 导致外层 combinedClickable.onLongClick 收不到回调，链接消息无法弹出菜单）
            val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = annotatedText,
                style = TextStyle(
                    color = defaultColor,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    lineHeight = lineHeight
                ),
                inlineContent = fileCapsuleContent,
                maxLines = maxLines,
                overflow = overflow,
                onTextLayout = { layoutResult.value = it; onOverflowChange?.invoke(it.hasVisualOverflow) },
                modifier = modifier.pointerInput(onClick, onLongPress) {
                    detectTapGestures(
                        onTap = { offset ->
                            val charOffset = layoutResult.value?.getOffsetForPosition(offset)
                                ?: return@detectTapGestures
                            val urlAnnotations = annotatedText.getStringAnnotations("URL", charOffset, charOffset)
                            if (urlAnnotations.firstOrNull() != null) {
                                val url = urlAnnotations.first().item
                                pendingUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                                showUrlChooser = true
                                return@detectTapGestures
                            }
                            val fileAnnotations = annotatedText.getStringAnnotations("FILE", charOffset, charOffset)
                            if (fileAnnotations.firstOrNull() != null) {
                                val fileName = fileAnnotations.first().item
                                if (onFileRefAction != null) {
                                    pendingFileRef = fileName
                                    showFileRefChooser = true
                                } else {
                                    Toast.makeText(context, "文件引用：$fileName", Toast.LENGTH_SHORT).show()
                                }
                                return@detectTapGestures
                            }
                            val mentionAnnotations = annotatedText.getStringAnnotations("MENTION", charOffset, charOffset)
                            if (mentionAnnotations.firstOrNull() != null) {
                                onAtMentionClick?.invoke(mentionAnnotations.first().item)
                                return@detectTapGestures
                            }
                            onClick?.invoke()
                        },
                        onLongPress = { onLongPress?.invoke() }
                    )
                }
            )
        } else {
            // 无 URL 或 clickable=false
            val textStyle = TextStyle(
                color = defaultColor,
                fontSize = fontSize,
                fontWeight = fontWeight,
                lineHeight = lineHeight
            )
            val baseText: @Composable () -> Unit = {
                if (hasUrl) {
                    // 有 URL 但不可点击 → 用 AnnotatedString 仅显示蓝色
                    Text(text = annotatedText, style = textStyle, maxLines = maxLines, overflow = overflow, modifier = modifier, inlineContent = fileCapsuleContent)
                } else {
                    // 无 URL → 仍用 annotatedText（含 @mention 高亮）
                    Text(text = annotatedText, style = textStyle, maxLines = maxLines, overflow = overflow, modifier = modifier, inlineContent = fileCapsuleContent)
                }
            }
            if (clickable) {
                // 没有 URL 但仍需将 tap/longpress 传给父容器
                val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
                Text(
                    text = annotatedText,
                    style = textStyle,
                    maxLines = maxLines,
                    inlineContent = fileCapsuleContent,
                    overflow = overflow,
                    onTextLayout = { layoutResult.value = it; onOverflowChange?.invoke(it.hasVisualOverflow) },
                    modifier = modifier.pointerInput(onClick, onLongPress) {
                        detectTapGestures(
                            onTap = { offset ->
                                val charOffset = layoutResult.value?.getOffsetForPosition(offset)
                                    ?: return@detectTapGestures
                                val fileAnnotations = annotatedText.getStringAnnotations("FILE", charOffset, charOffset)
                                if (fileAnnotations.firstOrNull() != null) {
                                    val fileName = fileAnnotations.first().item
                                    if (onFileRefAction != null) {
                                        pendingFileRef = fileName
                                        showFileRefChooser = true
                                    } else {
                                        Toast.makeText(context, "文件引用：$fileName", Toast.LENGTH_SHORT).show()
                                    }
                                    return@detectTapGestures
                                }
                                val mentionAnnotations = annotatedText.getStringAnnotations("MENTION", charOffset, charOffset)
                                if (mentionAnnotations.firstOrNull() != null) {
                                    onAtMentionClick?.invoke(mentionAnnotations.first().item)
                                } else {
                                    onClick?.invoke()
                                }
                            },
                            onLongPress = { onLongPress?.invoke() }
                        )
                    }
                )
            } else {
                baseText()
            }
        }
    }

    if (selectable) {
        // 选择模式：用系统原生文字选择（系统手柄 + 系统选择菜单：复制/全选/朗读…）。
        // 不做自绘工具条 —— 进选择态就自动全选一次，系统菜单当场出现，不必再长按一次。
        val autoFlag = LocalAutoSelectAll.current
        val selState = rememberSelectionState()
        if (autoFlag != null && !autoFlag.used) {
            LaunchedEffect(selState, autoFlag) {
                // 等 SelectionContainer 绑定 manager、文字完成一次排版后才有可选内容
                repeat(10) {
                    withFrameNanos { }
                    if (selState.getSelectableTexts().isNotEmpty()) {
                        selState.selectAll()
                        autoFlag.used = true
                        return@LaunchedEffect
                    }
                }
            }
        }
        SelectionContainer(state = selState) {
            textContent()
        }
    } else {
        textContent()
    }

    // ── URL 选择弹窗（与二维码结果页共用 LinkOpenChooserDialog，只有一份实现）──
    if (showUrlChooser && pendingUrl.isNotEmpty()) {
        LinkOpenChooserDialog(url = pendingUrl, onDismiss = { showUrlChooser = false })
    }

    // ── 文件引用操作弹窗：查看 / 所在位置（点击后由上层切入工作区定位）──
    if (showFileRefChooser && pendingFileRef.isNotEmpty()) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showFileRefChooser = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("文件引用", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.height(12.dp))
                Text(
                    pendingFileRef, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 查看：切入工作区定位并直接打开编辑器
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E40AF))
                            .clickable {
                                onFileRefAction?.invoke(pendingFileRef, 0)
                                showFileRefChooser = false
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("查看", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                    // 所在位置：切入工作区定位到文件所在目录
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(com.aurora.chat.ui.theme.appCardBg())
                            .clickable {
                                onFileRefAction?.invoke(pendingFileRef, 1)
                                showFileRefChooser = false
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("所在位置", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** 在应用内 WebView 打开 URL（聊天链接、二维码结果共用） */
fun openUrlInternal(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(context, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

/** 在系统浏览器打开 URL（聊天链接、二维码结果共用） */
fun openUrlExternal(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

/**
 * 链接打开方式选择弹窗：应用内 / 浏览器。
 * 聊天消息里的网址、二维码结果页的「打开」都走这一个实现，避免两份 UI。
 */
@Composable
fun LinkOpenChooserDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(com.aurora.chat.ui.theme.appSurface())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("打开链接", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.height(12.dp))
            Text(
                url, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 在应用内打开
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E40AF))
                        .clickable {
                            openUrlInternal(context, url)
                            onDismiss()
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("在应用内打开", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
                // 在浏览器中打开
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .clickable {
                            openUrlExternal(context, url)
                            onDismiss()
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("在浏览器中打开", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
