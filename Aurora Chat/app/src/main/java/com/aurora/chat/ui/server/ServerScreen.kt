package com.aurora.chat.ui.server

import android.content.Context
import com.aurora.chat.data.local.ServerCache
import com.aurora.chat.data.api.BreadcrumbItem
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.data.api.ApiResult
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.ServerFileInfo
import com.aurora.chat.data.api.ServerInfo
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.aurora.chat.ui.theme.LocalAppColors

// ──────────────────────────────────────────────
// 高级蓝色系配色
// ──────────────────────────────────────────────
private val BlueMain = Color(0xFF2563EB)       // 主蓝
private val BlueLight = Color(0xFF60A5FA)      // 浅蓝
private val BlueDark = Color(0xFF1D4ED8)       // 深蓝
private val BlueGradient = listOf(BlueMain, BlueDark)
private val SurfaceLight = Color(0xFFF8FAFC)
private val CardBg = Color(0xFFFFFFFF)

// ──────────────────────────────────────────────
// 通用工具：设置全屏 Dialog Window 的系统栏颜色 + 清除窗口背景
// 从底层彻底杜绝灰色/白色异常区域
// ──────────────────────────────────────────────
@Composable
internal fun SetDialogSystemBarColors(
    statusBarColor: Int,
    navigationBarColor: Int,
    lightStatusBars: Boolean = false
) {
    val view = LocalView.current
    SideEffect {
        val win = findDialogWindow(view)
        if (win != null) {
            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            win.statusBarColor = statusBarColor
            win.navigationBarColor = navigationBarColor
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            win.setDimAmount(0f)
            // 依据状态栏背景深浅设置图标颜色，避免白底上出现看不见的浅色图标
            val controller = androidx.core.view.WindowCompat.getInsetsController(win, win.decorView)
            controller.isAppearanceLightStatusBars = lightStatusBars
            controller.isAppearanceLightNavigationBars = lightStatusBars
        }
    }
}

