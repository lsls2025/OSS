package com.aurora.chat.host.pm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aurora.chat.host.HostFile
import com.aurora.chat.host.HostFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun toast(ctx: Context, msg: String, long: Boolean = false) =
    Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

/** 文件管理（「项目管理」MainScreen 的 BROWSER tab，照搬结构）。 */
@Composable
fun HostFileBrowser(onBack: () -> Unit, modifier: Modifier = Modifier, onBackToWorkspace: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    val settings by HostPmPrefs.settings

    var hostPath by remember { mutableStateOf(parsePath(HostPmPrefs.settings.value.defaultPath)) }
    var entries by remember { mutableStateOf<List<HostFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var newName by remember { mutableStateOf("") }
    var newIsFolder by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<HostFile?>(null) }
    var renameTarget by remember { mutableStateOf<HostFile?>(null) }
    var renameName by remember { mutableStateOf("") }
    var detailsTarget by remember { mutableStateOf<HostFile?>(null) }
    var itemMenuTarget by remember { mutableStateOf<HostFile?>(null) }
    var fabMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }

    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var clipboard by remember { mutableStateOf<Clipboard?>(null) }
    var downloadTarget by remember { mutableStateOf<List<String>?>(null) }

    var editingPath by remember { mutableStateOf<List<String>?>(null) }
    var editingName by remember { mutableStateOf("") }
    var editingContent by remember { mutableStateOf("") }
    var editLoading by remember { mutableStateOf(false) }

    fun toast(msg: String) = toast(context, msg)
    fun validName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') &&
            name != "." && name != ".." && !name.contains('\u0000')
    fun fullPath(name: String): List<String> = hostPath + name

    fun refresh() {
        if (entries.isEmpty()) loading = true
        error = null
        scope.launch(Dispatchers.IO) {
            runCatching { HostFiles.list(hostPath) }
                .onSuccess { list ->
                    val sorted = list.sortedWith(buildSorter(settings))
                    withContext(Dispatchers.Main) { entries = sorted; loading = false }
                }
                .onFailure { e ->
                    withContext(Dispatchers.Main) {
                        loading = false; error = e.message ?: "加载失败"
                    }
                }
        }
    }

    fun navigateTo(path: List<String>) {
        hostPath = path; entries = emptyList(); error = null; loading = true; selected = emptyList(); refresh()
    }

    fun hostCreate() {
        if (!validName(newName)) { toast("名称非法"); return }
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                if (newIsFolder) HostFiles.mkdir(hostPath, newName) else HostFiles.save(fullPath(newName), "")
            }.isSuccess
            withContext(Dispatchers.Main) {
                if (ok) { toast(if (newIsFolder) "已创建文件夹 ${newName}" else "已创建文件 ${newName}"); newName = ""; refresh() }
                else toast("创建失败")
            }
        }
    }

    fun hostDelete(f: HostFile) {
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { HostFiles.delete(fullPath(f.name)) }.isSuccess
            withContext(Dispatchers.Main) {
                if (ok) { toast("已删除 ${f.name}"); deleteTarget = null; refresh() } else toast("删除失败")
            }
        }
    }

    fun hostRename(f: HostFile) {
        if (!validName(renameName)) { toast("名称非法"); return }
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { HostFiles.rename(fullPath(f.name), renameName) }.isSuccess
            withContext(Dispatchers.Main) {
                if (ok) { toast("已重命名"); renameTarget = null; refresh() } else toast("重命名失败")
            }
        }
    }

    fun hostOpenEditor(f: HostFile) {
        editingPath = fullPath(f.name); editingName = f.name; editLoading = true
        scope.launch(Dispatchers.IO) {
            runCatching { HostFiles.read(fullPath(f.name)) }
                .onSuccess { content ->
                    withContext(Dispatchers.Main) { editingContent = content; editLoading = false }
                }
                .onFailure { e ->
                    withContext(Dispatchers.Main) { editLoading = false; toast("读取失败：${e.message}"); editingPath = null }
                }
        }
    }

    suspend fun hostSaveEditor(text: String, closeAfter: Boolean) {
        val p = editingPath ?: return
        editLoading = true
        withContext(Dispatchers.IO) {
            val ok = runCatching { HostFiles.save(p, text) }.isSuccess
            withContext(Dispatchers.Main) {
                editLoading = false
                if (ok) { toast(if (closeAfter) "已保存并退出" else "已保存"); refresh(); if (closeAfter) editingPath = null }
                else toast("保存失败")
            }
        }
    }

    fun resolveUploadName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        val n = cur.getString(0)?.trim()
                        if (!n.isNullOrBlank()) return n.replace(Regex("[\\x00/]"), "_").trim()
                    }
                }
            }
        }
        val seg = uri.lastPathSegment ?: "upload_${System.currentTimeMillis()}"
        return seg.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "upload_${System.currentTimeMillis()}" }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val safePath = hostPath
        scope.launch(Dispatchers.IO) {
            var ok = 0
            uris.forEach { uri ->
                val name = resolveUploadName(uri)
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L }.getOrDefault(0L)
                        HostFiles.uploadStream(safePath, name, input, size) {}
                    }
                    ok++
                } catch (e: Exception) { withContext(Dispatchers.Main) { toast("上传失败：${e.message}") } }
            }
            withContext(Dispatchers.Main) { toast("已上传 $ok 个文件"); refresh() }
        }
    }

    val treePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val target = downloadTarget ?: return@rememberLauncherForActivityResult
        if (treeUri == null) return@rememberLauncherForActivityResult
        try {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        } catch (_: Exception) {}
        val name = target.last()
        val tmp = File(context.cacheDir, "dl_${System.currentTimeMillis()}_$name")
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { HostFiles.downloadToFile(target, tmp) }.isSuccess
            if (ok) {
                val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                val out = rootDoc?.createFile("*/*", name)
                if (out != null) context.contentResolver.openOutputStream(out.uri)?.use { os -> tmp.inputStream().use { it.copyTo(os) } }
            }
            runCatching { tmp.delete() }
            withContext(Dispatchers.Main) { toast(if (ok) "已下载 $name" else "下载失败"); downloadTarget = null }
        }
    }

    LaunchedEffect(hostPath) { refresh() }

    fun openFile(f: HostFile) {
        if (f.isDir) { navigateTo(hostPath + f.name); selected = emptyList(); return }
        val editable = f.size <= MAX_EDITABLE_BYTES &&
            (f.kind == FileKind.DOC || f.kind == FileKind.CODE || f.kind == FileKind.GENERIC && !isBinaryExt(f.ext))
        if (editable) hostOpenEditor(f)
        else detailsTarget = f
    }

    BackHandler(enabled = true) {
        when {
            renameTarget != null -> renameTarget = null
            deleteTarget != null -> deleteTarget = null
            detailsTarget != null -> detailsTarget = null
            itemMenuTarget != null -> itemMenuTarget = null
            fabMenuOpen -> fabMenuOpen = false
            sortMenuOpen -> sortMenuOpen = false
            moreMenuOpen -> moreMenuOpen = false
            bookmarksOpen -> bookmarksOpen = false
            confirmDeleteSelected -> confirmDeleteSelected = false
            searchActive -> { searchActive = false; searchQuery = "" }
            selected.isNotEmpty() -> selected = emptyList()
            hostPath.isNotEmpty() -> navigateTo(hostPath.dropLast(1))
            else -> onBack()
        }
    }

    Column(modifier.fillMaxSize().background(c.appBg)) {
        FileManagerTopBar(
            settings = settings, pushStatus = "", currentPath = hostPath,
            searchActive = searchActive, searchQuery = searchQuery,
            onSearchActiveChange = { searchActive = it },
            onSearchQueryChange = { searchQuery = it },
            onMenu = { moreMenuOpen = true },
            onSort = { sortMenuOpen = true },
            onToggleView = { HostPmPrefs.setViewMode(if (settings.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) },
            onBookmarks = { bookmarksOpen = true },
            onBackToWorkspace = onBackToWorkspace
        )
        BreadcrumbBar(hostPath, settings) { idx -> navigateTo(if (idx < 0) emptyList() else hostPath.take(idx + 1)) }

        val display = remember(entries, settings, searchQuery, searchActive) {
            entries
                .filter { settings.showHidden || !it.name.startsWith(".") }
                .let { if (searchActive && searchQuery.isNotBlank()) it.filter { f -> f.name.contains(searchQuery, true) } else it }
                .sortedWith(buildSorter(settings))
        }

        clipboard?.let { clip ->
            Row(Modifier.fillMaxWidth().background(c.tealSoft).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (clip.op == ClipOp.COPY) "已复制 ${clip.files.size} 项" else "已剪切 ${clip.files.size} 项",
                    color = c.tealDark, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton2("粘贴到此处") {
                    pasteClipboard(clip, hostPath, context, { msg -> toast(msg) }, { selected = emptyList(); clipboard = null; refresh() })
                }
                Spacer(Modifier.width(8.dp))
                TextButton2("取消") { clipboard = null }
            }
        }

        if (selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(c.cardBg).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${selected.size}", color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { selected = display.map { it.name } }) { Icon(Icons.Rounded.SelectAll, contentDescription = "全选", tint = c.textSecondary) }
                IconButton(onClick = { selected = emptyList() }) { Icon(Icons.Rounded.Close, contentDescription = "取消", tint = c.textSecondary) }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { val name = "archive_${System.currentTimeMillis()}"; scope.launch(Dispatchers.IO) { runCatching { HostFiles.zipItems(selected.map { hostPath + it }, name) }.onSuccess { withContext(Dispatchers.Main) { toast("已压缩 $name"); refresh() } }.onFailure { withContext(Dispatchers.Main) { toast("压缩失败") } } } }) { Icon(Icons.Rounded.Download, contentDescription = "下载", tint = c.teal) }
                IconButton(onClick = { clipboard = Clipboard(ClipOp.COPY, selected.map { hostPath + it }, entries.filter { selectedSet(selected).contains(it.name) }); selected = emptyList() }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "复制", tint = c.teal) }
                IconButton(onClick = { clipboard = Clipboard(ClipOp.MOVE, selected.map { hostPath + it }, entries.filter { selectedSet(selected).contains(it.name) }); selected = emptyList() }) { Icon(Icons.Rounded.ContentCut, contentDescription = "剪切", tint = c.teal) }
                IconButton(onClick = { confirmDeleteSelected = true }) { Icon(Icons.Rounded.Delete, contentDescription = "删除", tint = c.danger) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val dirs = entries.count { it.isDir }
                Text("${entries.size} 项 · $dirs 文件夹 · ${formatBytes(entries.filter { !it.isDir }.sumOf { it.size })}", color = c.textTertiary, fontSize = 12.sp)
            }
        }

        androidx.compose.material3.HorizontalDivider(color = c.divider)

        if (loading && entries.isEmpty()) {
            LoadingState()
        } else if (error != null && entries.isEmpty()) {
            ErrorState(error ?: "加载失败") { refresh() }
        } else if (display.isEmpty()) {
            EmptyState("此目录为空", Icons.Rounded.FolderOpen)
        } else if (settings.viewMode == ViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                contentPadding = PaddingValues(4.dp)
            ) {
                gridItems(display, key = { it.name }) { f ->
                    val isSel = selected.contains(f.name)
                    Column(
                        Modifier.padding(4.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) c.tealSoft else c.cardBg)
                            .combinedClickableNoRipple(
                                onClick = { if (selected.isNotEmpty()) toggleSelect(selected, f, context) { selected = it } else openFile(f) },
                                onLongClick = { toggleSelect(selected, f, context) { selected = it } }
                            ).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(c.appBg), contentAlignment = Alignment.Center) {
                            Icon(hostFileIcon(f), contentDescription = null, tint = hostFileTint(f), modifier = Modifier.size(36.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(f.name, color = c.textPrimary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                items(display, key = { it.name }) { f ->
                    val isSel = selected.contains(f.name)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) c.tealSoft else c.cardBg)
                            .combinedClickableNoRipple(
                                onClick = { if (selected.isNotEmpty()) toggleSelect(selected, f, context) { selected = it } else openFile(f) },
                                onLongClick = { toggleSelect(selected, f, context) { selected = it } }
                            ).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(hostFileIcon(f), contentDescription = null, tint = hostFileTint(f), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = c.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            Text(f.subtitle(), color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { itemMenuTarget = f }) { Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = c.textTertiary) }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }

    // FAB
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        androidx.compose.foundation.layout.Box(Modifier.padding(16.dp).size(52.dp).clip(CircleShape)
            .background(c.teal).clickableNoRipple { fabMenuOpen = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, contentDescription = "新建", tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }

    // 弹窗
    if (fabMenuOpen) ChoiceDialog(title = "新建 / 上传", options = listOf(
        ChoiceItem("新建文件夹", Icons.Rounded.CreateNewFolder) { fabMenuOpen = false; newName = ""; newIsFolder = true; createOpen = true },
        ChoiceItem("新建文件", Icons.Rounded.NoteAdd) { fabMenuOpen = false; newName = ""; newIsFolder = false; createOpen = true },
        ChoiceItem("上传文件", Icons.Rounded.Upload) { fabMenuOpen = false; uploadLauncher.launch(arrayOf("*/*")) }
    )) { fabMenuOpen = false }

    if (createOpen) {
        Dialog(onDismissRequest = { createOpen = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(c.cardBg).padding(28.dp)) {
                Column {
                    Text(if (newIsFolder) "新建文件夹" else "新建文件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    Spacer(Modifier.height(16.dp))
                    AppOutlinedField(value = newName, onValueChange = { newName = it }, label = "名称", singleLine = true)
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { createOpen = false }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textSecondary)) { Text("取消") }
                        Button(onClick = { createOpen = false; hostCreate() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = c.teal)) { Text("创建", color = Color.White) }
                    }
                }
            }
        }
    }

    if (sortMenuOpen) ChoiceDialog(title = "默认排序", options = SortField.values().map { sf ->
        ChoiceItem(sf.label, if (settings.sortField == sf) Icons.Rounded.Check else null) { HostPmPrefs.setSort(sf, true) }
    } + ChoiceItem("切换升/降序", Icons.Rounded.SwapVert) { HostPmPrefs.setSort(settings.sortField, !settings.sortAsc) }) { sortMenuOpen = false }

    if (moreMenuOpen) ChoiceDialog(title = "更多", options = listOf(
        ChoiceItem(if (settings.showHidden) "隐藏隐藏文件" else "显示隐藏文件", Icons.Rounded.Visibility) { HostPmPrefs.setShowHidden(!settings.showHidden) },
        ChoiceItem("书签目录", Icons.Rounded.Bookmark) { moreMenuOpen = false; bookmarksOpen = true },
        ChoiceItem("退出登录", Icons.Rounded.Logout, danger = true) { onBack() }
    )) { moreMenuOpen = false }

    if (bookmarksOpen) BookmarksDialog(
        bookmarks = emptyList(), currentDir = joinPath(hostPath),
        onOpen = { rel -> navigateTo(parsePath(rel)); bookmarksOpen = false },
        onAdd = { HostPmPrefs.setDefaultPath(joinPath(hostPath)); toast("已设为默认目录") },
        onRemove = { toast("已移除") },
        onClose = { bookmarksOpen = false }
    )

    if (renameTarget != null) {
        val f = renameTarget!!
        Dialog(onDismissRequest = { renameTarget = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(c.cardBg).padding(28.dp)) {
                Column {
                    Text("重命名", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    Spacer(Modifier.height(16.dp))
                    AppOutlinedField(value = renameName, onValueChange = { renameName = it }, label = "新名称", singleLine = true)
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { renameTarget = null }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textSecondary)) { Text("取消") }
                        Button(onClick = { hostRename(f) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = c.teal)) { Text("确认", color = Color.White) }
                    }
                }
            }
        }
    }

    if (deleteTarget != null) {
        val f = deleteTarget!!
        ConfirmDialog(title = "确认删除", danger = true,
            message = "确定要删除${if (f.isDir) "文件夹（含全部内容）" else "文件"}「${f.name}」？此操作不可撤销。",
            onConfirm = { hostDelete(f) }, onDismiss = { deleteTarget = null })
    }

    if (detailsTarget != null) {
        val f = detailsTarget!!
        FileDetailsDialog(f, hostPath,
            onDismiss = { detailsTarget = null },
            onAction = { act ->
                when (act) {
                    "open" -> { detailsTarget = null; openFile(f) }
                    "rename" -> { renameTarget = f; renameName = f.name; detailsTarget = null }
                    "copy" -> { clipboard = Clipboard(ClipOp.COPY, listOf(hostPath + f.name), listOf(f)); detailsTarget = null }
                    "move" -> { clipboard = Clipboard(ClipOp.MOVE, listOf(hostPath + f.name), listOf(f)); detailsTarget = null }
                    "download" -> { if (!f.isDir) { downloadTarget = hostPath + f.name; treePickerLauncher.launch(null) }; detailsTarget = null }
                    "unzip" -> { if (f.ext == "zip") scope.launch(Dispatchers.IO) { runCatching { HostFiles.unzip(hostPath + f.name) }.onSuccess { withContext(Dispatchers.Main) { toast("已解压"); refresh() } }.onFailure { withContext(Dispatchers.Main) { toast("解压失败") } } }; detailsTarget = null }
                    "details" -> { /* already open */ }
                    "delete" -> { deleteTarget = f; detailsTarget = null }
                }
            }
        )
    }

    if (itemMenuTarget != null) {
        val f = itemMenuTarget!!
        val selectedSet = selected.toSet()
        ChoiceDialog(title = f.name, options = buildItemMenu(f, selectedSet.contains(f.name)) { act ->
            when (act) {
                "open" -> openFile(f)
                "select" -> toggleSelect(selected, f, context) { selected = it }
                "rename" -> { renameTarget = f; renameName = f.name }
                "copy" -> clipboard = Clipboard(ClipOp.COPY, listOf(hostPath + f.name), listOf(f))
                "move" -> clipboard = Clipboard(ClipOp.MOVE, listOf(hostPath + f.name), listOf(f))
                "download" -> if (!f.isDir) { downloadTarget = hostPath + f.name; treePickerLauncher.launch(null) }
                "unzip" -> if (f.ext == "zip") scope.launch(Dispatchers.IO) { runCatching { HostFiles.unzip(hostPath + f.name) }.onSuccess { withContext(Dispatchers.Main) { toast("已解压"); refresh() } }.onFailure { withContext(Dispatchers.Main) { toast("解压失败") } } }
                "details" -> detailsTarget = f
                "delete" -> deleteTarget = f
            }
            itemMenuTarget = null
        }) { itemMenuTarget = null }
    }

    if (confirmDeleteSelected) ConfirmDialog(title = "删除多项", danger = true, message = "确定删除选中的 ${selected.size} 项？此操作不可撤销。",
        onConfirm = { confirmDeleteSelected = false; val paths = selected.map { joinPath(hostPath + it) }; scope.launch(Dispatchers.IO) { runCatching { HostFiles.batchDelete(paths) }.onSuccess { withContext(Dispatchers.Main) { toast("已删除"); refresh() } }.onFailure { withContext(Dispatchers.Main) { toast("删除失败") } } } },
        onDismiss = { confirmDeleteSelected = false })

    if (busy) ProgressOverlay(progress, progressText.ifEmpty { "处理中…" })

    if (editingPath != null) {
        com.aurora.chat.ui.components.CodeEditor(
            title = editingName, subtitle = "虚拟主机文件", initialContent = editingContent,
            isLoading = editLoading,
            onSave = { text, closeAfter -> scope.launch { hostSaveEditor(text, closeAfter) } },
            onDismiss = { editingPath = null; editingContent = "" }, luaRootFile = null
        )
    }
}

