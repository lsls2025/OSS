package com.aurora.chat.ui.server
import com.aurora.chat.ui.theme.LocalAppColors

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.BackHandler
import com.aurora.chat.ui.components.FileEditorHost
import com.aurora.chat.ModelLogger

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
            tint = com.aurora.chat.ui.theme.appTextPrimary()
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

    fun toast(msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

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


    // ── ZIP 预览（点击 zip 进入预览，而非以文本打开）──
    var zipPreviewPath by remember { mutableStateOf<String?>(null) }
    var zipPreviewName by remember { mutableStateOf("") }

    // ── 文本文件编辑器（统一使用 FileEditorHost 模块）──
    var editingFilePath by remember { mutableStateOf<String?>(null) }
    var editingFileName by remember { mutableStateOf("") }
    var editingContent by remember { mutableStateOf("") }
    var editingLoading by remember { mutableStateOf(false) }

    // ── 图片全屏查看（通过站点公开代理直链）──
    var viewingImageUrl by remember { mutableStateOf<String?>(null) }
    var viewingImageName by remember { mutableStateOf("") }

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
                val r = AuroraApi.renameSandboxFile(projectId, oldPath, newPath)
                if (r.success) { showRenameDialog = false; refreshFileList() }
                else android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show()
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

    // ── 文本文件：调用统一 FileEditorHost 模块打开（读通道由本页注入）──
    fun openFileEditor(path: String, name: String) {
        ModelLogger.info(ctx, "SANDBOX", "进入打开流程 path=$path name=$name projectId=$projectId")
        editingFilePath = path
        editingFileName = name
        editingContent = ""
        editingLoading = true
        scope.launch {
            try {
                val r = AuroraApi.readSandboxFile(projectId, path)
                ModelLogger.info(ctx, "SANDBOX", "读取结果 success=${r.success} len=${r.data?.optString("content","")?.length} msg=${r.message}")
                if (r.success && r.data != null) {
                    editingContent = r.data.optString("content", "")
                } else {
                    ModelLogger.error(ctx, "SANDBOX", "读取失败: ${r.message}")
                    android.widget.Toast.makeText(ctx, "读取失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                    editingFilePath = null
                }
            } catch (e: Exception) {
                ModelLogger.error(ctx, "SANDBOX", "打开异常: ${e.message}", e)
                android.widget.Toast.makeText(ctx, "打开失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                editingFilePath = null
            }
            editingLoading = false
        }
    }

    // ── 文本保存（FileEditorHost 回调）──
    fun saveFileEditor(content: String, closeAfter: Boolean) {
        val path = editingFilePath ?: return
        scope.launch {
            try {
                val r = AuroraApi.writeSandboxFile(projectId, path, content)
                if (r.success) {
                    android.widget.Toast.makeText(ctx, "保存成功", android.widget.Toast.LENGTH_SHORT).show()
                    if (closeAfter) { editingFilePath = null; editingContent = "" }
                } else {
                    android.widget.Toast.makeText(ctx, "保存失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 图片文件：拼接站点公开代理直链，全屏查看 ──
    fun openImageViewer(path: String, name: String) {
        viewingImageName = name
        viewingImageUrl = "${com.aurora.chat.data.api.AuroraApi.serverUrl}/sandbox/p/$projectId/$path"
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

    // ── 上传文件（支持上传 zip 压缩包）──
    var uploading by remember { mutableStateOf(false) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            try {
                val cr = ctx.contentResolver
                val dispName = kotlin.runCatching {
                    cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull() ?: uri.lastPathSegment ?: "file"
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("无法读取文件")
                val r = AuroraApi.uploadSandboxFile(projectId, sandboxCurrentPath, bytes, dispName)
                if (r.success) { toast("上传成功"); refreshFileList() }
                else toast(r.message)
            } catch (e: Exception) {
                toast("上传失败: ${e.message}")
            } finally { uploading = false }
        }
    }

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
                    .background(com.aurora.chat.ui.theme.appSurface())
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
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = com.aurora.chat.ui.theme.appTextPrimary())
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (sandboxCurrentPath != ".") "文件" else "文件管理",
                                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()
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
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (uploading) com.aurora.chat.ui.theme.appTextHint() else Color(0xFF7C3AED).copy(alpha = 0.1f))
                                    .clickable(enabled = !uploading, indication = null, interactionSource = remember { MutableInteractionSource() }) { uploadLauncher.launch("*/*") },
                                    contentAlignment = Alignment.Center) {
                                    if (uploading) {
                                        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF7C3AED))
                                    } else {
                                        Icon(Icons.Filled.FileUpload, contentDescription = "上传文件", tint = Color(0xFF7C3AED), modifier = Modifier.size(22.dp))
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
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
                                        Text(" / ", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                        Text(seg, fontSize = 13.sp,
                                            color = if (index == pathSegments.lastIndex) com.aurora.chat.ui.theme.appTextPrimary() else BlueMain,
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
                                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                                    trailingIcon = {
                                        IconButton(onClick = { doSearch() }) {
                                            Icon(Icons.Filled.Search, contentDescription = "搜索", tint = BlueMain, modifier = Modifier.size(20.dp))
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = com.aurora.chat.ui.theme.appDivider(),
                                        focusedBorderColor = BlueMain,
                                        unfocusedContainerColor = com.aurora.chat.ui.theme.appCardBg()
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
                                                Text(if (searchResults != null) "未找到匹配文件" else "暂无文件", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextHint())
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
                                                        },
                                                    )
                                                } else {
                                                    FileItemRow(
                                                        fname = fname, size = size,
                                                        onClick = {
                                                            val ext = fname.substringAfterLast('.', "").lowercase()
                                                            when {
                                                                ext == "zip" -> { zipPreviewName = fname; zipPreviewPath = fullPath }
                                                                isTextFile(fname) -> openFileEditor(fullPath, fname)
                                                                isImageFile(fname) -> openImageViewer(fullPath, fname)
                                                                else -> toast("该类型暂不支持在线预览，请通过网站访问或下载")
                                                            }
                                                        },
                                                        onRename = { startRename(fullPath, fname) },
                                                        onUnzip = if (fname.substringAfterLast('.', "").lowercase() == "zip") {
                                                            {
                                                                scope.launch {
                                                                    try {
                                                                        val r = AuroraApi.unzipSandboxFile(projectId, fullPath)
                                                                        if (r.success) toast("已解压") else toast(r.message)
                                                                        refreshFileList()
                                                                    } catch (e: Exception) {
                                                                        toast("解压失败: ${e.message}")
                                                                    }
                                                                }
                                                            }
                                                        } else null,
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
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(28.dp)) {
                Column {
                    Text("新建文件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("输入文件名（含扩展名，如 index.html）", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(value = newFileName, onValueChange = { newFileName = it },
                        placeholder = { Text("文件名.后缀", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (newFileName.isNotBlank() && !createFileLoading) createNewFile() }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = BlueMain, cursorColor = BlueMain))
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { showCreateFileDialog = false; newFileName = "" }, modifier = Modifier.weight(1f), enabled = !createFileLoading,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = com.aurora.chat.ui.theme.appTextSecondary())) { Text("取消") }
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
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(28.dp)) {
                Column {
                    Text("新建文件夹", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(if (sandboxCurrentPath != ".") "将在当前目录创建子文件夹" else "在根目录创建新文件夹", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it },
                        placeholder = { Text("文件夹名称", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (newFolderName.isNotBlank() && !createFolderLoading) createNewFolder() }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = Color(0xFF7C3AED), cursorColor = Color(0xFF7C3AED)))
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { showCreateFolderDialog = false; newFolderName = "" }, modifier = Modifier.weight(1f), enabled = !createFolderLoading,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = com.aurora.chat.ui.theme.appTextSecondary())) { Text("取消") }
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
                    Text("确定要删除${if (deleteTargetIsDir) "文件夹" else "文件"}", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEF2F2)).padding(12.dp)) {
                        Text("\"$deleteTargetName\"", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF991B1B))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("此操作不可撤销。", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
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
            Box(Modifier.width(340.dp).clip(RoundedCornerShape(24.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(28.dp)) {
                Column {
                    Text("重命名", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("输入新名称", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(value = renameName, onValueChange = { renameName = it },
                        placeholder = { Text("新名称", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (renameName.isNotBlank() && !renameLoading) confirmRename() }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFFD1D5DB), focusedBorderColor = BlueMain, cursorColor = BlueMain))
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { if (!renameLoading) showRenameDialog = false }, modifier = Modifier.weight(1f), enabled = !renameLoading,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = com.aurora.chat.ui.theme.appTextSecondary())) { Text("取消") }
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

    // ═══════ ZIP 预览对话框 ═══════
    if (zipPreviewPath != null) {
        ZipPreviewDialog(
            projectId = projectId,
            zipPath = zipPreviewPath ?: "",
            zipName = zipPreviewName,
            onDismiss = { zipPreviewPath = null },
            onUnzipped = { zipPreviewPath = null; refreshFileList() }
        )
    }

    // ═══════ 文本文件编辑器（统一使用 FileEditorHost 模块）═══════
    if (editingFilePath != null) {
        FileEditorHost(
            title = editingFileName,
            subtitle = "网站托管文件",
            initialContent = editingContent,
            isLoading = editingLoading,
            onSave = { text, closeAfter -> saveFileEditor(text, closeAfter) },
            onDismiss = { editingFilePath = null; editingContent = "" }
        )
    }

    // ═══════ 图片全屏查看（站点公开代理直链，Coil 加载）═══════
    viewingImageUrl?.let { url ->
        BackHandler { viewingImageUrl = null }
        Box(
            Modifier.fillMaxSize().background(Color(0xFF111827))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { viewingImageUrl = null }
                .systemBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { viewingImageUrl = null },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White) }
                Spacer(Modifier.width(8.dp))
                Text(
                    viewingImageName, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appDivider(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
            }
            coil.compose.AsyncImage(
                model = url,
                contentDescription = "图片查看",
                modifier = Modifier.fillMaxSize().padding(top = 52.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

// ═══════ 文件夹行组件（与备份一致）═══════
@Composable
private fun FolderItemRow(fname: String, onClick: () -> Unit, onDeleteRequest: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(PurpleMain.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(24.dp), tint = PurpleMain)
                }
                Spacer(Modifier.width(12.dp))
                Text(fname, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = com.aurora.chat.ui.theme.appTextHint())
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
private fun FileItemRow(fname: String, size: Long, onClick: () -> Unit, onRename: () -> Unit, onUnzip: (() -> Unit)? = null, onDeleteRequest: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val sizeStr = remember(size) {
        when {
            size >= 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
            size >= 1024 -> String.format("%.1f KB", size / 1024.0)
            else -> "${size} B"
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(getFileIconColor(fname).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(getFileIcon(fname), contentDescription = null, modifier = Modifier.size(24.dp), tint = getFileIconColor(fname))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(fname, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$sizeStr", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                if (onUnzip != null) {
                    DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF7C3AED)); Spacer(Modifier.width(8.dp)); Text("解压") } },
                        onClick = { showMenu = false; onUnzip() })
                }
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

private fun isImageFile(fileName: String): Boolean {
    return listOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico"
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
        else -> com.aurora.chat.ui.theme.appTextSecondary()
    }
}

private fun formatFileSize(size: Long): String {
    return when {
        size >= 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        size >= 1024 -> String.format("%.1f KB", size / 1024.0)
        else -> "$size B"
    }
}

// ═══════ ZIP 预览对话框 ═══════
@Composable
private fun ZipPreviewDialog(
    projectId: Long,
    zipPath: String,
    zipName: String,
    onDismiss: () -> Unit,
    onUnzipped: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var unzipping by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        try {
            val r = AuroraApi.zipInfoSandboxFile(projectId, zipPath)
            if (r.success && r.data != null) {
                val arr = r.data.optJSONArray("entries")
                val lst = mutableListOf<JSONObject>()
                if (arr != null) for (i in 0 until arr.length()) lst.add(arr.getJSONObject(i))
                entries = lst
            } else loadError = r.message
        } catch (e: Exception) {
            loadError = e.message
        }
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.widthIn(max = 440.dp).fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(24.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF97316).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Archive, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color(0xFFF97316))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ZIP 压缩包", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        Text(zipName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(if (loading) "正在读取压缩包…" else "包含 ${entries.size} 个条目", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg()).verticalScroll(rememberScrollState())
                ) {
                    when {
                        loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = BlueMain, modifier = Modifier.size(28.dp))
                        }
                        loadError != null -> Text(loadError ?: "", fontSize = 13.sp, color = Color(0xFFDC2626), modifier = Modifier.padding(16.dp))
                        entries.isEmpty() -> Text("压缩包为空", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.padding(16.dp))
                        else -> Column {
                            entries.forEach { e ->
                                val ename = e.optString("name", "")
                                val esize = e.optLong("size", 0)
                                val eisDir = e.optBoolean("is_dir", false)
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (eisDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp), tint = com.aurora.chat.ui.theme.appTextHint())
                                    Spacer(Modifier.width(8.dp))
                                    Text(ename, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!eisDir) Text(formatFileSize(esize), fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = com.aurora.chat.ui.theme.appTextSecondary())) {
                        Text("关闭")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                unzipping = true
                                try {
                                    val r = AuroraApi.unzipSandboxFile(projectId, zipPath)
                                    if (r.success) { toast("已解压"); onUnzipped() } else toast(r.message)
                                } catch (e: Exception) {
                                    toast("解压失败: ${e.message}")
                                }
                                unzipping = false
                            }
                        },
                        modifier = Modifier.weight(1f), enabled = !unzipping,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                    ) {
                        if (unzipping) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text("解压", color = Color.White)
                    }
                }
            }
        }
    }
}
