package com.aurora.chat.host.pm

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aurora.chat.host.HostBackup
import com.aurora.chat.host.HostFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.comparisons.compareBy

/** 文件分类（决定图标）。 */
enum class FileKind { IMAGE, VIDEO, AUDIO, ARCHIVE, CODE, DOC, GENERIC }

val HostFile.ext: String
    get() = name.substringAfterLast('.', "").lowercase()

val HostFile.kind: FileKind
    get() = when {
        name.lowercase() in setOf("dockerfile", "makefile", "gemfile", "rakefile") -> FileKind.CODE
        ext == "md" || ext == "txt" || ext == "log" -> FileKind.DOC
        ext in setOf("kt", "java", "py", "js", "ts", "go", "php", "html", "htm", "css", "json", "xml",
            "sh", "lua", "yml", "yaml", "conf", "ini", "toml", "csv", "tsv", "properties",
            "sql", "bat", "cmd", "ps1", "gradle", "svg", "rst", "tex",
            "env", "gitignore", "mk", "lock", "mdx", "markdown",
            "c", "cpp", "h", "hpp", "cs", "rb", "rs", "swift", "scala", "groovy", "dart",
            "vue", "jsx", "tsx", "sass", "scss", "less", "proto", "graphql", "gql") -> FileKind.CODE
        ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif") -> FileKind.IMAGE
        ext in setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "mpg", "mpeg", "flv") -> FileKind.VIDEO
        ext in setOf("mp3", "wav", "ogg", "flac", "aac", "m4a") -> FileKind.AUDIO
        ext in setOf("zip", "apk", "rar", "7z", "tar", "gz") -> FileKind.ARCHIVE
        else -> FileKind.GENERIC
    }

fun HostFile.subtitle(): String =
    if (isDir) "文件夹" else "${formatBytes(size)} · ${formatFullTime(modifiedMillis)}"

fun HostBackup.timeText(): String = formatFullTime(time)

fun joinPath(path: List<String>): String = path.joinToString("/")
fun parsePath(s: String): List<String> = s.split("/").filter { it.isNotBlank() }

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
fun formatFullTime(ms: Long): String = if (ms <= 0) "—" else fullFmt.format(Date(ms))

/** 小于该体积的文件可在内置编辑器中打开。 */
const val MAX_EDITABLE_BYTES = 200 * 1024L

enum class ViewMode { LIST, GRID }
enum class SortField(val label: String) { NAME("名称"), SIZE("大小"), TIME("修改时间"), TYPE("类型") }
enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val viewMode: ViewMode = ViewMode.LIST,
    val showHidden: Boolean = false,
    val gridThumbnails: Boolean = false,
    val sortField: SortField = SortField.NAME,
    val sortAsc: Boolean = true,
    val dirsFirst: Boolean = true,
    val editorFontSize: Int = 14,
    val editorWordWrap: Boolean = true,
    val defaultPath: String = "",
)

/** 设置项持久化（SharedPreferences），进入虚拟主机时由 HostConfig 注入的 Context 加载。 */
object HostPmPrefs {
    private const val PREFS = "aurora_host_pm_prefs"
    private lateinit var ctx: Context
    val settings = mutableStateOf(AppSettings())

    fun load(context: Context) {
        ctx = context.applicationContext
        val p = prefs()
        settings.value = AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(p.getString("theme_mode", "SYSTEM") ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            viewMode = runCatching { ViewMode.valueOf(p.getString("view_mode", "LIST") ?: "LIST") }.getOrDefault(ViewMode.LIST),
            showHidden = p.getBoolean("show_hidden", false),
            gridThumbnails = p.getBoolean("grid_thumbnails", false),
            sortField = runCatching { SortField.valueOf(p.getString("sort_field", "NAME") ?: "NAME") }.getOrDefault(SortField.NAME),
            sortAsc = p.getBoolean("sort_asc", true),
            dirsFirst = p.getBoolean("dirs_first", true),
            editorFontSize = p.getInt("editor_font_size", 14),
            editorWordWrap = p.getBoolean("editor_word_wrap", true),
            defaultPath = p.getString("default_path", "") ?: "",
        )
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        settings.value = transform(settings.value)
        persist()
    }
    private fun persist() {
        if (!::ctx.isInitialized) return
        val s = settings.value
        prefs().edit().apply {
            putString("theme_mode", s.themeMode.name)
            putString("view_mode", s.viewMode.name)
            putBoolean("show_hidden", s.showHidden)
            putBoolean("grid_thumbnails", s.gridThumbnails)
            putString("sort_field", s.sortField.name)
            putBoolean("sort_asc", s.sortAsc)
            putBoolean("dirs_first", s.dirsFirst)
            putInt("editor_font_size", s.editorFontSize)
            putBoolean("editor_word_wrap", s.editorWordWrap)
            putString("default_path", s.defaultPath)
            apply()
        }
    }
    private fun prefs(): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setThemeMode(v: ThemeMode) = update { it.copy(themeMode = v) }
    fun setViewMode(v: ViewMode) = update { it.copy(viewMode = v) }
    fun setShowHidden(v: Boolean) = update { it.copy(showHidden = v) }
    fun setGridThumbnails(v: Boolean) = update { it.copy(gridThumbnails = v) }
    fun setSort(field: SortField, asc: Boolean) = update { it.copy(sortField = field, sortAsc = asc) }
    fun setDirsFirst(v: Boolean) = update { it.copy(dirsFirst = v) }
    fun setEditorFontSize(v: Int) = update { it.copy(editorFontSize = v.coerceIn(10, 24)) }
    fun setEditorWordWrap(v: Boolean) = update { it.copy(editorWordWrap = v) }
    fun setDefaultPath(v: String) = update { it.copy(defaultPath = v) }
}

/** 文件排序比较器（与「项目管理」一致）。 */
fun buildSorter(s: AppSettings): java.util.Comparator<HostFile> {
    val dirFirst = if (s.dirsFirst) compareBy<HostFile> { !it.isDir } else compareBy<HostFile> { 0 }
    val byField = when (s.sortField) {
        SortField.NAME -> compareBy<HostFile> { it.name.lowercase() }
        SortField.SIZE -> compareBy<HostFile> { it.size }
        SortField.TIME -> compareBy<HostFile> { it.modifiedMillis }
        SortField.TYPE -> compareBy<HostFile> { it.ext }
    }
    return dirFirst.then(if (s.sortAsc) byField else byField.reversed())
}
