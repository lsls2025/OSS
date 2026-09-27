@file:OptIn(ExperimentalMaterial3Api::class)

package com.pm.manager.ui
import androidx.compose.foundation.layout.*

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pm.manager.core.AppPrefs
import com.pm.manager.core.ViewMode
import com.pm.manager.core.formatBytes
import com.pm.manager.core.joinPath
import com.pm.manager.core.parsePath
import com.pm.manager.core.queryDisplayName
import com.pm.manager.core.queryFileSize
import com.pm.manager.core.ToastBus
import com.pm.manager.model.FileKind
import com.pm.manager.model.SiteFile
import com.pm.manager.terminal.SessionState
import com.pm.manager.terminal.SiteFiles
import com.pm.manager.terminal.WsManager
import com.pm.manager.ui.components.ChoiceDialog
import com.pm.manager.ui.components.ChoiceItem
import com.pm.manager.ui.components.ConfirmDialog
import com.pm.manager.ui.components.ProgressOverlay
import com.pm.manager.ui.components.TextInputDialog
import com.pm.manager.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class MainDest { BROWSER, TERMINAL, BACKUP, SETTINGS, TEXT, MEDIA, HTML }
private enum class ClipOp { COPY, MOVE }
private data class Clipboard(val op: ClipOp, val srcPaths: List<List<String>>, val files: List<SiteFile>)

