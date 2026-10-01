package com.aurora.chat.ui.server

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.components.CodeEditor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val BlueMain = Color(0xFF2563EB)
private val PurpleMain = Color(0xFF7C3AED)

// ── 终端图标 ──
@Composable
fun AnimatedTerminalIcon(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.Terminal,
            contentDescription = "终端",
            modifier = Modifier.size(26.dp),
            tint = Color(0xFF1F2937)
        )
    }
}

@Composable
fun SandboxFileBrowserDialog(
    projectId: Long = 0,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val noRipple = remember { MutableInteractionSource() }

    // ═══════ 状态变量（与备份完全一致）═══════
    var showFilePage by remember { mutableStateOf(true) }
    var filePageVisible by remember { mutableStateOf(false) }
    var fileList by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var fileListLoading by remember { mutableStateOf(false) }

    // ── 如果传入了 0，自动获取项目 ──
    val resolvedProjectId = remember { mutableLongStateOf(projectId) }
    if (resolvedProjectId.longValue == 0L) {
        LaunchedEffect(Unit) {
            try {
                val r = AuroraApi.getMySandboxProject()
                if (r.success && r.data != null) {
                    resolvedProjectId.longValue = r.data.optLong("id", 0)
                }
            } catch (_: Exception) {}
            if (resolvedProjectId.longValue == 0L) {
                android.widget.Toast.makeText(ctx, "获取沙盒项目失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 沙盒路径导航（替代主文件管理的 folderId） ──
    var sandboxCurrentPath by remember { mutableStateOf(".") }
    val pathSegments: List<String> = remember(sandboxCurrentPath) {
        if (sandboxCurrentPath == ".") emptyList() else sandboxCurrentPath.split("/")
    }

    // ── 搜索 ──
    var searchKeyword by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<JSONObject>?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    // ── 新建文件/文件夹 ──
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var createFileLoading by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var createFolderLoading by remember { mutableStateOf(false) }

    // ── 删除 ──
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var deleteTargetName by remember { mutableStateOf("") }
    var deleteTargetPath by remember { mutableStateOf("") }
    var deleteTargetIsDir by remember { mutableStateOf(false) }
    var deleteLoading by remember { mutableStateOf(false) }

    // ── 重命名 ──
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameName by remember { mutableStateOf("") }
    var renameLoading by remember { mutableStateOf(false) }

    // ── 编辑器（统一使用 CodeEditor 组件）──
    var editingFile by remember { mutableStateOf<String?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var editLoading by remember { mutableStateOf(false) }

    // ═══════ 函数（与备份一致，数据源改为沙盒API）═══════
    fun refreshFileList() {
        scope.launch {
            fileListLoading = true
            try {
                val r = AuroraApi.getSandboxFiles(projectId, sandboxCurrentPath)
                fileList = if (r.success && r.data != null) {
                    val arr = r.data.optJSONArray("files")
                    val lst = mutableListOf<JSONObject>()
                    if (arr != null) for (i in 0 until arr.length()) lst.add(arr.getJSONObject(i))
                    lst
                } else emptyList()
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "加载文件列表失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            fileListLoading = false
        }
    }

    fun enterFolder(name: String) {
        sandboxCurrentPath = if (sandboxCurrentPath == ".") name else "$sandboxCurrentPath/$name"
        searchKeyword = ""; searchResults = null
    }

    fun goToParentFolder() {
        sandboxCurrentPath = sandboxCurrentPath.substringBeforeLast("/", ".")
        searchKeyword = ""; searchResults = null
    }

    fun goToBreadcrumb(index: Int) {
        sandboxCurrentPath = if (index < 0) "." else pathSegments.take(index + 1).joinToString("/")
    }

    fun closeFilePage() { onDismiss() }

    fun createNewFile() {
        if (newFileName.isBlank()) return
        scope.launch {
            createFileLoading = true
            try {
                val fp = if (sandboxCurrentPath == ".") newFileName else "$sandboxCurrentPath/$newFileName"
                val r = AuroraApi.writeSandboxFile(projectId, fp, "")
                if (r.success) { showCreateFileDialog = false; newFileName = ""; refreshFileList() }
                else { android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "创建失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            createFileLoading = false
        }
    }

    fun createNewFolder() {
        if (newFolderName.isBlank()) return
        scope.launch {
            createFolderLoading = true
            try {
                val fp = if (sandboxCurrentPath == ".") newFolderName else "$sandboxCurrentPath/$newFolderName"
                val r = AuroraApi.mkdirSandbox(projectId, fp)
                if (r.success) { showCreateFolderDialog = false; newFolderName = ""; refreshFileList() }
                else { android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "创建失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            createFolderLoading = false
        }
    }

    fun startRename(path: String, name: String) {
        renameTarget = path; renameName = name; showRenameDialog = true
    }

    fun confirmRename() {
        if (renameTarget == null || renameName.isBlank()) return
        scope.launch {
            renameLoading = true
            try {
                val oldPath = renameTarget!!
                val parent = oldPath.substringBeforeLast("/", ".")
                val newPath = if (parent == ".") renameName else "$parent/$renameName"
                val readR = AuroraApi.readSandboxFile(projectId, oldPath)
                if (readR.success && readR.data != null) {
                    val content = readR.data.optString("content", "")
                    val writeR = AuroraApi.writeSandboxFile(projectId, newPath, content)
                    if (writeR.success) {
                        AuroraApi.deleteSandboxFile(projectId, oldPath)
                        showRenameDialog = false; refreshFileList()
                    } else android.widget.Toast.makeText(ctx, writeR.message, android.widget.Toast.LENGTH_SHORT).show()
                } else android.widget.Toast.makeText(ctx, "读取文件失败", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "重命名失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            renameLoading = false
        }
    }

    fun confirmDelete() {
        scope.launch {
            deleteLoading = true
            try {
                val r = AuroraApi.deleteSandboxFile(projectId, deleteTargetPath)
                if (r.success) { showDeleteConfirmDialog = false; refreshFileList() }
                else android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "删除失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            deleteLoading = false
        }
    }

    fun openFileEditor(path: String) {
        editingFile = path
        scope.launch {
            editLoading = true
            try {
                val r = AuroraApi.readSandboxFile(projectId, path)
                if (r.success && r.data != null) {
                    editingContent = r.data.optString("content", "")
                } else {
                    android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show()
                    editingFile = null
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "读取文件失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                editingFile = null
            }
            editLoading = false
        }
    }

    suspend fun saveFileContent(text: String, closeAfter: Boolean) {
        val path = editingFile ?: return
        editLoading = true
        try {
            val r = AuroraApi.writeSandboxFile(projectId, path, text)
            if (r.success) {
                android.widget.Toast.makeText(ctx, if (closeAfter) "保存成功并退出" else "保存成功", android.widget.Toast.LENGTH_SHORT).show()
                refreshFileList()
                if (closeAfter) editingFile = null
            } else android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
        editLoading = false
    }

    fun doSearch() {
        if (searchKeyword.isBlank()) { searchResults = null; return }
        isSearching = true
        val kw = searchKeyword.lowercase()
        searchResults = fileList.filter {
            it.optString("name", "").lowercase().contains(kw)
        }
        isSearching = false
    }

    LaunchedEffect(filePageVisible) { if (filePageVisible) refreshFileList() }
    LaunchedEffect(sandboxCurrentPath) { refreshFileList() }
    LaunchedEffect(Unit) { delay(100); filePageVisible = true }

    // ═════════════════════════════════════════════════════
    // 文件页面（与备份完全一致）
    // ═════════════════════════════════════════════════════
    if (showFilePage) {
        Dialog(
            onDismissRequest = { closeFilePage() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            ) {
                AnimatedVisibility(
                    visible = filePageVisible,
                    enter = slideInHorizontally { it },
                    exit = slideOutHorizontally { it }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // ── 顶栏 ──
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                            if (sandboxCurrentPath != ".") goToParentFolder() else closeFilePage()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (sandboxCurrentPath != ".") "文件" else "文件管理",
                                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)
                                )
                                Spacer(Modifier.weight(1f))
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(PurpleMain.copy(alpha = 0.1f))
                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showCreateFolderDialog = true },
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Folder, contentDescription = "新建文件夹", tint = PurpleMain, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(BlueMain.copy(alpha = 0.1f))
                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showCreateFileDialog = true },
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Add, contentDescription = "新建文件", tint = BlueMain, modifier = Modifier.size(22.dp))
                                }
                            }

                            // ── 面包屑导航 ──
                            if (sandboxCurrentPath != ".") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("根目录", fontSize = 13.sp, color = BlueMain, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { goToBreadcrumb(-1) })
                                    pathSegments.forEachIndexed { index, seg ->
                                        Text(" / ", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                                        Text(seg, fontSize = 13.sp,
                                            color = if (index == pathSegments.lastIndex) Color(0xFF1F2937) else BlueMain,
                                            fontWeight = if (index == pathSegments.lastIndex) FontWeight.SemiBold else FontWeight.Medium,
                                            modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { goToBreadcrumb(index) })
                                    }
                                }
                            }

                            // ── 搜索栏 ──
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = searchKeyword,
                                    onValueChange = {
                                        searchKeyword = it
                                        if (it.isEmpty()) { searchResults = null }
                                    },
                                    placeholder = { Text("搜索文件名...", fontSize = 14.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                                    trailingIcon = {
                                        IconButton(onClick = { doSearch() }) {
                                            Icon(Icons.Filled.Search, contentDescription = "搜索", tint = BlueMain, modifier = Modifier.size(20.dp))
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = Color(0xFFE5E7EB),
                                        focusedBorderColor = BlueMain,
                                        unfocusedContainerColor = Color(0xFFF9FAFB)
                                    )
                                )
                            }

                            // ── 内容区域 ──
                            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                                val displayList = searchResults ?: fileList
                                when {
                                    fileListLoading && searchResults == null -> {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(color = BlueMain, modifier = Modifier.size(36.dp))
                                        }
                                    }
                                    displayList.isEmpty() -> {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color(0xFFD1D5DB))
                                                Spacer(Modifier.height(12.dp))
                                                Text(if (searchResults != null) "未找到匹配文件" else "暂无文件", fontSize = 16.sp, color = Color(0xFF9CA3AF))
                                                Spacer(Modifier.height(4.dp))
                                                Text(if (searchResults != null) "尝试其他关键词" else "点击右上角按钮新建文件", fontSize = 13.sp, color = Color(0xFFD1D5DB))
                                            }
                                        }
                                    }
                                    else -> {
                                        val dirs = displayList.filter { it.optBoolean("is_dir", false) }
                                        val files = displayList.filter { !it.optBoolean("is_dir", false) }
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                                        ) {
                                            items(dirs + files, key = { it.optString("name", "") }) { item ->
                                                val fname = item.optString("name", "")
                                                val isDir = item.optBoolean("is_dir", false)
                                                val size = item.optLong("size", 0)
                                                val fullPath = if (sandboxCurrentPath == ".") fname else "$sandboxCurrentPath/$fname"
                                                if (isDir) {
                                                    FolderItemRow(
                                                        fname = fname,
                                                        onClick = { enterFolder(fname) },
                                                        onDeleteRequest = {
                                                            deleteTargetName = fname; deleteTargetPath = fullPath; deleteTargetIsDir = true
                                                            showDeleteConfirmDialog = true
                                                        }
                                                    )
                                                } else {
                                                    FileItemRow(
                                                        fname = fname, size = size,
                                                        onClick = { openFileEditor(fullPath) },
                                                        onRename = { startRename(fullPath, fname) },
                                                        onDeleteRequest = {
                                                            deleteTargetName = fname; deleteTargetPath = fullPath; deleteTargetIsDir = false
                                                            showDeleteConfirmDialog = true
                                                        }
                                                    )
                                                }
                                                Spacer(Modifier.height(8.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════ 新建文件弹窗（与备份一致）═══════
    if (showCreateFileDialog) {
        Dialog(
            onDismissRequest = { if (!createFileLoading) { showCreateFileDialog = false; newFileName = "" } },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(28.dp)) {
                Column {
                    Text("新建文件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("输入文件名（含扩展名，如 index.html）", fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(value = newFileName, onValueChange = { newFileName = it },
                        placeholder = { Text("文件名.后缀", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (newFileName.isNotBlank() && !createFileLoading) createNewFile() }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = BlueMain, cursorColor = BlueMain))
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { showCreateFileDialog = false; newFileName = "" }, modifier = Modifier.weight(1f), enabled = !createFileLoading,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6B7280))) { Text("取消") }
                        Button(onClick = { if (newFileName.isNotBlank()) createNewFile() }, modifier = Modifier.weight(1f),
                            enabled = newFileName.isNotBlank() && !createFileLoading, colors = ButtonDefaults.buttonColors(containerColor = BlueMain)) {
                            if (createFileLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            else Text("创建")
                        }
                    }
                }
            }
        }
    }

    // ═══════ 新建文件夹弹窗（与备份一致）═══════
    if (showCreateFolderDialog) {
        Dialog(
            onDismissRequest = { if (!createFolderLoading) { showCreateFolderDialog = false; newFolderName = "" } },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(28.dp)) {
                Column {
                    Text("新建文件夹", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(if (sandboxCurrentPath != ".") "将在当前目录创建子文件夹" else "在根目录创建新文件夹", fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it },
                        placeholder = { Text("文件夹名称", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (newFolderName.isNotBlank() && !createFolderLoading) createNewFolder() }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = Color(0xFF7C3AED), cursorColor = Color(0xFF7C3AED)))
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { showCreateFolderDialog = false; newFolderName = "" }, modifier = Modifier.weight(1f), enabled = !createFolderLoading,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6B7280))) { Text("取消") }
                        Button(onClick = { if (newFolderName.isNotBlank()) createNewFolder() }, modifier = Modifier.weight(1f),
                            enabled = newFolderName.isNotBlank() && !createFolderLoading, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) {
                            if (createFolderLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            else Text("创建")
                        }
                    }
                }
            }
        }
    }

    // ═══════ 删除确认弹窗（与备份一致）═══════
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { if (!deleteLoading) showDeleteConfirmDialog = false },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(28.dp)) },
            title = { Text("确认删除", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("确定要删除${if (deleteTargetIsDir) "文件夹" else "文件"}", fontSize = 14.sp, color = Color(0xFF374151))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEF2F2)).padding(12.dp)) {
                        Text("\"$deleteTargetName\"", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF991B1B))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("此操作不可撤销。", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                }
            },
            confirmButton = {
                Button(onClick = { confirmDelete() }, enabled = !deleteLoading, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))) {
                    if (deleteLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text("删除", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!deleteLoading) showDeleteConfirmDialog = false }) { Text("取消") }
            }
        )
    }

    // ═══════ 重命名弹窗（与备份一致）═══════
    if (showRenameDialog) {
        Dialog(
            onDismissRequest = { if (!renameLoading) showRenameDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(28.dp)) {
                Column {
                    Text("重命名", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("输入新名称", fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(value = renameName, onValueChange = { renameName = it },
                        placeholder = { Text("新名称", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (renameName.isNotBlank() && !renameLoading) confirmRename() }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = BlueMain, cursorColor = BlueMain))
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { if (!renameLoading) showRenameDialog = false }, modifier = Modifier.weight(1f), enabled = !renameLoading,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6B7280))) { Text("取消") }
                        Button(onClick = { if (renameName.isNotBlank()) confirmRename() }, modifier = Modifier.weight(1f),
                            enabled = renameName.isNotBlank() && !renameLoading, colors = ButtonDefaults.buttonColors(containerColor = BlueMain)) {
                            if (renameLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            else Text("确认")
                        }
                    }
                }
            }
        }
    }

    // ═══════ 编辑器（统一使用 CodeEditor 组件）═══════
    if (editingFile != null) {
        CodeEditor(
            title = editingFile ?: "",
            subtitle = "网站托管沙盒",
            initialContent = editingContent,
            isLoading = editLoading,
            onSave = { text, closeAfter -> saveFileContent(text, closeAfter) },
            onDismiss = { editingFile = null; editingContent = "" }
        )
    }
}

