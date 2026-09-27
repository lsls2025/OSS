package com.pm.manager.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题模式：跟随系统 / 始终浅色 / 始终深色。 */
enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/** 文件列表呈现方式。 */
enum class ViewMode { LIST, GRID }

/** 排序字段。 */
enum class SortField(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    TIME("修改时间"),
    TYPE("类型")
}

/** 应用级偏好设置。所有界面只读这份快照，改写入口统一在 [AppPrefs]。 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val viewMode: ViewMode = ViewMode.LIST,
    val sortField: SortField = SortField.NAME,
    val sortAsc: Boolean = true,
    /** 目录是否永远排在文件前面（绝大多数文件管理器的习惯）。 */
    val dirsFirst: Boolean = true,
    val showHidden: Boolean = false,
    /** 默认启动目录（相对根目录，空串 = 本人根目录）。 */
    val defaultPath: String = "",
    /** 书签目录，元素为「相对根目录路径串」，空串代表根目录。 */
    val bookmarks: List<String> = emptyList(),
    /** 文本编辑器字号（sp）。 */
    val editorFontSize: Int = 13,
    /** 编辑器自动换行。 */
    val editorWordWrap: Boolean = true,
    /** 网格模式下显示图片缩略图。 */
    val gridThumbnails: Boolean = true
)

/**
 * 统一的本地偏好设置中心。
 *
 * 原来各界面各自 getSharedPreferences 读 key：key 散落、没有缓存、改了也不通知界面。
 * 这里收敛成一份 [AppSettings] 快照：
 * - 只在初始化时读一次磁盘，之后全走内存，避免滑动/重组时反复 IPC 读盘；
 * - 写入后通过 StateFlow 推送，界面自动跟随（例如在设置里切主题，主界面立刻生效）；
 * - 书签等结构化数据集中序列化，不再散落在调用点。
 */
object AppPrefs {

    private const val FILE = "pm_manager"
    private const val K_THEME = "theme_mode"
    private const val K_VIEW_MODE = "view_mode"
    private const val K_SORT_FIELD = "sort_field"
    private const val K_SORT_ASC = "sort_asc"
    private const val K_DIRS_FIRST = "dirs_first"
    private const val K_SHOW_HIDDEN = "show_hidden"
    private const val K_DEFAULT_PATH = "default_path"
    private const val K_BOOKMARKS = "bookmarks"
    private const val K_EDITOR_FONT = "editor_font_size"
    private const val K_EDITOR_WRAP = "editor_word_wrap"
    private const val K_GRID_THUMB = "grid_thumbnails"

    private lateinit var prefs: SharedPreferences
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** 当前设置快照（非 Compose 场景直接读）。 */
    val current: AppSettings get() = _settings.value

    private val lock = Any()

    /** 幂等初始化：重复调用不会覆盖内存中尚未落盘的改动。 */
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        synchronized(lock) {
            if (::prefs.isInitialized) return
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            reload()
        }
    }

    /** 强制从磁盘重读（仅在偏好文件被整体清空后需要）。 */
    fun reload() {
        if (!::prefs.isInitialized) return
        _settings.value = AppSettings(
            themeMode = readEnum(K_THEME, ThemeMode.SYSTEM),
            viewMode = readEnum(K_VIEW_MODE, ViewMode.LIST),
            sortField = readEnum(K_SORT_FIELD, SortField.NAME),
            sortAsc = prefs.getBoolean(K_SORT_ASC, true),
            dirsFirst = prefs.getBoolean(K_DIRS_FIRST, true),
            showHidden = prefs.getBoolean(K_SHOW_HIDDEN, false),
            defaultPath = prefs.getString(K_DEFAULT_PATH, "") ?: "",
            bookmarks = decodeBookmarks(prefs.getString(K_BOOKMARKS, "") ?: ""),
            editorFontSize = prefs.getInt(K_EDITOR_FONT, 13).coerceIn(10, 24),
            editorWordWrap = prefs.getBoolean(K_EDITOR_WRAP, true),
            gridThumbnails = prefs.getBoolean(K_GRID_THUMB, true)
        )
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T): T =
        prefs.getString(key, null)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    // ==================== 写入 ====================

    private fun mutate(block: (AppSettings) -> AppSettings) {
        _settings.value = block(_settings.value)
    }

    fun setThemeMode(mode: ThemeMode) {
        mutate { it.copy(themeMode = mode) }
        prefs.edit().putString(K_THEME, mode.name).apply()
    }

    fun setViewMode(mode: ViewMode) {
        mutate { it.copy(viewMode = mode) }
        prefs.edit().putString(K_VIEW_MODE, mode.name).apply()
    }

    fun setSort(field: SortField, asc: Boolean) {
        mutate { it.copy(sortField = field, sortAsc = asc) }
        prefs.edit().putString(K_SORT_FIELD, field.name).putBoolean(K_SORT_ASC, asc).apply()
    }

    fun setDirsFirst(value: Boolean) {
        mutate { it.copy(dirsFirst = value) }
        prefs.edit().putBoolean(K_DIRS_FIRST, value).apply()
    }

    fun setShowHidden(value: Boolean) {
        mutate { it.copy(showHidden = value) }
        prefs.edit().putBoolean(K_SHOW_HIDDEN, value).apply()
    }

    fun setDefaultPath(relPath: String) {
        val normalized = relPath.trim().trimStart('/')
        mutate { it.copy(defaultPath = normalized) }
        prefs.edit().putString(K_DEFAULT_PATH, normalized).apply()
    }

    fun setEditorFontSize(sp: Int) {
        val v = sp.coerceIn(10, 24)
        mutate { it.copy(editorFontSize = v) }
        prefs.edit().putInt(K_EDITOR_FONT, v).apply()
    }

    fun setEditorWordWrap(value: Boolean) {
        mutate { it.copy(editorWordWrap = value) }
        prefs.edit().putBoolean(K_EDITOR_WRAP, value).apply()
    }

    fun setGridThumbnails(value: Boolean) {
        mutate { it.copy(gridThumbnails = value) }
        prefs.edit().putBoolean(K_GRID_THUMB, value).apply()
    }

    // ==================== 书签 ====================

    /** 书签用换行分隔存储；路径段不包含换行，安全。空串（根目录）用占位符表示。 */
    private const val ROOT_MARK = "\u0001root"

    private fun decodeBookmarks(raw: String): List<String> =
        raw.split("\n").filter { it.isNotBlank() }.map { if (it == ROOT_MARK) "" else it }

    fun addBookmark(relPath: String) {
        val p = relPath.trim().trimStart('/')
        val list = _settings.value.bookmarks
        if (list.contains(p)) return
        val next = list + p
        mutate { it.copy(bookmarks = next) }
        persistBookmarks(next)
    }

    fun removeBookmark(relPath: String) {
        val next = _settings.value.bookmarks.filter { it != relPath }
        mutate { it.copy(bookmarks = next) }
        persistBookmarks(next)
    }

    fun moveBookmark(from: Int, to: Int) {
        val list = _settings.value.bookmarks.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        mutate { it.copy(bookmarks = list) }
        persistBookmarks(list)
    }

    private fun persistBookmarks(list: List<String>) {
        prefs.edit()
            .putString(K_BOOKMARKS, list.joinToString("\n") { if (it.isEmpty()) ROOT_MARK else it })
            .apply()
    }

    fun raw(): SharedPreferences = prefs
}