// ============ 以下为内部小工具/组件 ============

/** 常见二进制扩展名（GENERIC 分类下也不进文本编辑器，避免乱码）。 */
private val BINARY_EXTS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
    "bin", "exe", "dll", "so", "dylib", "dex", "jar", "class", "pyc", "obj", "lib", "a", "o",
    "ttf", "otf", "woff", "woff2", "eot", "ico", "cur",
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif",
    "mp4", "mov", "avi", "mkv", "webm", "3gp", "mpg", "mpeg", "flv", "wmv",
    "mp3", "wav", "ogg", "flac", "aac", "m4a", "opus", "mid", "midi",
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "cab", "msi",
    "db", "sqlite", "sqlite3", "mdb", "dat", "pak", "pdb",
    "psd", "ai", "sketch", "fig", "blend", "fbx", "obj3d",
    "woff", "eot", "ttc",
)

private fun isBinaryExt(e: String): Boolean = e in BINARY_EXTS

private data class Clipboard(val op: ClipOp, val srcPaths: List<List<String>>, val files: List<HostFile>)
private enum class ClipOp { COPY, MOVE }

private fun selectedSet(selected: List<String>) = selected.toSet()

private fun toggleSelect(selected: List<String>, f: HostFile, context: Context, set: (List<String>) -> Unit) {
    set(if (selected.contains(f.name)) selected - f.name else selected + f.name)
}