/** 多方式查找 Dialog 的 Window 对象 */
private fun findDialogWindow(view: android.view.View?): android.view.Window? {
    // 方式1：DialogWindowProvider 父级遍历（标准 Compose 途径）
    var p: Any? = view?.parent
    while (p != null) {
        if (p is DialogWindowProvider) return p.window
        p = (p as? android.view.View)?.parent
    }
    // 方式2：从 View Context 向上找 Dialog（兼容某些版本）
    var ctx: android.content.Context? = view?.context
    while (ctx != null) {
        if (ctx is android.app.Dialog) return ctx.window
        ctx = if (ctx is android.content.ContextWrapper) ctx.baseContext else null
    }
    return null
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    direct: Boolean = false,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 当前显示的服务器信息 ──
    var myServer by remember { mutableStateOf<ServerInfo?>(null) }
    var initialLoading by remember { mutableStateOf(true) }
    // ── 服务器详情页（direct：作为页面直接渲染，初始即显示，避免进入时的空白闪现） ──
    var showServerDetail by remember { mutableStateOf(direct) }
    // 控制内部内容动画（Dialog 打开后才播放滑入）
    var detailContentVisible by remember { mutableStateOf(false) }
    // ── 文件页面 ──
    var showFilePage by remember { mutableStateOf(false) }
    var filePageVisible by remember { mutableStateOf(false) }
    // ── 沙盒项目 ──
    var showProjectFiles by remember { mutableStateOf(false) }
    // ── 部署弹窗 ──
    var showDeployDialog by remember { mutableStateOf(false) }
    // 文件列表数据
    var fileList by remember { mutableStateOf<List<ServerFileInfo>>(emptyList()) }
    var fileListLoading by remember { mutableStateOf(false) }
    // ── 当前文件夹导航 ──
    var currentFolderId by remember { mutableStateOf<Long?>(null) }
    var folderBreadcrumb by remember { mutableStateOf<List<BreadcrumbItem>>(emptyList()) }

    // ── 文件搜索 ──
    var searchKeyword by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ServerFileInfo>?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    // ── 文件重命名 ──
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ServerFileInfo?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var renameLoading by remember { mutableStateOf(false) }
    // ── 新建文件/文件夹弹窗 ──
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var createFileLoading by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var createFolderLoading by remember { mutableStateOf(false) }
    // ── 开关机确认弹窗 ──
    var showShutdownConfirmDialog by remember { mutableStateOf(false) }
    var showRestartConfirmDialog by remember { mutableStateOf(false) }
    // ── 文件编辑器 ──
    var editingFile by remember { mutableStateOf<ServerFileInfo?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var editingLoading by remember { mutableStateOf(false) }
    var editingIsImage by remember { mutableStateOf(false) }
    var editingImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    // 编辑器扩展功能
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showReplaceDialog by remember { mutableStateOf(false) }
    var replaceFind by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var showConvertDialog by remember { mutableStateOf(false) }
    var convertFrom by remember { mutableStateOf("") }
    var convertTo by remember { mutableStateOf("") }
    var editKeyboardVisible by remember { mutableStateOf(true) }
    // WebView 浏览
    var showWebViewDialog by remember { mutableStateOf(false) }
    var webViewUrl by remember { mutableStateOf("") }
    // ── 删除确认弹窗 ──
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var deleteTargetFileId by remember { mutableStateOf(0L) }
    var deleteTargetFileType by remember { mutableStateOf("文件") }


    // ── 获取文件列表（支持离线缓存） ──
    fun refreshFileList() {
        scope.launch {
            fileListLoading = true
            try {
                val result = ChatRepository.getServerFiles(currentFolderId)
                if (result.success) {
                    fileList = result.data ?: emptyList()
                    // 缓存文件列表到本地
                    if (myServer != null) {
                        try {
                            val arr = org.json.JSONArray()
                            result.data?.forEach { f ->
                                arr.put(org.json.JSONObject().apply {
                                    put("id", f.id)
                                    put("name", f.fileName)
                                    put("mimeType", f.mimeType)
                                    put("size", f.fileSize)
                                    put("parentId", f.parentId ?: 0)
                                })
                            }
                            ServerCache.saveFileList(context, myServer?.id ?: 0L, currentFolderId ?: 0L, arr.toString())
                        } catch (_: Exception) {}
                    }
                } else if (!ServerCache.isOnline(context) && myServer != null) {
                    // 无网络时从本地缓存恢复
                    val cached = ServerCache.loadFileList(context, myServer?.id ?: 0L, currentFolderId ?: 0L)
                    if (cached != null) {
                        try {
                            val arr = org.json.JSONArray(cached)
                            fileList = (0 until arr.length()).map { i ->
                                val obj = arr.getJSONObject(i)
                                com.aurora.chat.data.api.ServerFileInfo(
                                    id = obj.getLong("id"),
                                    fileName = obj.getString("name"),
                                    mimeType = obj.optString("mimeType", ""),
                                    fileSize = obj.optLong("size", 0),
                                    parentId = if (obj.has("parentId")) obj.getLong("parentId") else null,
                                    downloadUrl = ""
                                )
                            }
                        } catch (_: Exception) {}
                    }
                } else {
                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "加载文件列表失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            fileListLoading = false
        }
    }
    // ── 获取面包屑 ──
    fun refreshBreadcrumb() {
        scope.launch {
            try {
                val result = ChatRepository.getFolderBreadcrumb(currentFolderId)
                if (result.success) {
                    folderBreadcrumb = result.data ?: emptyList()
                }
            } catch (_: Exception) { }
        }
    }
    // ── 进入文件夹 ──
    fun enterFolder(folderId: Long) {
        currentFolderId = folderId
        refreshBreadcrumb()
        refreshFileList()
    }
    // ── 返回上级目录 ──
    fun goToParentFolder() {
        if (folderBreadcrumb.isNotEmpty()) {
            val parentIdx = folderBreadcrumb.size - 2
            if (parentIdx >= 0) {
                currentFolderId = folderBreadcrumb[parentIdx].id
            } else {
                currentFolderId = null
            }
        } else {
            currentFolderId = null
        }
        refreshBreadcrumb()
        refreshFileList()
    }
    // ── 跳转到面包屑中的目录 ──
    fun goToBreadcrumb(index: Int) {
        if (index < 0) {
            currentFolderId = null
        } else {
            currentFolderId = folderBreadcrumb[index].id
        }
        folderBreadcrumb = folderBreadcrumb.take(index + 1)
        refreshFileList()
    }

    // 打开详情页
    fun openServerDetail() {
        showServerDetail = true
        // 进入详情页时刷新服务器状态
        scope.launch {
            try {
                val result = ChatRepository.getMyServer()
                if (result.success && result.data != null) {
                    myServer = result.data
                }
            } catch (_: Exception) { }
            delay(100) // 等待 Dialog 渲染完成
            detailContentVisible = true
        }
    }

    // 关闭详情页（先播退出动画再关闭 Dialog）
    fun closeServerDetail() {
        scope.launch {
            detailContentVisible = false
            delay(300)
            showServerDetail = false
        }
    }

    // ── 文件页面 ──
    fun openFilePage() {
        showFilePage = true
        scope.launch {
            delay(100)
            filePageVisible = true
        }
    }

    fun closeFilePage() {
        scope.launch {
            filePageVisible = false
            delay(300)
            showFilePage = false
        }
    }

    // 上传文件函数（支持上传到当前文件夹）
    fun handleFileUpload(ctx: android.content.Context, uri: android.net.Uri) {
        scope.launch {
            val inputStream = try { ctx.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
            if (inputStream == null) {
                android.widget.Toast.makeText(ctx, "无法读取文件", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                val fileName = getFileNameFromUri(ctx, uri)
                android.widget.Toast.makeText(ctx, "正在上传: $fileName", android.widget.Toast.LENGTH_SHORT).show()
                val result = ChatRepository.uploadServerFile(inputStream, fileName, currentFolderId)
                if (result.success) {
                    android.widget.Toast.makeText(ctx, "上传成功", android.widget.Toast.LENGTH_SHORT).show()
                    refreshFileList()
                } else {
                    android.widget.Toast.makeText(ctx, result.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "上传失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                try { inputStream.close() } catch (_: Exception) {}
            }
        }
    }

    // 上传文件 launcher
    val fileUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { handleFileUpload(context, it) }
    }

    fun createNewFile() {
        scope.launch {
            createFileLoading = true
            try {
                val result = ChatRepository.createServerFile(newFileName.trim(), "", currentFolderId)
                if (result.success) {
                    android.widget.Toast.makeText(context, "创建成功", android.widget.Toast.LENGTH_SHORT).show()
                    showCreateFileDialog = false
                    newFileName = ""
                    refreshFileList()
                } else {
                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "创建失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            createFileLoading = false
        }
    }

    fun createNewFolder() {
        if (newFolderName.isBlank()) return
        scope.launch {
            createFolderLoading = true
            try {
                val result = ChatRepository.createServerFolder(newFolderName.trim(), currentFolderId)
                if (result.success) {
                    android.widget.Toast.makeText(context, "文件夹创建成功", android.widget.Toast.LENGTH_SHORT).show()
                    showCreateFolderDialog = false
                    newFolderName = ""
                    refreshFileList()
                    refreshBreadcrumb()
                } else {
                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "创建文件夹失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            createFolderLoading = false
        }
    }

    // ── 文件搜索 ──
    fun doSearch() {
        val kw = searchKeyword.trim()
        if (kw.isEmpty()) {
            searchResults = null
            return
        }
        scope.launch {
            isSearching = true
            try {
                val result = ChatRepository.searchServerFiles(kw)
                if (result.success) {
                    searchResults = result.data ?: emptyList()
                } else {
                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "搜索失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            isSearching = false
        }
    }

    // ── 文件重命名 ──
    fun startRename(file: ServerFileInfo) {
        renameTarget = file
        renameValue = file.fileName
        showRenameDialog = true
    }

    fun doRename() {
        val newName = renameValue.trim()
        if (newName.isEmpty()) return
        val target = renameTarget ?: return
        scope.launch {
            renameLoading = true
            try {
                val result = ChatRepository.renameServerFile(target.id, newName)
                if (result.success) {
                    android.widget.Toast.makeText(context, "重命名成功", android.widget.Toast.LENGTH_SHORT).show()
                    showRenameDialog = false
                    renameTarget = null
                    refreshFileList()
                } else {
                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "重命名失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            renameLoading = false
        }
    }

    fun deleteFileById(fileId: Long) {
        scope.launch {
            try {
                val result = ChatRepository.deleteServerFile(fileId)
                if (result.success) {
                    android.widget.Toast.makeText(context, "已删除", android.widget.Toast.LENGTH_SHORT).show()
                    refreshFileList()
                } else {
                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "删除失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openFileEditor(file: ServerFileInfo) {
        editingFile = file
        editingContent = ""
        editingIsImage = false
        editingImageBytes = null
        val ext = file.fileName.substringAfterLast('.', "").lowercase()
        val isImage = ext in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico")
        editingLoading = true
        scope.launch {
            try {
                val result = ChatRepository.downloadFileContent(file.id)
                if (result.success && result.data != null) {
                    if (isImage) {
                        editingIsImage = true
                        editingImageBytes = result.data
                    } else {
                        editingContent = try {
                            String(result.data, Charsets.UTF_8)
                        } catch (_: Exception) {
                            "[二进制文件，无法以文本显示]"
                        }
                    }
                } else {
                    android.widget.Toast.makeText(context, "下载失败: ${result.message}", android.widget.Toast.LENGTH_SHORT).show()
                    editingFile = null
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "打开文件失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                editingFile = null
            }
            editingLoading = false
        }
    }

    // ── 返回键层级管理 ──
    BackHandler(enabled = showFilePage) { closeFilePage() }
    BackHandler(enabled = showServerDetail && !showFilePage) { if (direct) onBack() else closeServerDetail() }

    // ── 热加载安全：强制超时解锁loading（防止 LaunchedEffect 不触发） ──
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        initialLoading = false
    }

    // ── 启动时拉取我的服务器（用于「网站托管」入口展示） ──
    LaunchedEffect(Unit) {
        try {
            val result = ChatRepository.getMyServer()
            if (result.success && result.data != null) {
                myServer = result.data
            } else {
                // 未登录或查询失败：使用本地默认服务器，让用户可直接使用网站托管
                myServer = ServerInfo(
                    id = 0,
                    ownerUserId = com.aurora.chat.data.api.AuroraApi.currentUserId,
                    ownerUsername = com.aurora.chat.data.api.AuroraApi.currentUserName,
                    name = "我的网站",
                    domain = "local",
                    serverUrl = "",
                    status = "stopped"
                )
            }
        } catch (_: Exception) {
            myServer = ServerInfo(0, 0, "", "我的网站", "local", "", status = "stopped")
        }
        initialLoading = false
    }



    // ═════════════════════════════════════════════════════
    // 外层容器：包裹主界面和所有全屏覆盖层
    // 覆盖层声明在后，利用 Compose Z-order 堆叠在顶层
    // ═════════════════════════════════════════════════════
    val appColors = LocalAppColors.current

    Box(Modifier.fillMaxSize()) {
        // 主界面（透明背景，让全局壁纸透出；默认壁纸模式下由全局最外层白色兜底）
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))

            // ────────── 内容区 ──────────
            if (direct) {
                // 从「我的」网站托管进入：主界面仅作为空白底板，真正内容由右侧滑入的详情页呈现
                Spacer(Modifier.height(28.dp))
            } else {
                // 联系人 tab：已清理干净，暂不展示任何工具入口，待日后新增
                Spacer(Modifier.height(140.dp))
                Icon(
                    Icons.Filled.Language,
                    contentDescription = null,
                    tint = Color(0xFFE5E7EB),
                    modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(20.dp))
                Text("暂无内容", fontSize = 16.sp, color = Color(0xFF9CA3AF),
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Text("功能整理中，敬请期待", fontSize = 13.sp, color = Color(0xFFB0B7C0),
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // ═════════════════════════════════════════════════════
    // 右侧滑入界面（Dialog 全屏覆盖底部导航栏和标题栏）
    // ═════════════════════════════════════════════════════
    if (showServerDetail) {
        // 网站托管详情：作为页面直接渲染（由外层 AnimatedContent 负责滑入/滑出），
        // 不再嵌套 Dialog，避免双层弹窗导致的过度跳转与返回后残影卡死
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .systemBarsPadding()
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部返回栏
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("←", fontSize = 24.sp, color = Color(0xFF1E40AF),
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onBack() })
                    Spacer(Modifier.weight(1f))
                    Text("网站托管", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(48.dp))
                }
                HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE5E7EB))
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp)
                    ) {
                            if (myServer == null) {
                                // 未登录或无服务器：显示引导界面
                                val ctx = LocalContext.current
                                Spacer(Modifier.height(60.dp))
                                Icon(Icons.Filled.Language, contentDescription = null,
                                    modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
                                    tint = Color(0xFFD1D5DB))
                                Spacer(Modifier.height(16.dp))
                                Text("网站托管", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1F2937), modifier = Modifier.align(Alignment.CenterHorizontally))
                                Spacer(Modifier.height(8.dp))
                                Text("将你的 HTML 静态网站部署到云端，生成可公开访问的链接",
                                    fontSize = 13.sp, color = Color(0xFF6B7280),
                                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 32.dp),
                                    lineHeight = 18.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(24.dp))
                                Button(
                                    onClick = {
                                        // 如果未登录，提示登录
                                        if (com.aurora.chat.data.api.AuroraApi.authToken == null) {
                                            android.widget.Toast.makeText(ctx, "请先登录后再使用网站托管", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            showDeployDialog = true
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(min = 200.dp).height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BlueMain)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("创建服务器", color = Color.White, fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.height(12.dp))
                                Text("需要登录后才能创建和管理服务器",
                                    fontSize = 12.sp, color = Color(0xFF9CA3AF),
                                    modifier = Modifier.align(Alignment.CenterHorizontally))
                            } else {
                                val s = myServer!!
                                // ── 状态顶部区域 ──
                                val clipboardManager = LocalClipboardManager.current
                                val context = LocalContext.current
                                // 状态颜色映射
                                val statusColor = when (s.status) {
                                    "running" -> Color(0xFF059669)
                                    "stopping" -> Color(0xFFD97706)
                                    "restarting" -> Color(0xFFD97706)
                                    "stopped" -> Color(0xFFDC2626)
                                    else -> Color(0xFF6B7280)
                                }
                                val statusText = when (s.status) {
                                    "running" -> "运行中"
                                    "stopping" -> "关机中..."
                                    "restarting" -> "重启中..."
                                    "stopped" -> "已关机"
                                    "offline" -> "已关机"
                                    "" -> "已关机"
                                    else -> "未知"
                                }
                                val isTransitioning = s.status == "stopping" || s.status == "restarting"
                                val isStoppedOrOffline = s.status == "stopped" || s.status == "offline" || s.status == ""

                                // 状态轮询：过渡状态时每2秒查询一次
                                if (isTransitioning) {
                                    LaunchedEffect(s.status) {
                                        while (true) {
                                            delay(2000)
                                            try {
                                                val statusResult = ChatRepository.getServerStatus()
                                                if (statusResult.success && statusResult.data != null) {
                                                    val newStatus = statusResult.data
                                                    if (newStatus != s.status) {
                                                        myServer = s.copy(status = newStatus)
                                                    }
                                                    if (newStatus == "running" || newStatus == "stopped") break
                                                }
                                            } catch (_: Exception) { break }
                                        }
                                    }
                                }

                                // ── 名称 + 大状态指示 ──
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.08f))
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                                        Column {
                                            Text(s.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                                            Spacer(Modifier.height(4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(statusText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = statusColor)
                                                if (isTransitioning) {
                                                    Spacer(Modifier.width(8.dp))
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        color = statusColor,
                                                        strokeWidth = 2.dp
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = serverFileUrl(s.serverUrl, s.id),
                                                fontSize = 13.sp, color = Color(0xFF059669),
                                                modifier = Modifier.clickable(
                                                    indication = null, interactionSource = remember { MutableInteractionSource() }
                                                ) {
                                                    val url = serverFileUrl(s.serverUrl, s.id)
                                                    clipboardManager.setText(AnnotatedString(url))
                                                    android.widget.Toast.makeText(context, "地址已复制", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(20.dp))

                                // ── 开关机/重启按钮（靠右对齐） ──
                                if (!isTransitioning) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 打开网站按钮（运行中才显示，放在关机左侧）
                                        if (s.status == "running") {
                                            Button(
                                                onClick = {
                                                    val url = serverFileUrl(s.serverUrl, s.id)
                                                    if (url.isNotEmpty()) {
                                                        webViewUrl = url
                                                        showWebViewDialog = true
                                                    }
                                                },
                                                modifier = Modifier.height(44.dp),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = BlueMain)
                                            ) {
                                                Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("打开", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            Spacer(Modifier.width(10.dp))
                                        }
                                        // 关机按钮（运行中才显示）
                                        if (s.status == "running") {
                                            Button(
                                                onClick = { showShutdownConfirmDialog = true },
                                                modifier = Modifier.height(44.dp),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                                            ) {
                                                Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("关机", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            Spacer(Modifier.width(10.dp))
                                        }
                                        // 重启按钮（运行中、已关机或 offline 都显示）
                                        if (s.status == "running" || isStoppedOrOffline) {
                                            Button(
                                                onClick = { showRestartConfirmDialog = true },
                                                modifier = Modifier.height(44.dp),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = if (isStoppedOrOffline) Color(0xFF059669) else Color(0xFFD97706))
                                            ) {
                                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(if (isStoppedOrOffline) "开机" else "重启", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                } else {
                                    // 过渡状态：显示进度条
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7))
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = Color(0xFFD97706),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                text = statusText,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFF92400E)
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(24.dp))

                                // ── 文件管理入口 ──
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        // 项目
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(Color(0xFF7C3AED).copy(alpha = 0.1f))
                                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                                        openFilePage()
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Filled.Folder, contentDescription = "文件管理", modifier = Modifier.size(28.dp), tint = Color(0xFF7C3AED))
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text("文件管理", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
                                        }
                                        // 刷新状态
                                        var refreshClickCount by remember { mutableIntStateOf(0) }
                                        val refreshRotate by animateFloatAsState(
                                            targetValue = refreshClickCount * 360f,
                                            animationSpec = tween(600, easing = LinearEasing)
                                        )
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(Color(0xFF059669).copy(alpha = 0.1f))
                                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                                        refreshClickCount++
                                                        scope.launch {
                                                            try {
                                                                val result = ChatRepository.getServerStatus()
                                                                if (result.success && result.data != null) {
                                                                    myServer = s.copy(status = result.data)
                                                                    android.widget.Toast.makeText(context, "状态已刷新", android.widget.Toast.LENGTH_SHORT).show()
                                                                }
                                                            } catch (_: Exception) { }
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Filled.Refresh, contentDescription = null,
                                                    modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = refreshRotate },
                                                    tint = Color(0xFF059669))
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text("刷新状态", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
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

    // ═════════════════════════════════════════════════════
    // 项目文件管理
    // ═════════════════════════════════════════════════════
    // ═════════════════════════════════════════════════════
    // 文件页面（覆盖在服务器详情之上，层级高于详情）
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
            val fpView = LocalView.current
            SideEffect {
                var p: Any? = fpView.parent
                while (p != null) {
                    if (p is DialogWindowProvider) {
                        val win = p.window
                        win.statusBarColor = android.graphics.Color.parseColor("#FFF8FAFC")
                        win.navigationBarColor = android.graphics.Color.parseColor("#FFF8FAFC")
                        win.setDimAmount(0f)
                        break
                    }
                    p = (p as? android.view.View)?.parent
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF8FAFC))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { /* 防事件穿透 */ }
            ) {
                AnimatedVisibility(
                    visible = filePageVisible,
                    enter = slideInHorizontally { it },
                    exit = slideOutHorizontally { it }
                ) {
                    // 进入页面时加载文件列表和面包屑
                    LaunchedEffect(filePageVisible) {
                        if (filePageVisible) {
                            refreshFileList()
                            refreshBreadcrumb()
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC))
                    ) {
                    Column(
                        modifier = Modifier.fillMaxSize().statusBarsPadding()
                    ) {
                            // ── 顶栏 ──
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 返回按钮（在子文件夹中返回上级，在根目录关闭）
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) {
                                            if (currentFolderId != null) goToParentFolder()
                                            else closeFilePage()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (currentFolderId != null) "文件" else "文件管理",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1F2937)
                                )
                                Spacer(Modifier.weight(1f))
                                // 新建菜单（点击加号弹选项）
                                var showCreateMenu by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF2563EB).copy(alpha = 0.1f))
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { showCreateMenu = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "新建", tint = BlueMain, modifier = Modifier.size(22.dp))
                                    DropdownMenu(
                                        expanded = showCreateMenu,
                                        onDismissRequest = { showCreateMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Row { Icon(Icons.Filled.Description, null, Modifier.size(18.dp), tint = BlueMain); Spacer(Modifier.width(8.dp)); Text("新建文件") } },
                                            onClick = { showCreateMenu = false; showCreateFileDialog = true }
                                        )
                                        DropdownMenuItem(
                                            text = { Row { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp), tint = Color(0xFF7C3AED)); Spacer(Modifier.width(8.dp)); Text("新建文件夹") } },
                                            onClick = { showCreateMenu = false; showCreateFolderDialog = true }
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                // 上传文件按钮
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF059669).copy(alpha = 0.1f))
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { fileUploadLauncher.launch("*/*") },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Upload, contentDescription = "上传文件", tint = Color(0xFF059669), modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                            }

                            // ── 面包屑导航 ──
                            if (currentFolderId != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 根目录链接
                                    Text(
                                        text = "根目录",
                                        fontSize = 13.sp,
                                        color = BlueMain,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { goToBreadcrumb(-1) }
                                    )
                                    folderBreadcrumb.forEachIndexed { index, item ->
                                        Text(" / ", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                                        Text(
                                            text = item.name,
                                            fontSize = 13.sp,
                                            color = if (index == folderBreadcrumb.lastIndex) Color(0xFF1F2937) else BlueMain,
                                            fontWeight = if (index == folderBreadcrumb.lastIndex) FontWeight.SemiBold else FontWeight.Medium,
                                            modifier = Modifier.clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) { goToBreadcrumb(index) }
                                        )
                                    }
                                }
                            }

                            // ── 搜索栏 ──
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
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
                                                Text(
                                                    if (searchResults != null) "未找到匹配文件" else "暂无文件",
                                                    fontSize = 16.sp, color = Color(0xFF9CA3AF)
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    if (searchResults != null) "尝试其他关键词" else "点击右上角按钮新建或上传文件",
                                                    fontSize = 13.sp, color = Color(0xFFD1D5DB)
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                                        ) {
                                            items(displayList, key = { it.id }) { item ->
                                                if (item.isDir) {
                                                    // 文件夹行
                                                    FolderItemRow(
                                                        folder = item,
                                                        onClick = { enterFolder(item.id) },
                                                        onRename = { startRename(item) },
                                                        onDeleteRequest = {
                                                            deleteTargetFileId = item.id
                                                            deleteTargetFileType = "文件夹"
                                                            showDeleteConfirmDialog = true
                                                        }
                                                    )
                                                } else {
                                                    // 文件行
                                                    FileItemRow(
                                                        file = item,
                                                        onClick = { openFileEditor(item) },
                                                        onRename = { startRename(item) },
                                                        onDeleteRequest = {
                                                            deleteTargetFileId = item.id
                                                            deleteTargetFileType = "文件"
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

    // ═════════════════════════════════════════════════════
    // 新建文件弹窗
    // ═════════════════════════════════════════════════════
    if (showCreateFileDialog) {
        Dialog(
            onDismissRequest = { if (!createFileLoading) { showCreateFileDialog = false; newFileName = "" } },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .width(340.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .padding(28.dp)
            ) {
                Column {
                    Text(
                        text = "新建文件",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "输入文件名（含扩展名，如 index.html）",
                        fontSize = 12.sp, color = Color(0xFF9CA3AF),
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))

                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        placeholder = { Text("文件名.后缀", fontSize = 14.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (newFileName.isNotBlank() && !createFileLoading) createNewFile() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color(0xFFD1D5DB),
                            focusedBorderColor = BlueMain, cursorColor = BlueMain
                        )
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { showCreateFileDialog = false; newFileName = "" },
                            modifier = Modifier.weight(1f),
                            enabled = !createFileLoading,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6B7280))
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = { if (newFileName.isNotBlank()) createNewFile() },
                            modifier = Modifier.weight(1f),
                            enabled = newFileName.isNotBlank() && !createFileLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = BlueMain)
                        ) {
                            if (createFileLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Text("创建")
                            }
                        }
                    }
                }
            }
        }
    }



    // ═════════════════════════════════════════════════════
    // 新建文件夹弹窗
    // ═════════════════════════════════════════════════════
    if (showCreateFolderDialog) {
        Dialog(
            onDismissRequest = { if (!createFolderLoading) { showCreateFolderDialog = false; newFolderName = "" } },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .width(340.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .padding(28.dp)
            ) {
                Column {
                    Text(
                        text = "新建文件夹",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (currentFolderId != null) "将在当前目录创建子文件夹" else "在根目录创建新文件夹",
                        fontSize = 12.sp, color = Color(0xFF9CA3AF),
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))

                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        placeholder = { Text("文件夹名称", fontSize = 14.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (newFolderName.isNotBlank() && !createFolderLoading) createNewFolder() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color(0xFFD1D5DB),
                            focusedBorderColor = Color(0xFF7C3AED), cursorColor = Color(0xFF7C3AED)
                        )
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { showCreateFolderDialog = false; newFolderName = "" },
                            modifier = Modifier.weight(1f),
                            enabled = !createFolderLoading,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF6B7280))
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = { if (newFolderName.isNotBlank()) createNewFolder() },
                            modifier = Modifier.weight(1f),
                            enabled = newFolderName.isNotBlank() && !createFolderLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                        ) {
                            if (createFolderLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Text("创建")
                            }
                        }
                    }
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════
    // 关机确认弹窗
    // ═════════════════════════════════════════════════════
    if (showShutdownConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showShutdownConfirmDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("确认关机", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column {
                    Text("关机将执行以下操作：", fontSize = 14.sp, color = Color(0xFF374151))
                    Spacer(Modifier.height(12.dp))
                    Text("• 清理服务器缓存文件", fontSize = 13.sp, color = Color(0xFF6B7280))
                    Text("• 停止所有文件服务", fontSize = 13.sp, color = Color(0xFF6B7280))
                    Text("• 其他用户访问将提示「服务器已关机」", fontSize = 13.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(8.dp))
                    Text("确定要关机吗？", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showShutdownConfirmDialog = false
                        scope.launch {
                            try {
                                val result = ChatRepository.shutdownServer()
                                if (result.success) {
                                    myServer = myServer?.copy(status = "stopping")
                                    android.widget.Toast.makeText(context, "关机指令已发送", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "关机失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) { Text("确认关机", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showShutdownConfirmDialog = false }) { Text("取消") }
            }
        )
    }

    // ═════════════════════════════════════════════════════
    // 重启/开机确认弹窗
    // ═════════════════════════════════════════════════════
    if (showRestartConfirmDialog) {
        val isStopped = myServer?.status == "stopped"
        AlertDialog(
            onDismissRequest = { showRestartConfirmDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isStopped) "确认开机" else "确认重启", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column {
                    Text(
                        if (isStopped) "开机将执行以下操作：" else "重启将执行以下操作：",
                        fontSize = 14.sp, color = Color(0xFF374151)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("• 清理服务器缓存文件", fontSize = 13.sp, color = Color(0xFF6B7280))
                    Text(if (isStopped) "• 启动服务器服务" else "• 重新启动服务器服务", fontSize = 13.sp, color = Color(0xFF6B7280))
                    Text("• 恢复正常文件访问", fontSize = 13.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isStopped) "确定要开机吗？" else "确定要重启吗？",
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestartConfirmDialog = false
                        scope.launch {
                            try {
                                val result = ChatRepository.restartServer()
                                if (result.success) {
                                    myServer = myServer?.copy(status = "restarting")
                                    android.widget.Toast.makeText(context, if (isStopped) "开机指令已发送" else "重启指令已发送", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "操作失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                ) { Text(if (isStopped) "确认开机" else "确认重启", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirmDialog = false }) { Text("取消") }
            }
        )
    }

    // ═════════════════════════════════════════════════════
    // 重命名对话框
    // ═════════════════════════════════════════════════════
    if (showRenameDialog && renameTarget != null) {
        AlertDialog(
            onDismissRequest = { if (!renameLoading) { showRenameDialog = false; renameTarget = null } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("重命名", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column {
                    Text("输入新的名称：", fontSize = 14.sp, color = Color(0xFF374151))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入新名称", fontSize = 13.sp) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (renameValue.isNotBlank()) doRename() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color(0xFFD1D5DB),
                            focusedBorderColor = Color(0xFFD97706)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { if (renameValue.isNotBlank()) doRename() },
                    enabled = renameValue.isNotBlank() && !renameLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                ) {
                    if (renameLoading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("确认", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRenameDialog = false; renameTarget = null },
                    enabled = !renameLoading
                ) { Text("取消") }
            }
        )
    }

    // ═════════════════════════════════════════════════════
    // 删除确认弹窗
    // ═════════════════════════════════════════════════════
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(28.dp)) },
            title = { Text("确认删除", fontWeight = FontWeight.Bold) },
            text = { Text("确定要删除此${deleteTargetFileType}吗？\n删除后无法恢复。", fontSize = 14.sp, color = Color(0xFF374151)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        deleteFileById(deleteTargetFileId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) { Text("删除", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("取消") }
            }
        )
    }

    // ═════════════════════════════════════════════════════
    // 文件编辑器（全屏）
    // ═════════════════════════════════════════════════════
    if (editingFile != null) {
        Dialog(
            onDismissRequest = { editingFile = null; editingContent = ""; editingImageBytes = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, decorFitsSystemWindows = false)
        ) {
            SetDialogSystemBarColors(android.graphics.Color.WHITE, android.graphics.Color.WHITE, lightStatusBars = true)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            ) {
                Column(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    // 文本编辑器状态：提升至 Column 作用域，供顶栏、编辑区、状态栏共用
                    val editText = remember(editingContent) { mutableStateOf(editingContent) }
                    // 编辑器缩放倍率（双指缩放 0.5x~3x）
                    val zoomLevel = remember { mutableFloatStateOf(1f) }
                    // 搜索目标位置（用于点击确定后滚动到该位置）
                    var searchTargetPos by remember { mutableStateOf<Int?>(null) }
                    // 编辑器光标/选区位置（用于搜索滚动后恢复）
                    var editorSelection by remember { mutableStateOf(TextRange.Zero) }
                    // 替换弹窗的当前匹配索引（提升到 Column 作用域，供高亮转换使用）
                    var replaceIdx by remember(replaceFind, editText.value) { mutableIntStateOf(0) }
                    // ── 顶栏 ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF3F4F6))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 关闭按钮
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    editingFile = null; editingContent = ""; editingImageBytes = null
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color(0xFF6B7280), modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        // 文件名图标
                        Icon(
                            getFileIcon(editingFile?.fileName ?: ""),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFF1E40AF)
                        )
                        Spacer(Modifier.width(8.dp))
                        // 文件名
                        Text(
                            text = editingFile?.fileName ?: "",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1F2937)
                        )
                        Spacer(Modifier.weight(1f))
                        // 保存按钮（用 editingContent 作为 key，文件加载完成后自动同步内容）
                        TextButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        val result = ChatRepository.updateServerFile(editingFile!!.id, editText.value)
                                        if (result.success) {
                                            android.widget.Toast.makeText(context, "保存成功", android.widget.Toast.LENGTH_SHORT).show()
                                            editingFile = null; editingContent = ""
                                            refreshFileList()
                                        } else {
                                            android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = editText.value != editingContent,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1E40AF))
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("保存", fontSize = 13.sp)
                        }
                    }

                    // ── 编辑器内容（可双指缩放） ──
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    zoomLevel.value = (zoomLevel.value * zoom).coerceIn(0.5f, 3f)
                                }
                            }
                    ) {
                        when {
                            editingLoading -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = Color(0xFF1E40AF), modifier = Modifier.size(36.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text("正在加载文件...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                                    }
                                }
                            }
                            editingIsImage && editingImageBytes != null -> {
                                val bitmap = remember(editingImageBytes) {
                                    try {
                                        android.graphics.BitmapFactory.decodeByteArray(editingImageBytes, 0, editingImageBytes!!.size)
                                    } catch (_: Exception) { null }
                                }
                                if (bitmap != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFFF9FAFB))
                                            .graphicsLayer {
                                                scaleX = zoomLevel.value
                                                scaleY = zoomLevel.value
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        androidx.compose.foundation.Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                        )
                                    }
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("无法加载图片", fontSize = 16.sp, color = Color(0xFF9CA3AF))
                                    }
                                }
                            }
                            else -> {
                                // 全屏文本编辑器（白色背景）
                                val baseFontSize = (14 * zoomLevel.value).coerceIn(7f, 42f)
                                val baseLineHeight = (22 * zoomLevel.value).coerceIn(11f, 66f)
                                val lineCount by remember { derivedStateOf { editText.value.count { it == '\n' } + 1 } }
                                Row(modifier = Modifier.fillMaxSize()) {
                                    // 行号列
                                    Box(
                                        modifier = Modifier
                                            .width((44 * zoomLevel.value).dp.coerceIn(28.dp, 80.dp))
                                            .fillMaxHeight()
                                            .background(Color(0xFFF9FAFB))
                                            .padding(top = 12.dp, end = 8.dp),
                                        contentAlignment = Alignment.TopEnd
                                    ) {
                                        Text(
                                            text = (1..lineCount).joinToString("\n"),
                                            fontSize = baseFontSize.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = Color(0xFF9CA3AF),
                                            lineHeight = baseLineHeight.sp
                                        )
                                    }
                                    // 编辑器
                                    // 搜索/替换高亮转换
                                    val highlightTransformation = remember(searchQuery, replaceFind, replaceIdx, showReplaceDialog) {
                                        VisualTransformation { text ->
                                            val str = text.text
                                            val annotated = buildAnnotatedString {
                                                append(str)
                                                // 搜索高亮（确定后持续显示）
                                                if (searchQuery.isNotEmpty()) {
                                                    var pos = 0
                                                    while (true) {
                                                        val idx = str.indexOf(searchQuery, pos)
                                                        if (idx < 0) break
                                                        addStyle(SpanStyle(background = Color(0x3380BFFF)), idx, idx + searchQuery.length)
                                                        pos = idx + searchQuery.length
                                                    }
                                                }
                                                // 替换高亮（对话框打开时显示）
                                                if (replaceFind.isNotEmpty() && showReplaceDialog) {
                                                    var pos = 0
                                                    var matchIdx = 0
                                                    while (true) {
                                                        val idx = str.indexOf(replaceFind, pos)
                                                        if (idx < 0) break
                                                        val isCurrent = matchIdx == replaceIdx
                                                        addStyle(
                                                            SpanStyle(background = if (isCurrent) Color(0x66FFB800) else Color(0x3380BFFF)),
                                                            idx, idx + replaceFind.length
                                                        )
                                                        matchIdx++
                                                        pos = idx + replaceFind.length
                                                    }
                                                }
                                            }
                                            TransformedText(annotated, OffsetMapping.Identity)
                                        }
                                    }
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = TextFieldValue(
                                            text = editText.value,
                                            selection = if (searchTargetPos != null) TextRange(searchTargetPos!!) else editorSelection
                                        ),
                                        onValueChange = {
                                            editText.value = it.text
                                            editorSelection = it.selection
                                            searchTargetPos = null
                                        },
                                        readOnly = !editKeyboardVisible,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(start = 8.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                                            .pointerInput(Unit) {
                                                detectTransformGestures { _, _, zoom, _ ->
                                                    zoomLevel.value = (zoomLevel.value * zoom).coerceIn(0.5f, 3f)
                                                }
                                            },
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontSize = baseFontSize.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = Color(0xFF1F2937),
                                            lineHeight = baseLineHeight.sp
                                        ),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E40AF)),
                                        visualTransformation = highlightTransformation,
                                        decorationBox = { innerTextField ->
                                            if (editText.value.isEmpty()) {
                                                Text(
                                                    text = "在此输入文件内容...",
                                                    fontSize = baseFontSize.sp,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    color = Color(0xFFD1D5DB),
                                                    modifier = Modifier.padding(start = 2.dp)
                                                )
                                            }
                                            innerTextField()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // ── 底部功能导航栏（文本编辑器） ──
                    if (!editingLoading && !editingIsImage) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White)
                                .shadow(4.dp, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                .navigationBarsPadding()
                                .padding(top = 4.dp, bottom = 32.dp)
                        ) {
                            // ── 状态行 ──
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF8FAFC))
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "行: ${editText.value.count { it == '\n' } + 1}",
                                        fontSize = 11.sp, color = Color(0xFF6B7280)
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Text(
                                        text = "字: ${editText.value.length}",
                                        fontSize = 11.sp, color = Color(0xFF9CA3AF)
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Text(
                                        text = "UTF-8",
                                        fontSize = 11.sp, color = Color(0xFF9CA3AF)
                                    )
                                }
                            }
                            // ── 工具按钮行 ──
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White)
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 搜索
                                EditorToolButton(
                                    icon = Icons.Filled.Search,
                                    label = "搜索",
                                    onClick = { showSearchDialog = true; searchTargetPos = null }
                                )
                                // 替换
                                EditorToolButton(
                                    icon = Icons.Filled.FindReplace,
                                    label = "替换",
                                    onClick = { showReplaceDialog = true }
                                )
                                // 精简（去空行、去空格）
                                EditorToolButton(
                                    icon = Icons.Filled.Compress,
                                    label = "精简",
                                    onClick = {
                                        val text = editText.value
                                        // 去除所有空行、去除每行首尾空格、多余空格压缩
                                        val minified = text
                                            .split("\n")
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                            .joinToString("\n")
                                        editText.value = minified
                                        android.widget.Toast.makeText(context, "已精简", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                )
                                // 转换
                                EditorToolButton(
                                    icon = Icons.Filled.SwapHoriz,
                                    label = "转换",
                                    onClick = { showConvertDialog = true }
                                )
                                // 镜子键盘（防误触）
                                EditorToolButton(
                                    icon = if (editKeyboardVisible) Icons.Filled.Keyboard else Icons.Filled.KeyboardHide,
                                    label = if (editKeyboardVisible) "键盘" else "收起",
                                    onClick = {
                                        editKeyboardVisible = !editKeyboardVisible
                                        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                                        try {
                                            val token = (context as? android.app.Activity)?.currentFocus?.windowToken
                                            if (token != null) imm.hideSoftInputFromWindow(token, 0)
                                        } catch (_: Exception) {}
                                        android.widget.Toast.makeText(
                                            context,
                                            if (!editKeyboardVisible) "键盘已锁定" else "键盘已解锁",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        }
                    }

                    // ── 搜索弹窗（带匹配导航） ──
                    if (showSearchDialog) {
                        val searchMatchCount = remember(searchQuery, editText.value) {
                            if (searchQuery.isEmpty()) 0 else
                                editText.value.let { txt ->
                                    var c = 0; var p = 0
                                    while (true) { val idx = txt.indexOf(searchQuery, p); if (idx < 0) break; c++; p = idx + searchQuery.length }
                                    c
                                }
                        }
                        var searchIdx by remember(searchQuery, editText.value) { mutableIntStateOf(0) }
                        if (searchIdx >= searchMatchCount) searchIdx = (searchMatchCount - 1).coerceAtLeast(0)
                        AlertDialog(
                            onDismissRequest = { showSearchDialog = false; searchQuery = ""; searchIdx = 0; searchTargetPos = null },
                            title = { Text("搜索", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it; searchIdx = 0 },
                                        placeholder = { Text("输入搜索关键词") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    if (searchMatchCount > 0) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("找到 $searchMatchCount 处（当前第 ${searchIdx + 1} 处）", fontSize = 13.sp, color = Color(0xFF6B7280))
                                            Row {
                                                TextButton(onClick = {
                                                    searchIdx = if (searchIdx > 0) searchIdx - 1 else searchMatchCount - 1
                                                    // 实时滚动到新匹配位置
                                                    var p = 0; var m = 0
                                                    while (true) { val idx = editText.value.indexOf(searchQuery, p); if (idx < 0) break; if (m == searchIdx) { searchTargetPos = idx; break }; m++; p = idx + searchQuery.length }
                                                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("上一个", fontSize = 13.sp) }
                                                TextButton(onClick = {
                                                    searchIdx = if (searchIdx < searchMatchCount - 1) searchIdx + 1 else 0
                                                    // 实时滚动到新匹配位置
                                                    var p = 0; var m = 0
                                                    while (true) { val idx = editText.value.indexOf(searchQuery, p); if (idx < 0) break; if (m == searchIdx) { searchTargetPos = idx; break }; m++; p = idx + searchQuery.length }
                                                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("下一个", fontSize = 13.sp) }
                                            }
                                        }
                                    } else if (searchQuery.isNotEmpty()) { Text("未找到匹配", fontSize = 13.sp, color = Color(0xFFDC2626)) }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showSearchDialog = false
                                    if (searchQuery.isNotEmpty()) {
                                        // 计算 searchIdx 处匹配的位置并滚动到该位置
                                        var p = 0; var m = 0
                                        while (true) { val idx = editText.value.indexOf(searchQuery, p); if (idx < 0) break; if (m == searchIdx) { searchTargetPos = idx; break }; m++; p = idx + searchQuery.length }
                                        android.widget.Toast.makeText(context, "已搜索: $searchQuery，共 $searchMatchCount 处", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("确定") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showSearchDialog = false; searchQuery = ""; searchIdx = 0; searchTargetPos = null }) { Text("取消") }
                            }
                        )
                    }

                    // ── 替换弹窗（带匹配导航） ──
                    if (showReplaceDialog) {
                        val matchCount = remember(replaceFind, editText.value) {
                            if (replaceFind.isEmpty()) 0 else
                                editText.value.let { txt ->
                                    var c = 0; var p = 0
                                    while (true) { val idx = txt.indexOf(replaceFind, p); if (idx < 0) break; c++; p = idx + replaceFind.length }
                                    c
                                }
                        }
                        if (replaceIdx >= matchCount) replaceIdx = (matchCount - 1).coerceAtLeast(0)
                        var showReplaceAllConfirm by remember { mutableStateOf(false) }
                        AlertDialog(
                            onDismissRequest = { showReplaceDialog = false; replaceFind = ""; replaceWith = ""; searchTargetPos = null },
                            title = { Text("替换", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    OutlinedTextField(value = replaceFind, onValueChange = { replaceFind = it; replaceIdx = 0 }, placeholder = { Text("查找内容") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(value = replaceWith, onValueChange = { replaceWith = it }, placeholder = { Text("替换为") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(8.dp))
                                    if (matchCount > 0) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("找到 $matchCount 处（当前第 ${replaceIdx + 1} 处）", fontSize = 13.sp, color = Color(0xFF6B7280))
                                            Row {
                                                TextButton(onClick = { replaceIdx = if (replaceIdx > 0) replaceIdx - 1 else matchCount - 1
                                                    // 实时滚动到新匹配位置
                                                    var p = 0; var m = 0
                                                    while (true) { val idx = editText.value.indexOf(replaceFind, p); if (idx < 0) break; if (m == replaceIdx) { searchTargetPos = idx; break }; m++; p = idx + replaceFind.length }
                                                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("上一个", fontSize = 13.sp) }
                                                TextButton(onClick = { replaceIdx = if (replaceIdx < matchCount - 1) replaceIdx + 1 else 0
                                                    // 实时滚动到新匹配位置
                                                    var p = 0; var m = 0
                                                    while (true) { val idx = editText.value.indexOf(replaceFind, p); if (idx < 0) break; if (m == replaceIdx) { searchTargetPos = idx; break }; m++; p = idx + replaceFind.length }
                                                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("下一个", fontSize = 13.sp) }
                                            }
                                        }
                                    } else if (replaceFind.isNotEmpty()) { Text("未找到匹配", fontSize = 13.sp, color = Color(0xFFDC2626)) }
                                }
                            },
                            confirmButton = {
                                Row {
                                    if (matchCount > 0) {
                                        TextButton(onClick = {
                                            val f = replaceFind; val r = replaceWith; var pos = 0; var cur = 0
                                            while (true) { val idx = editText.value.indexOf(f, pos); if (idx < 0) break; if (cur == replaceIdx) { editText.value = editText.value.substring(0, idx) + r + editText.value.substring(idx + f.length); android.widget.Toast.makeText(context, "已替换第 ${replaceIdx + 1} 处", android.widget.Toast.LENGTH_SHORT).show(); break }; cur++; pos = idx + f.length }
                                        }) { Text("替换当前", fontSize = 13.sp, color = Color(0xFF2563EB)) }
                                    }
                                    TextButton(onClick = { if (matchCount > 0) showReplaceAllConfirm = true else android.widget.Toast.makeText(context, "未找到匹配内容", android.widget.Toast.LENGTH_SHORT).show() }) { Text("全部替换", fontSize = 13.sp, color = Color(0xFFDC2626)) }
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showReplaceDialog = false; replaceFind = ""; replaceWith = ""; searchTargetPos = null }) { Text("取消") }
                            }
                        )
                        if (showReplaceAllConfirm) {
                            AlertDialog(
                                onDismissRequest = { showReplaceAllConfirm = false },
                                title = { Text("确认全部替换", fontWeight = FontWeight.Bold, color = Color(0xFFDC2626)) },
                                text = { Text("确定要将全部 $matchCount 处「$replaceFind」替换为「$replaceWith」吗？\n\n此操作不可撤销。", fontSize = 14.sp, lineHeight = 22.sp) },
                                confirmButton = { TextButton(onClick = { editText.value = editText.value.replace(replaceFind, replaceWith); android.widget.Toast.makeText(context, "已全部替换 $matchCount 处", android.widget.Toast.LENGTH_SHORT).show(); showReplaceAllConfirm = false; showReplaceDialog = false; replaceFind = ""; replaceWith = "" }) { Text("确认替换", color = Color(0xFFDC2626)) } },
                                dismissButton = { TextButton(onClick = { showReplaceAllConfirm = false }) { Text("取消") } }
                            )
                        }
                    }

                    // ── 转换弹窗 ──
                    if (showConvertDialog) {
                        AlertDialog(
                            onDismissRequest = { showConvertDialog = false; convertFrom = ""; convertTo = "" },
                            title = { Text("格式转换", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = convertFrom,
                                        onValueChange = { convertFrom = it },
                                        placeholder = { Text("被转换的格式（例: 大写）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = convertTo,
                                        onValueChange = { convertTo = it },
                                        placeholder = { Text("转换后的格式（例: 小写）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text("常用转换: 大写→小写 小写→大写 驼峰→下划线", fontSize = 12.sp, color = Color(0xFF6B7280))
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val text = editText.value
                                    val from = convertFrom.lowercase().trim()
                                    val to = convertTo.lowercase().trim()
                                    val result = when {
                                        (from == "大写" || from == "uppercase") && (to == "小写" || to == "lowercase") -> text.lowercase()
                                        (from == "小写" || from == "lowercase") && (to == "大写" || to == "uppercase") -> text.uppercase()
                                        (to == "反" || to == "reverse" || to == "反转") -> text.reversed()
                                        else -> text // 无法识别则原样返回
                                    }
                                    editText.value = result
                                    android.widget.Toast.makeText(context, "转换完成", android.widget.Toast.LENGTH_SHORT).show()
                                    showConvertDialog = false; convertFrom = ""; convertTo = ""
                                }) { Text("执行转换") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showConvertDialog = false; convertFrom = ""; convertTo = "" }) { Text("取消") }
                            }
                        )
                    }
                }
            }
        }
    }






    // ═════════════════════════════════════════════════════
    // WebView 内置浏览器（应用内打开）
    // ═════════════════════════════════════════════════════
    if (showWebViewDialog && webViewUrl.isNotEmpty()) {
        Dialog(
            onDismissRequest = { showWebViewDialog = false; webViewUrl = "" },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, decorFitsSystemWindows = false)
        ) {
            SetDialogSystemBarColors(0xFF1F2937.toInt(), 0xFF1F2937.toInt())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1F2937))
            ) {
                Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    // 顶栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1F2937))
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    showWebViewDialog = false; webViewUrl = ""
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = webViewUrl,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        // 用浏览器打开
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(webViewUrl))
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        android.widget.Toast.makeText(context, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.OpenInBrowser, contentDescription = "浏览器打开", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", webViewUrl))
                                    android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "复制", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                    // WebView 内容（带进度指示、错误页、下载支持）
                    Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                        var webViewState by remember { mutableStateOf("loading") }
                        var errorMsg by remember { mutableStateOf("") }
                        // 顶部加载进度条
                        if (webViewState == "loading") {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF60A5FA),
                                trackColor = Color(0xFFE5E7EB)
                            )
                        }
                        // 错误页面
                        if (webViewState == "error") {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFFEF4444))
                                    Spacer(Modifier.height(12.dp))
                                    Text("页面加载失败", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = errorMsg.ifEmpty { "请检查网络或使用浏览器打开" },
                                        fontSize = 14.sp, color = Color(0xFF6B7280),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 32.dp)
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    OutlinedButton(onClick = {
                                        try {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(webViewUrl))
                                            context.startActivity(intent)
                                        } catch (_: Exception) { }
                                    }) {
                                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("用浏览器打开")
                                    }
                                }
                            }
                        }
                        // WebView
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    settings.allowFileAccess = true
                                    settings.setSupportZoom(true)
                                    settings.builtInZoomControls = true
                                    webViewClient = object : android.webkit.WebViewClient() {
                                        override fun onReceivedError(view: android.webkit.WebView, errorCode: Int, description: String, failingUrl: String) {
                                            super.onReceivedError(view, errorCode, description, failingUrl)
                                            webViewState = "error"
                                            errorMsg = "($errorCode) $description"
                                        }
                                        override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            webViewState = "loading"
                                            errorMsg = ""
                                        }
                                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            webViewState = "loaded"
                                        }
                                    }
                                    setDownloadListener(object : android.webkit.DownloadListener {
                                        override fun onDownloadStart(url: String, userAgent: String, contentDisposition: String, mimeType: String, contentLength: Long) {
                                            try {
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                                ctx.startActivity(intent)
                                            } catch (_: Exception) {
                                                android.widget.Toast.makeText(context, "无法下载，请使用浏览器打开", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    })
                                    loadUrl(webViewUrl)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════


    // ═════════════════════════════════════════════════════
    }  // 关闭外层 Box
}  // 关闭 ServerScreen()

// ═════════════════════════════════════════════════════
// 统一渐变蓝色按钮
// ═════════════════════════════════════════════════════
@Composable
private fun GradientBlueButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(colors = BlueGradient),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

// 获取文件服务器实际访问地址（含服务器ID，否则 /serve 返回400）
private fun serverFileUrl(serverUrl: String, serverId: Long = 0): String {
    return if (serverUrl.isNotEmpty() && serverId > 0) "$serverUrl/serve/$serverId" else ""
}

// 编辑器底部工具按钮
@Composable
private fun EditorToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = Color(0xFF374151)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color(0xFF6B7280)
        )
    }
}



// ═════════════════════════════════════════════════════
// 文件行组件
// ═════════════════════════════════════════════════════
@Composable
private fun FileItemRow(
    file: ServerFileInfo,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onDeleteRequest: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateStr = remember(file.createdAt) {
        val sdf = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(file.createdAt * 1000))
    }
    val sizeStr = remember(file.fileSize) {
        when {
            file.fileSize >= 1024 * 1024 -> String.format("%.1f MB", file.fileSize / (1024.0 * 1024.0))
            file.fileSize >= 1024 -> String.format("%.1f KB", file.fileSize / 1024.0)
            else -> "${file.fileSize} B"
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showMenu = true }
                )
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(getFileIconColor(file.fileName).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(getFileIcon(file.fileName), contentDescription = null, modifier = Modifier.size(24.dp), tint = getFileIconColor(file.fileName))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = file.fileName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(text = "$sizeStr · $dateStr", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                }
            }
            // 长按菜单
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFD97706)); Spacer(Modifier.width(8.dp)); Text("重命名") } },
                    onClick = { showMenu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFDC2626)); Spacer(Modifier.width(8.dp)); Text("删除") } },
                    onClick = { showMenu = false; onDeleteRequest() }
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════
// 文件夹行组件
// ═════════════════════════════════════════════════════
@Composable
private fun FolderItemRow(
    folder: ServerFileInfo,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onDeleteRequest: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateStr = remember(folder.createdAt) {
        val sdf = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(folder.createdAt * 1000))
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showMenu = true }
                )
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F3FF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF7C3AED).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color(0xFF7C3AED))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = folder.fileName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(text = "文件夹 · $dateStr", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                }
            }
            // 长按菜单
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFD97706)); Spacer(Modifier.width(8.dp)); Text("重命名") } },
                    onClick = { showMenu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFDC2626)); Spacer(Modifier.width(8.dp)); Text("删除") } },
                    onClick = { showMenu = false; onDeleteRequest() }
                )
            }
        }
    }
}

