package com.pm.manager.model

import androidx.compose.runtime.Immutable
import com.pm.manager.core.formatBytes
import java.util.Locale

enum class FileKind { FOLDER, IMAGE, CODE, DOC, ARCHIVE, AUDIO, VIDEO, GENERIC }

/**
 * 服务器上的一个文件/目录。
 *
 * @Immutable 让 Compose 认定实例稳定，LazyColumn 只在实例真正变化时才重组对应行。
 *
 * 改动：原来 `modified` 是直接格式化好的字符串（"MM-dd HH:mm"），只能用于展示，
 * 想按时间排序时无从下手。现在保留原始毫秒 `modifiedMillis`，展示文案按需计算，
 * 排序 / 相对时间（今天、昨天）都能支持。
 */
@Immutable
data class SiteFile(
    val name: String,
    val kind: FileKind,
    val size: Long = 0,
    val modifiedMillis: Long = 0
) {
    val isDir: Boolean get() = kind == FileKind.FOLDER

    /** 不含点的小写扩展名；无扩展名返回空串。 */
    val ext: String
        get() {
            val i = name.lastIndexOf('.')
            return if (i <= 0 || i == name.length - 1) "" else name.substring(i + 1).lowercase(Locale.ROOT)
        }

    /** 点开头的隐藏文件（Unix 惯例）。 */
    val isHidden: Boolean get() = name.startsWith(".")

    /** 列表副标题：大小 · 时间。目录不显示大小。 */
    fun subtitle(): String = buildString {
        append(if (isDir) "文件夹" else formatBytes(size))
        if (modifiedMillis > 0) append("　·　").append(com.pm.manager.core.formatRelativeTime(modifiedMillis))
    }
}

fun kindFromName(name: String, isDir: Boolean): FileKind {
    if (isDir) return FileKind.FOLDER
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "heic" -> FileKind.IMAGE
        "html", "htm", "css", "scss", "less", "js", "mjs", "ts", "jsx", "tsx", "vue",
        "json", "xml", "go", "kt", "java", "py", "rb", "php", "c", "cpp", "h", "cs",
        "sql", "sh", "bash", "yml", "yaml", "toml", "ini", "conf", "env" -> FileKind.CODE
        "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "csv" -> FileKind.DOC
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz" -> FileKind.ARCHIVE
        "mp3", "wav", "flac", "aac", "ogg", "m4a" -> FileKind.AUDIO
        "mp4", "webm", "mov", "avi", "mkv", "flv", "m4v" -> FileKind.VIDEO
        else -> FileKind.GENERIC
    }
}

/** 可安全当文本编辑的最大体积：超过就用只读预览，避免大文件把编辑器拖死。 */
const val MAX_EDITABLE_BYTES = 2L * 1024 * 1024

/** 文本文件的常见后缀（用于判断是否走文本预览）。 */
private val TEXTY_EXT = setOf(
    "txt", "md", "log", "ini", "conf", "cfg", "env", "csv", "json", "xml", "yml", "yaml",
    "html", "htm", "css", "js", "ts", "jsx", "tsx", "vue", "php", "py", "go", "java", "kt",
    "c", "cpp", "h", "hpp", "cs", "rb", "sh", "bash", "sql", "gradle", "properties", "toml"
)

fun SiteFile.isTextLike(): Boolean = isDir || ext.isEmpty() || ext in TEXTY_EXT