private fun pasteClipboard(clip: Clipboard, dest: List<String>, context: Context, toast: (String) -> Unit, done: () -> Unit) {
    val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
    scope.launch {
        var ok = 0
        clip.srcPaths.forEach { src ->
            val dst = dest + src.last()
            val success = runCatching {
                when (clip.op) {
                    ClipOp.COPY -> HostFiles.copy(src, dst)
                    ClipOp.MOVE -> HostFiles.move(src, dst)
                }
            }.isSuccess
            if (success) ok++
        }
        withContext(Dispatchers.Main) {
            val verb = if (clip.op == ClipOp.COPY) "复制" else "移动"
            if (ok == clip.srcPaths.size) toast("已$verb $ok 项") else toast("部分失败（成功 $ok/${clip.srcPaths.size}）")
            done()
        }
    }
}

@Composable
private fun FileManagerTopBar(
    settings: AppSettings, pushStatus: String, currentPath: List<String>,
    searchActive: Boolean, searchQuery: String,
    onSearchActiveChange: (Boolean) -> Unit, onSearchQueryChange: (String) -> Unit,
    onMenu: () -> Unit, onSort: () -> Unit, onToggleView: () -> Unit, onBookmarks: () -> Unit,
    onBackToWorkspace: () -> Unit,
) {
    val c = LocalAppColors.current
    Column(Modifier.background(c.cardBg).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackToWorkspace) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回工作区", tint = c.textPrimary)
            }
            Text("虚拟主机", color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { if (searchActive) { onSearchActiveChange(false); onSearchQueryChange("") } else onSearchActiveChange(true) }) {
                Icon(Icons.Rounded.Search, contentDescription = "搜索", tint = if (searchActive) c.teal else c.textPrimary)
            }
            IconButton(onClick = onBookmarks) { Icon(Icons.Rounded.BookmarkBorder, contentDescription = "书签", tint = c.textPrimary) }
            IconButton(onClick = onSort) { Icon(Icons.Rounded.Sort, contentDescription = "排序", tint = c.textPrimary) }
            IconButton(onClick = onToggleView) { Icon(if (settings.viewMode == ViewMode.LIST) Icons.Rounded.GridView else Icons.Rounded.ViewList, contentDescription = "切换视图", tint = c.textPrimary) }
            IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = c.textPrimary) }
        }
        androidx.compose.animation.AnimatedVisibility(visible = searchActive,
            enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = searchQuery, onValueChange = onSearchQueryChange, placeholder = { Text("搜索当前目录…", color = c.textTertiary, fontSize = 14.sp) }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = c.appBg, unfocusedContainerColor = c.appBg, focusedBorderColor = c.teal, unfocusedBorderColor = c.divider))
            }
        }
    }
}