// ═══════ 文件夹行组件（与备份一致）═══════
@Composable
private fun FolderItemRow(fname: String, onClick: () -> Unit, onDeleteRequest: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F3FF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(PurpleMain.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(24.dp), tint = PurpleMain)
                }
                Spacer(Modifier.width(12.dp))
                Text(fname, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937), modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color(0xFF9CA3AF))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFDC2626)); Spacer(Modifier.width(8.dp)); Text("删除") } },
                    onClick = { showMenu = false; onDeleteRequest() })
            }
        }
    }
}

// ═══════ 文件行组件（与备份一致）═══════
@Composable
private fun FileItemRow(fname: String, size: Long, onClick: () -> Unit, onRename: () -> Unit, onDeleteRequest: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val sizeStr = remember(size) {
        when {
            size >= 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
            size >= 1024 -> String.format("%.1f KB", size / 1024.0)
            else -> "${size} B"
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(getFileIconColor(fname).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(getFileIcon(fname), contentDescription = null, modifier = Modifier.size(24.dp), tint = getFileIconColor(fname))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(fname, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$sizeStr", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                }
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFD97706)); Spacer(Modifier.width(8.dp)); Text("重命名") } },
                    onClick = { showMenu = false; onRename() })
                DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFDC2626)); Spacer(Modifier.width(8.dp)); Text("删除") } },
                    onClick = { showMenu = false; onDeleteRequest() })
            }
        }
    }
}