private fun getFileIcon(fileName: String): ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "txt", "md", "log", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "csv" -> Icons.Filled.Description
        "html", "htm", "css", "js", "ts" -> Icons.Filled.Code
        "kt", "kts", "java", "go", "py", "rs", "cpp", "cc", "c", "h", "hpp", "cxx", "swift", "rb", "php" -> Icons.Filled.Code
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico" -> Icons.Filled.Image
        "mp4", "avi", "mov", "mkv", "flv", "wmv" -> Icons.Filled.VideoFile
        "mp3", "wav", "ogg", "flac", "aac", "m4a" -> Icons.Filled.AudioFile
        "pdf" -> Icons.Filled.PictureAsPdf
        "doc", "docx" -> Icons.Filled.Description
        "xls", "xlsx" -> Icons.Filled.TableChart
        "ppt", "pptx" -> Icons.Filled.Slideshow
        "zip", "rar", "7z", "tar", "gz" -> Icons.Filled.FolderZip
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
        "cpp", "cc", "c", "h", "hpp", "cxx" -> Color(0xFF7C3AED)
        "swift" -> Color(0xFFEC4899)
        "rb" -> Color(0xFFDC2626)
        "php" -> Color(0xFF8B5CF6)
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp" -> Color(0xFF059669)
        "mp4", "avi", "mov", "mkv" -> Color(0xFF0891B2)
        "mp3", "wav", "ogg", "flac", "aac" -> Color(0xFFD97706)
        "pdf" -> Color(0xFFDC2626)
        "doc", "docx" -> Color(0xFF2563EB)
        "xls", "xlsx" -> Color(0xFF059669)
        "ppt", "pptx" -> Color(0xFFEA580C)
        "zip", "rar", "7z", "tar", "gz" -> Color(0xFF6B7280)
        "apk" -> Color(0xFF16A34A)
        else -> Color(0xFF6B7280)
    }
}

private fun getFileNameFromUri(ctx: android.content.Context, uri: android.net.Uri): String {
    var name = "unknown"
    ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex) ?: "unknown"
        }
    }
    return name
}

// ===================== 服务器工具功能卡片 =====================

private data class ServerFeature(
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val color: Color,
    val enabled: Boolean
)

@Composable
private fun ServerFeatureCard(
    feature: ServerFeature,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(136.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(feature.color.copy(alpha = if (feature.enabled) 0.12f else 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = null,
                    tint = feature.color,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = feature.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = feature.desc,
                    fontSize = 12.sp,
                    color = Color(0xFF1F2937),
                    lineHeight = 16.sp
                )
            }
        }
    }
}


/** 单条用户数据库 KV 项（对应后端 /api/user_data 返回的 items） */
data class DbItem(val key: String, val value: String, val time: String)

/** 单条服务器日志条目 */
data class LogItem(val id: String, val type: String, val time: String, val message: String)