@Composable
private fun BreadcrumbBar(currentPath: List<String>, settings: AppSettings, onNavigate: (Int) -> Unit) {
    val c = LocalAppColors.current
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).background(c.cardBg).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BreadcrumbChip("根目录", true) { onNavigate(-1) }
        currentPath.forEachIndexed { i, seg -> Text(" / ", color = c.textTertiary, fontSize = 13.sp); BreadcrumbChip(seg, i == currentPath.lastIndex) { onNavigate(i) } }
    }
}

@Composable
private fun BreadcrumbChip(label: String, isLast: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Text(label, color = if (isLast) c.teal else c.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickableNoRipple(onClick).padding(vertical = 2.dp))
}

@Composable
private fun FileDetailsDialog(f: HostFile, currentPath: List<String>, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    PureWhiteDialog(onDismiss) {
        val c = LocalAppColors.current
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(hostFileIcon(f), contentDescription = null, tint = hostFileTint(f), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(f.name, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            DetailRow("类型", if (f.isDir) "文件夹" else (f.ext.ifEmpty { "文件" }))
            DetailRow("大小", if (f.isDir) "—" else formatBytes(f.size))
            DetailRow("修改时间", formatFullTime(f.modifiedMillis))
            DetailRow("路径", joinPath(currentPath + f.name))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("重命名", modifier = Modifier.weight(1f), onClick = { onAction("rename") })
                ActionChip(if (f.isDir) "打开" else "详情", modifier = Modifier.weight(1f), onClick = { onAction("details") })
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("复制", modifier = Modifier.weight(1f), onClick = { onAction("copy") })
                ActionChip("剪切", modifier = Modifier.weight(1f), onClick = { onAction("move") })
                if (!f.isDir) ActionChip("下载", modifier = Modifier.weight(1f), onClick = { onAction("download") })
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                ActionChip("删除", danger = true, modifier = Modifier.weight(1f), onClick = { onAction("delete") })
            }
        }
    }
}