// ═══════ 辅助函数（与备份一致）═══════
private fun isTextFile(fileName: String): Boolean {
    return listOf(
        "txt", "md", "log", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "csv",
        "html", "htm", "css", "js", "ts", "kt", "kts", "java", "go", "py", "rs",
        "cpp", "cc", "c", "h", "hpp", "cxx", "swift", "rb", "php", "lua"
    ).contains(fileName.substringAfterLast('.', "").lowercase())
}

private fun getFileIcon(fileName: String): ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "txt", "md", "log", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "csv" -> Icons.Filled.Description
        "html", "htm", "css", "js", "ts" -> Icons.Filled.Code
        "kt", "kts", "java", "go", "py", "rs", "cpp", "cc", "c", "h", "hpp", "cxx", "swift", "rb", "php", "lua" -> Icons.Filled.Code
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico" -> Icons.Filled.Image
        "mp4", "avi", "mov", "mkv", "wmv", "flv" -> Icons.Filled.VideoFile
        "mp3", "wav", "flac", "aac", "ogg", "wma" -> Icons.Filled.AudioFile
        "zip", "rar", "7z", "tar", "gz", "bz2" -> Icons.Filled.Archive
        "pdf" -> Icons.Filled.PictureAsPdf
        "doc", "docx" -> Icons.Filled.Description
        "xls", "xlsx" -> Icons.Filled.TableChart
        "ppt", "pptx" -> Icons.Filled.Slideshow
        "apk" -> Icons.Filled.Android
        else -> Icons.Filled.InsertDriveFile
    }
}

private fun getFileIconColor(fileName: String): Color {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "txt", "md", "log", "json", "xml", "yaml", "yml" -> Color(0xFF6366F1)
        "html", "htm", "css", "js", "ts" -> Color(0xFFEAB308)
        "kt", "kts", "java", "go", "py", "rs" -> Color(0xFF2563EB)
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg" -> Color(0xFF0891B2)
        "zip", "rar", "7z", "tar", "gz" -> Color(0xFFF97316)
        "pdf" -> Color(0xFFEF4444)
        "mp3", "wav", "flac" -> Color(0xFF8B5CF6)
        "mp4", "avi", "mov", "mkv" -> Color(0xFFEC4899)
        "apk" -> Color(0xFF22C55E)
        "cpp", "cc", "c", "h", "hpp" -> Color(0xFF0D9488)
        else -> Color(0xFF6B7280)
    }
}