@Composable
fun MainScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    val settings by AppPrefs.settings.collectAsStateWithLifecycle()
    val pushStatus by SessionState.pushStatus.collectAsStateWithLifecycle()

    var destName by androidx.compose.runtime.remember { mutableStateOf("BROWSER") }
    var currentDir by androidx.compose.runtime.remember { mutableStateOf(AppPrefs.current.defaultPath) }
    var items by remember { mutableStateOf<List<SiteFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by androidx.compose.runtime.remember { mutableStateOf<List<String>>(emptyList()) }
    var searchActive by androidx.compose.runtime.remember { mutableStateOf(false) }
    var searchQuery by androidx.compose.runtime.remember { mutableStateOf("") }
    var clipboard by remember { mutableStateOf<Clipboard?>(null) }

    // 子界面数据
    var textContent by remember { mutableStateOf("") }
    var textPath by remember { mutableStateOf<List<String>>(emptyList()) }
    var textReadOnly by remember { mutableStateOf(false) }
    var mediaPath by remember { mutableStateOf<List<String>>(emptyList()) }
    var mediaMode by remember { mutableStateOf(FileKind.GENERIC) }
    var htmlContent by remember { mutableStateOf("") }
    var htmlBaseUrl by remember { mutableStateOf("") }

    // 弹窗 / 菜单
    var renameTarget by remember { mutableStateOf<SiteFile?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var zipNameOpen by remember { mutableStateOf(false) }
    var detailsTarget by remember { mutableStateOf<SiteFile?>(null) }
    var itemMenuTarget by remember { mutableStateOf<SiteFile?>(null) }
    var fabMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SiteFile?>(null) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }

    val currentPath = parsePath(currentDir)
    val selectedSet = selected.toSet()
    val selectMode = selected.isNotEmpty()

    // 系统返回手势（屏幕边缘左/右滑、或系统返回键）统一拦截为“返回上一级 / 取消”，绝不直接退出软件。
    // 优先级：弹窗/菜单 → 选择模式 → 子界面 → 上级目录 → 最顶层返回到登录/激活页。
    BackHandler(enabled = true) {
        when {
            renameTarget != null -> renameTarget = null
            newFolderOpen -> newFolderOpen = false
            zipNameOpen -> zipNameOpen = false
            detailsTarget != null -> detailsTarget = null
            itemMenuTarget != null -> itemMenuTarget = null
            fabMenuOpen -> fabMenuOpen = false
            sortMenuOpen -> sortMenuOpen = false
            moreMenuOpen -> moreMenuOpen = false
            bookmarksOpen -> bookmarksOpen = false
            confirmDelete != null -> confirmDelete = null
            confirmDeleteSelected -> confirmDeleteSelected = false
            searchActive -> { searchActive = false; searchQuery = "" }
            selected.isNotEmpty() -> selected = emptyList()
            destName != MainDest.BROWSER.name -> destName = MainDest.BROWSER.name
            currentPath.isNotEmpty() -> {
                currentDir = joinPath(currentPath.dropLast(1))
                selected = emptyList()
            }
            else -> onLogout()
        }
    }

    fun loadFiles(dir: String = currentDir) {
        loading = true
        error = null
        scope.launch {
            runCatching { SiteFiles.list(parsePath(dir)) }
                .onSuccess { items = it; loading = false }
                .onFailure { error = it.message ?: "加载失败"; loading = false; items = emptyList() }
        }
    }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (mediaPath.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    SiteFiles.downloadToStream(mediaPath, out) { p -> progress = p }
                } ?: throw IllegalStateException("无法写入目标位置")
            }.onSuccess { ToastBus.show(context, "下载完成", long = true) }
                .onFailure { ToastBus.show(context, it.message ?: "下载失败") }
            busy = false
            progress = 0f
        }
    }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            var ok = 0
            uris.forEachIndexed { i, uri ->
                progressText = "上传中 ${i + 1}/${uris.size}"
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val name = queryDisplayName(context, uri)
                        // 必须传真实大小：走定长(Content-Length)而非 chunked。
                        // 服务端不解析 chunked，只按 Content-Length 读，未知大小会写出 0 字节空文件。
                        val size = queryFileSize(context, uri)
                        SiteFiles.uploadStream(currentPath, name, input, size) { p -> progress = p }
                    }
                }.onSuccess { ok++ }.onFailure { ToastBus.show(context, "上传失败：${it.message}") }
            }
            busy = false
            progress = 0f
            ToastBus.show(context, "已上传 $ok 个文件", long = true)
            loadFiles()
        }
    }

    LaunchedEffect(currentDir) {
        WsManager.subscribe(currentPath)
        loadFiles()
    }
    LaunchedEffect(Unit) {
        WsManager.onListing = { json ->
            items = SiteFiles.parseItems(json.optJSONArray("items"))
            loading = false
            error = null
        }
    }

    fun openFile(f: SiteFile) {
        if (f.isDir) {
            currentDir = joinPath(currentPath + f.name)
            selected = emptyList()
            return
        }
        when {
            f.kind == FileKind.IMAGE || f.kind == FileKind.VIDEO || f.kind == FileKind.AUDIO -> {
                mediaPath = currentPath + f.name
                mediaMode = f.kind
                destName = MainDest.MEDIA.name
            }
            else -> {
                scope.launch {
                    val tooBig = f.size > com.pm.manager.model.MAX_EDITABLE_BYTES
                    runCatching { SiteFiles.read(currentPath + f.name) }
                        .onSuccess {
                            textContent = it
                            textPath = currentPath + f.name
                            textReadOnly = tooBig
                            destName = MainDest.TEXT.name
                        }
                        .onFailure { ToastBus.show(context, it.message ?: "读取失败") }
                }
            }
        }
    }

    fun downloadFileDirect(relPath: List<String>, name: String) {
        mediaPath = relPath
        downloadLauncher.launch(name)
    }

    fun downloadSelectedAsZip(name: String) {
        val names = selected.ifEmpty { listOfNotNull(detailsTarget?.name) }
        val paths = (if (selected.isNotEmpty()) selected else detailsTarget?.let { listOf(it.name) } ?: emptyList()).map { currentPath + it }
        if (paths.isEmpty()) return
        scope.launch {
            busy = true
            progressText = "压缩中…"
            runCatching { SiteFiles.zipItems(currentPath, paths, "$name.zip") }
                .onSuccess { zipName ->
                    busy = false
                    mediaPath = currentPath + zipName
                    loadFiles()
                    downloadLauncher.launch(zipName)
                }
                .onFailure { busy = false; ToastBus.show(context, it.message ?: "压缩失败") }
        }
    }

    fun deleteSelected() {
        val paths = selected.map { joinPath(currentPath + it) }
        scope.launch {
            busy = true
            val r = SiteFiles.batchDelete(paths)
            busy = false
            selected = emptyList()
            if (r.failed.isEmpty()) ToastBus.show(context, "已删除 ${r.success} 项", long = true)
            else ToastBus.show(context, "删除 ${r.success} 项，${r.failed.size} 项失败：${r.failed.first()}", long = true)
            loadFiles()
        }
    }

    fun pasteClipboard() {
        val clip = clipboard ?: return
        scope.launch {
            busy = true
            var ok = 0
            clip.srcPaths.forEachIndexed { i, src ->
                val dst = joinPath(currentPath + clip.files[i].name)
                runCatching {
                    if (clip.op == ClipOp.COPY) SiteFiles.copy(src, parsePath(dst))
                    else SiteFiles.move(src, parsePath(dst))
                }.onSuccess { ok++ }.onFailure { ToastBus.show(context, "粘贴失败：${it.message}") }
            }
            busy = false
            clipboard = null
            ToastBus.show(context, "已粘贴 $ok 项", long = true)
            loadFiles()
        }
    }

    val display = remember(items, settings, searchQuery, searchActive) {
        items
            .filter { settings.showHidden || !it.isHidden }
            .let { if (searchActive && searchQuery.isNotBlank()) it.filter { f -> f.name.contains(searchQuery, true) } else it }
            .sortedWith(buildSorter(settings))
    }

    when (MainDest.valueOf(destName)) {
        MainDest.TERMINAL -> { TerminalScreen(onClose = { destName = MainDest.BROWSER.name }); return }
        MainDest.BACKUP -> { BackupScreen(onClose = { destName = MainDest.BROWSER.name }); return }
        MainDest.SETTINGS -> { SettingsScreen(onLogout = onLogout, onClose = { destName = MainDest.BROWSER.name }); return }
        MainDest.TEXT -> { TextPreviewScreen(textContent, textPath, textReadOnly, onClose = { destName = MainDest.BROWSER.name }) { textContent = it }; return }
        MainDest.MEDIA -> { MediaPreviewScreen(mediaMode, mediaPath, onClose = { destName = MainDest.BROWSER.name }); return }
        MainDest.HTML -> { HtmlBrowserScreen("HTML 预览", htmlContent, htmlBaseUrl, onClose = { destName = MainDest.BROWSER.name }); return }
        MainDest.BROWSER -> Unit
    }

    Scaffold(
        topBar = {
            FileManagerTopBar(
                settings, pushStatus, currentPath, searchActive, searchQuery,
                onSearchActiveChange = { searchActive = it },
                onSearchQueryChange = { searchQuery = it },
                onMenu = { moreMenuOpen = true },
                onSort = { sortMenuOpen = true },
                onToggleView = { AppPrefs.setViewMode(if (settings.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) },
                onBookmarks = { bookmarksOpen = true }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = c.cardBg) {
                listOf(
                    Triple("文件", androidx.compose.material.icons.Icons.Rounded.Folder, MainDest.BROWSER),
                    Triple("终端", androidx.compose.material.icons.Icons.Rounded.Terminal, MainDest.TERMINAL),
                    Triple("备份", androidx.compose.material.icons.Icons.Rounded.Backup, MainDest.BACKUP),
                    Triple("设置", androidx.compose.material.icons.Icons.Rounded.Settings, MainDest.SETTINGS)
                ).forEach { (label, icon, d) ->
                    NavigationBarItem(
                        selected = destName == d.name,
                        onClick = { destName = d.name },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            selectedIconColor = c.teal, selectedTextColor = c.teal, indicatorColor = c.tealSoft
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            if (!selectMode) FloatingActionButton(onClick = { fabMenuOpen = true }, containerColor = c.teal) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Add, contentDescription = "新建", tint = Color.White)
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(c.appBg)) {
            BreadcrumbBar(currentPath, settings) { idx ->
                currentDir = joinPath(currentPath.take(idx + 1))
                selected = emptyList()
            }

            clipboard?.let { clip ->
                Row(
                    Modifier.fillMaxWidth().background(c.tealSoft).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (clip.op == ClipOp.COPY) "已复制 ${clip.files.size} 项" else "已剪切 ${clip.files.size} 项",
                        color = c.tealDark, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton2("粘贴到此处") { pasteClipboard() }
                    Spacer(Modifier.width(8.dp))
                    TextButton2("取消") { clipboard = null }
                }
            }

            if (selectMode) {
                Row(
                    Modifier.fillMaxWidth().background(c.cardBg).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("已选 ${selected.size}", color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { selected = display.map { it.name } }) { Icon(androidx.compose.material.icons.Icons.Rounded.SelectAll, contentDescription = "全选", tint = c.textSecondary) }
                    IconButton(onClick = { selected = display.filter { !selectedSet.contains(it.name) }.map { it.name } }) { Icon(androidx.compose.material.icons.Icons.Rounded.FlipToBack, contentDescription = "反选", tint = c.textSecondary) }
                    IconButton(onClick = { selected = emptyList() }) { Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "取消", tint = c.textSecondary) }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { zipNameOpen = true }) { Icon(androidx.compose.material.icons.Icons.Rounded.Download, contentDescription = "下载", tint = c.teal) }
                    IconButton(onClick = {
                        clipboard = Clipboard(ClipOp.COPY, selected.map { currentPath + it }, display.filter { selectedSet.contains(it.name) })
                        selected = emptyList()
                    }) { Icon(androidx.compose.material.icons.Icons.Rounded.ContentCopy, contentDescription = "复制", tint = c.teal) }
                    IconButton(onClick = {
                        clipboard = Clipboard(ClipOp.MOVE, selected.map { currentPath + it }, display.filter { selectedSet.contains(it.name) })
                        selected = emptyList()
                    }) { Icon(androidx.compose.material.icons.Icons.Rounded.ContentCut, contentDescription = "剪切", tint = c.teal) }
                    IconButton(onClick = { confirmDeleteSelected = true }) { Icon(androidx.compose.material.icons.Icons.Rounded.Delete, contentDescription = "删除", tint = c.danger) }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val dirs = display.count { it.isDir }
                    Text("${display.size} 项 · $dirs 文件夹 · ${formatBytes(display.filter { !it.isDir }.sumOf { it.size })}",
                        color = c.textTertiary, fontSize = 12.sp)
                }
            }

            HorizontalDivider(color = c.divider)

            PullToRefreshBox(isRefreshing = loading, onRefresh = { loadFiles() }, modifier = Modifier.fillMaxSize().weight(1f)) {
                if (error != null && items.isEmpty()) {
                    com.pm.manager.ui.components.ErrorState(error ?: "加载失败") { loadFiles() }
                } else {
                    FileList(
                        files = display, currentPath = currentPath, viewMode = settings.viewMode,
                        selected = selectedSet, selectMode = selectMode, showThumbnails = settings.gridThumbnails,
                        onOpen = { openFile(it) },
                        onToggleSelect = { f -> selected = if (selectedSet.contains(f.name)) selected - f.name else selected + f.name },
                        onLongPress = { f -> selected = if (selectedSet.contains(f.name)) selected - f.name else selected + f.name },
                        onItemMenu = { itemMenuTarget = it }
                    )
                }
            }
        }
    }

    // ---------- 弹窗区 ----------
    if (renameTarget != null) {
        TextInputDialog(title = "重命名", label = "新名称", initial = renameTarget!!.name, onConfirm = { newName ->
            val target = renameTarget!!
            scope.launch {
                runCatching { SiteFiles.rename(currentPath + target.name, newName) }
                    .onSuccess { ToastBus.show(context, "已重命名"); loadFiles() }
                    .onFailure { ToastBus.show(context, it.message ?: "重命名失败") }
                renameTarget = null
            }
        }, onDismiss = { renameTarget = null })
    }
    if (newFolderOpen) {
        TextInputDialog(title = "新建文件夹", label = "文件夹名称", initial = "", onConfirm = { name ->
            scope.launch {
                runCatching { SiteFiles.mkdir(currentPath, name) }
                    .onSuccess { ToastBus.show(context, "已创建"); loadFiles() }
                    .onFailure { ToastBus.show(context, it.message ?: "创建失败") }
                newFolderOpen = false
            }
        }, onDismiss = { newFolderOpen = false })
    }
    if (zipNameOpen) {
        TextInputDialog(title = "压缩为 zip", label = "压缩包名称", initial = "archive", onConfirm = { name ->
            zipNameOpen = false
            downloadSelectedAsZip(name)
        }, onDismiss = { zipNameOpen = false })
    }
    if (detailsTarget != null) {
        FileDetailsDialog(detailsTarget!!, currentPath, onDismiss = { detailsTarget = null }) { act ->
            when (act) {
                "rename" -> { renameTarget = detailsTarget; detailsTarget = null }
                "copy" -> { clipboard = Clipboard(ClipOp.COPY, listOf(currentPath + detailsTarget!!.name), listOf(detailsTarget!!)); detailsTarget = null }
                "move" -> { clipboard = Clipboard(ClipOp.MOVE, listOf(currentPath + detailsTarget!!.name), listOf(detailsTarget!!)); detailsTarget = null }
                "download" -> { downloadFileDirect(currentPath + detailsTarget!!.name, detailsTarget!!.name); detailsTarget = null }
                "delete" -> { confirmDelete = detailsTarget; detailsTarget = null }
                "details" -> { /* already open */ }
            }
        }
    }
    if (itemMenuTarget != null) {
        val f = itemMenuTarget!!
        ChoiceDialog(title = f.name, options = buildItemMenu(f, selectedSet.contains(f.name)) { action ->
            when (action) {
                "open" -> openFile(f)
                "preview-html" -> scope.launch {
                    runCatching { SiteFiles.read(currentPath + f.name) }.onSuccess {
                        htmlContent = it
                        htmlBaseUrl = "file:///" + joinPath(currentPath) + "/"
                        destName = MainDest.HTML.name
                    }.onFailure { ToastBus.show(context, it.message ?: "读取失败") }
                }
                "rename" -> renameTarget = f
                "copy" -> clipboard = Clipboard(ClipOp.COPY, listOf(currentPath + f.name), listOf(f))
                "move" -> clipboard = Clipboard(ClipOp.MOVE, listOf(currentPath + f.name), listOf(f))
                "download" -> downloadFileDirect(currentPath + f.name, f.name)
                "details" -> detailsTarget = f
                "select" -> selected = if (selectedSet.contains(f.name)) selected - f.name else selected + f.name
                "delete" -> confirmDelete = f
                "unzip" -> scope.launch {
                    busy = true
                    progressText = "解压中…"
                    runCatching { SiteFiles.unzip(currentPath + f.name) }
                        .onSuccess { ToastBus.show(context, "解压完成", long = true); loadFiles() }
                        .onFailure { ToastBus.show(context, it.message ?: "解压失败", long = true) }
                    busy = false
                    progress = 0f
                }
            }
        }) { itemMenuTarget = null }
    }
    if (fabMenuOpen) {
        ChoiceDialog(title = "新建 / 上传", options = listOf(
            ChoiceItem("新建文件夹", androidx.compose.material.icons.Icons.Rounded.CreateNewFolder) { newFolderOpen = true },
            ChoiceItem("上传文件", androidx.compose.material.icons.Icons.Rounded.Upload) { uploadLauncher.launch("*/*") }
        )) { fabMenuOpen = false }
    }
    if (sortMenuOpen) {
        val opts = com.pm.manager.core.SortField.values().map { sf ->
            ChoiceItem("${sf.label}（${if (settings.sortAsc) "升序" else "降序"}）",
                if (settings.sortField == sf) androidx.compose.material.icons.Icons.Rounded.Check else null) {
                if (settings.sortField == sf) AppPrefs.setSort(sf, !settings.sortAsc)
                else AppPrefs.setSort(sf, true)
            }
        }
        ChoiceDialog(title = "排序方式", options = opts + ChoiceItem("切换升/降序", androidx.compose.material.icons.Icons.Rounded.SwapVert) { AppPrefs.setSort(settings.sortField, !settings.sortAsc) }) { sortMenuOpen = false }
    }
    if (moreMenuOpen) {
        ChoiceDialog(title = "更多", options = listOf(
            ChoiceItem(if (settings.showHidden) "隐藏隐藏文件" else "显示隐藏文件", androidx.compose.material.icons.Icons.Rounded.Visibility) { AppPrefs.setShowHidden(!settings.showHidden) },
            ChoiceItem("书签目录", androidx.compose.material.icons.Icons.Rounded.Bookmark) { bookmarksOpen = true },
            ChoiceItem("退出登录", androidx.compose.material.icons.Icons.Rounded.Logout, danger = true) { onLogout() }
        )) { moreMenuOpen = false }
    }
    if (bookmarksOpen) {
        BookmarksDialog(
            bookmarks = settings.bookmarks, currentDir = currentDir,
            onOpen = { rel -> currentDir = rel; selected = emptyList(); bookmarksOpen = false },
            onAdd = { AppPrefs.addBookmark(currentDir) },
            onRemove = { AppPrefs.removeBookmark(it) },
            onClose = { bookmarksOpen = false }
        )
    }
    if (confirmDelete != null) {
        ConfirmDialog(title = "删除文件", danger = true, message = "确定删除「${confirmDelete!!.name}」？此操作不可撤销。", onConfirm = {
            val t = confirmDelete!!
            scope.launch {
                runCatching { SiteFiles.delete(currentPath + t.name) }
                    .onSuccess { ToastBus.show(context, "已删除"); loadFiles() }
                    .onFailure { ToastBus.show(context, it.message ?: "删除失败") }
                confirmDelete = null
            }
        }, onDismiss = { confirmDelete = null })
    }
    if (confirmDeleteSelected) {
        ConfirmDialog(title = "删除多项", danger = true, message = "确定删除选中的 ${selected.size} 项？此操作不可撤销。", onConfirm = {
            confirmDeleteSelected = false
            deleteSelected()
        }, onDismiss = { confirmDeleteSelected = false })
    }
    if (busy) ProgressOverlay(progress, progressText.ifEmpty { "处理中…" })
}