@Composable
private fun DetailRow(k: String, v: String) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(k, color = c.textTertiary, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(v, color = c.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionChip(text: String, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(if (danger) c.danger.copy(alpha = 0.1f) else Color(0xFFF4F6F8)).clickable(onClick = onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (danger) c.danger else c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BookmarksDialog(bookmarks: List<String>, currentDir: String, onOpen: (String) -> Unit, onAdd: () -> Unit, onRemove: (String) -> Unit, onClose: () -> Unit) {
    val c = LocalAppColors.current
    PureWhiteDialog(onClose) {
        Column(Modifier.width(320.dp)) {
            Text("书签目录", color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (bookmarks.isEmpty()) Text("还没有书签；可在「更多」里把当前目录设为默认目录。", color = c.textSecondary, fontSize = 13.sp)
            else bookmarks.forEach { rel -> Row(Modifier.fillMaxWidth().clickable { onOpen(rel) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = c.teal, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Text(if (rel.isEmpty()) "根目录" else rel, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onRemove(rel) }) { Icon(Icons.Rounded.Delete, contentDescription = "移除", tint = c.textTertiary, modifier = Modifier.size(18.dp)) }
            } }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF4F6F8)).clickable(onClick = onAdd).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text("设为默认目录", color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun buildItemMenu(f: HostFile, isSelected: Boolean, onAction: (String) -> Unit): List<ChoiceItem> = buildList {
    if (!isSelected) add(ChoiceItem(if (f.isDir) "打开" else "预览", Icons.Rounded.OpenInNew) { onAction("open") })
    add(ChoiceItem(if (isSelected) "取消选择" else "选择", if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.CheckBox) { onAction("select") })
    add(ChoiceItem("重命名", Icons.Rounded.Edit) { onAction("rename") })
    add(ChoiceItem("复制到剪贴板", Icons.Rounded.ContentCopy) { onAction("copy") })
    add(ChoiceItem("剪切到剪贴板", Icons.Rounded.ContentCut) { onAction("move") })
    if (!f.isDir) add(ChoiceItem("下载", Icons.Rounded.Download) { onAction("download") })
    if (!f.isDir && f.ext == "zip") add(ChoiceItem("解压到当前目录", Icons.Rounded.FolderZip) { onAction("unzip") })
    add(ChoiceItem("详情", Icons.Rounded.Info) { onAction("details") })
    add(ChoiceItem("删除", Icons.Rounded.Delete, danger = true) { onAction("delete") })
}
