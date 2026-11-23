package com.aurora.chat.ui.server

import android.content.Context
import com.aurora.chat.ModelLogger
import com.aurora.chat.data.api.BreadcrumbItem
import com.aurora.chat.ui.components.CodeEditor
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.json.JSONObject
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
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogWindowProvider
import com.aurora.chat.ui.theme.LocalAppColors
import androidx.compose.material3.AlertDialog

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
    // 方式2 已移除：android.app.Dialog 不是 Context 子类，无法沿 Context 链取到其 window
    //（原先的 `ctx is Dialog` 恒为 false，属无效分支）
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
    // ── 开关机确认弹窗：null=关闭；"stop"=关机确认；"start"=开机确认（网站托管没有「重启」）──
    var powerConfirmRequest by remember { mutableStateOf<String?>(null) }
    var powerBusy by remember { mutableStateOf(false) }
    // ── 文件编辑器（统一使用 FileEditorHost 模块）──
    var editingFile by remember { mutableStateOf<ServerFileInfo?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var editingLoading by remember { mutableStateOf(false) }
    // 加载代次：超时/切换文件后作废旧请求，避免迟到的结果又把界面改回去
    var fileLoadGen by remember { mutableStateOf(0) }
    // ── 图片查看（远程图片：点击直接查看，不进文本编辑器）──
    var viewingImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var viewingImageName by remember { mutableStateOf("") }
    var viewingImageLoading by remember { mutableStateOf(false) }
    // WebView 浏览
    var showWebViewDialog by remember { mutableStateOf(false) }
    var webViewUrl by remember { mutableStateOf("") }
    // 打开网站：应用内 / 外部浏览器 选择
    var showOpenChoiceDialog by remember { mutableStateOf(false) }
    var pendingOpenUrl by remember { mutableStateOf("") }
    // ── 删除确认弹窗 ──
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var deleteTargetFileId by remember { mutableStateOf(0L) }
    var deleteTargetFileType by remember { mutableStateOf("文件") }
    // ── ZIP 操作弹窗（点 zip 不再进文本编辑器）──
    var zipActionFile by remember { mutableStateOf<ServerFileInfo?>(null) }


    // ── 获取文件列表（支持离线缓存） ──
    fun refreshFileList() {
        scope.launch {
            fileListLoading = true
            try {
                val result = ChatRepository.getServerFiles(currentFolderId)
                if (result.success) {
                    // 始终以服务器返回为准（不缓存本地，避免旧数据干扰）
                    fileList = result.data ?: emptyList()
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


    // ── 打开文本编辑器（完整复用 AI 工作区 CodeEditor）──
    // 规则：>2MB 或疑似二进制直接拒绝；加载超过 5 秒强制取消，避免界面卡死。
    fun openFileEditor(file: ServerFileInfo) {
        ModelLogger.info(context, "OPEN", "进入打开流程 id=${file.id} name=${file.fileName} isDir=${file.isDir} size=${file.fileSize}")
        if (file.fileSize > TEXT_EDIT_MAX_BYTES) {
            android.widget.Toast.makeText(context, "文件超过 2MB，已取消以文本打开", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val gen = fileLoadGen + 1
        fileLoadGen = gen
        editingFile = file
        editingContent = ""
        editingLoading = true
        // 5 秒看门狗：到点还没加载完就判定卡住，强制取消并恢复界面
        scope.launch {
            delay(5000)
            if (fileLoadGen == gen && editingLoading) {
                fileLoadGen = gen + 1 // 作废迟到的下载结果
                editingFile = null
                editingContent = ""
                editingLoading = false
                android.widget.Toast.makeText(context, "加载超时（超过 5 秒），已取消", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        scope.launch {
            try {
                val result = ChatRepository.downloadFileContent(file.id)
                if (fileLoadGen != gen) return@launch // 已超时或被新文件取代
                ModelLogger.info(context, "DL", "下载结果 success=${result.success} len=${result.data?.size} msg=${result.message}")
                if (result.success && result.data != null) {
                    val text = String(result.data, Charsets.UTF_8)
                    if (text.indexOf('\u0000') >= 0) {
                        // 二进制内容绝不能再塞进文本编辑器（视频/压缩包/图片等）
                        fileLoadGen = gen + 1
                        editingFile = null
                        editingContent = ""
                        android.widget.Toast.makeText(context, "该文件疑似二进制，已取消以文本打开", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        editingContent = text
                    }
                } else {
                    android.widget.Toast.makeText(context, "下载失败: ${result.message}", android.widget.Toast.LENGTH_SHORT).show()
                    editingFile = null
                }
            } catch (e: Exception) {
                if (fileLoadGen == gen) {
                    ModelLogger.error(context, "OPEN", "打开异常: ${e.message}", e)
                    android.widget.Toast.makeText(context, "打开文件失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    editingFile = null
                }
            } finally {
                if (fileLoadGen == gen) editingLoading = false
            }
        }
    }

    // ── 打开图片（直接全屏查看）：同样 5 秒上限，超时强制取消 ──
    fun openImageViewer(file: ServerFileInfo) {
        val gen = fileLoadGen + 1
        fileLoadGen = gen
        viewingImageName = file.fileName
        viewingImageBytes = null
        viewingImageLoading = true
        scope.launch {
            delay(5000)
            if (fileLoadGen == gen && viewingImageLoading) {
                fileLoadGen = gen + 1
                viewingImageLoading = false
                android.widget.Toast.makeText(context, "图片加载超时（超过 5 秒），已取消", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        scope.launch {
            try {
                val result = ChatRepository.downloadFileContent(file.id)
                if (fileLoadGen != gen) return@launch
                if (result.success && result.data != null) {
                    viewingImageBytes = result.data
                } else {
                    android.widget.Toast.makeText(context, "下载失败: ${result.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (fileLoadGen == gen) {
                    android.widget.Toast.makeText(context, "打开图片失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (fileLoadGen == gen) viewingImageLoading = false
            }
        }
    }

    // ── 保存编辑器内容（FileEditorHost 回调）──
    fun saveServerEditor(content: String, closeAfter: Boolean) {
        val file = editingFile ?: return
        scope.launch {
            try {
                val result = ChatRepository.updateServerFile(file.id, content)
                if (result.success) {
                    android.widget.Toast.makeText(context, "保存成功", android.widget.Toast.LENGTH_SHORT).show()
                    if (closeAfter) {
                        editingFile = null
                        editingContent = ""
                    }
                } else {
                    android.widget.Toast.makeText(context, "保存失败: ${result.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 返回键层级管理 ──
    // 正在编辑文件 / 查看大图时，返回键交给它们内部处理（编辑器有未保存提醒），
    // 只有两者都不存在时才由这里关闭文件页，避免直接整页退出。
    BackHandler(enabled = showFilePage && editingFile == null && viewingImageBytes == null) { closeFilePage() }
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
                    tint = com.aurora.chat.ui.theme.appDivider(),
                    modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(20.dp))
                Text("暂无内容", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextHint(),
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
                .background(com.aurora.chat.ui.theme.appSurface())
                .statusBarsPadding()
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部返回栏（与「我的发布 / 小游戏」统一：固定 52.dp、statusBarsPadding、标题左对齐，不再额外加高）
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = com.aurora.chat.ui.theme.appTextPrimary()
                        )
                    }
                    Text("网站托管", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                }
                HorizontalDivider(thickness = 0.5.dp, color = com.aurora.chat.ui.theme.appDivider())
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 24.dp, end = 24.dp, top = 20.dp)
                    ) {
                                if (myServer == null) {
                                // 未登录或无服务器：显示引导界面
                                val ctx = LocalContext.current
                                Spacer(Modifier.height(40.dp))
                                Icon(Icons.Filled.Language, contentDescription = null,
                                    modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
                                    tint = Color(0xFFD1D5DB))
                                Spacer(Modifier.height(16.dp))
                                Text("网站托管", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                    color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.align(Alignment.CenterHorizontally))
                                Spacer(Modifier.height(8.dp))
                                Text("将你的 HTML 静态网站部署到云端，生成可公开访问的链接",
                                    fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
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
                                    fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(),
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
                                    else -> com.aurora.chat.ui.theme.appTextSecondary()
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

                                // 状态轮询：过渡状态时每2秒查询一次（最多轮询 60 秒，避免异常时永远卡在「关机中」）
                                if (isTransitioning) {
                                    LaunchedEffect(s.status) {
                                        var attempts = 0
                                        while (attempts < 30) {
                                            delay(2000)
                                            attempts++
                                            try {
                                                val statusResult = ChatRepository.getServerStatus()
                                                if (!statusResult.success) continue
                                                val newStatus = statusResult.data ?: continue
                                                if (newStatus != s.status) {
                                                    myServer = s.copy(status = newStatus)
                                                }
                                                if (newStatus == "running" || newStatus == "stopped" || newStatus == "offline") break
                                            } catch (_: Exception) { }
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
                                            Text(s.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
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
                                        // 打开网站按钮（运行中才显示，放在关机左侧）→ 选择应用内/浏览器打开
                                        if (s.status == "running") {
                                            Button(
                                                onClick = {
                                                    val url = serverFileUrl(s.serverUrl, s.id)
                                                    if (url.isNotEmpty()) {
                                                        pendingOpenUrl = url
                                                        showOpenChoiceDialog = true
                                                    } else {
                                                        android.widget.Toast.makeText(context, "服务器地址未就绪，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
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
                                                onClick = { powerConfirmRequest = "stop" },
                                                modifier = Modifier.height(44.dp),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                                            ) {
                                                Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("关机", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        } else if (isStoppedOrOffline) {
                                            // 开机（已关机/离线才显示）；网站托管没有「重启」这一档
                                            Button(
                                                onClick = { powerConfirmRequest = "start" },
                                                modifier = Modifier.height(44.dp),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                                            ) {
                                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("开机", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                                    colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg()),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        // 终端（从「我的」迁入，与文件管理/刷新状态同一卡片）
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(Color(0xFF2563EB).copy(alpha = 0.1f))
                                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                                        context.startActivity(android.content.Intent(context, com.aurora.chat.ui.server.TerminalActivity::class.java))
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Filled.Terminal, contentDescription = "终端", modifier = Modifier.size(28.dp), tint = Color(0xFF2563EB))
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text("终端", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                                        }
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
                                            Text("文件管理", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
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
                                            Text("刷新状态", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
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

    // 打开网站：选择应用内打开 / 外部浏览器打开
    // 注意：用普通 Compose 覆盖层（非窗口 Dialog），直接覆盖在「网站托管」详情界面之上，
    // 避免与文件管理页（窗口 Dialog）的层级混淆，导致弹窗跑到文件管理里。
    if (showOpenChoiceDialog && pendingOpenUrl.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { showOpenChoiceDialog = false },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appSurface()),
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { }
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("打开网站", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Spacer(Modifier.height(8.dp))
                    Text("请选择打开方式", fontSize = 14.sp, color = Color(0xFF4B5563))
                    Spacer(Modifier.height(16.dp))
                    // 应用内打开
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BlueMain)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                showOpenChoiceDialog = false
                                // 走独立 WebViewActivity：与聊天里「在应用内打开」同一条路径。
                                // 之前用页面内 Compose Dialog 承载 WebView，在本机部分机型/系统版本上
                                // 窗口不显示（点了等于没反应），改成 Activity 后不再依赖弹窗窗口层级。
                                val url = pendingOpenUrl
                                try {
                                    val intent = android.content.Intent(context, com.aurora.chat.ui.WebViewActivity::class.java)
                                        .putExtra(com.aurora.chat.ui.WebViewActivity.EXTRA_URL, url)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "打开失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("应用内打开", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    // 浏览器打开
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(com.aurora.chat.ui.theme.appCardBg())
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                showOpenChoiceDialog = false
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(pendingOpenUrl))
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    android.widget.Toast.makeText(context, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("浏览器打开", color = com.aurora.chat.ui.theme.appTextSecondary(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showFilePage) {
        // 内联全屏层：不再用窗口级 Dialog，避免和开关机确认等窗口弹窗抢层级
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(com.aurora.chat.ui.theme.appCardBg())
                .zIndex(20f)
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
                        modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg())
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
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = com.aurora.chat.ui.theme.appTextPrimary())
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (currentFolderId != null) "文件" else "文件管理",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = com.aurora.chat.ui.theme.appTextPrimary()
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
                                        Text(" / ", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                        Text(
                                            text = item.name,
                                            fontSize = 13.sp,
                                            color = if (index == folderBreadcrumb.lastIndex) com.aurora.chat.ui.theme.appTextPrimary() else BlueMain,
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
                                                Text(
                                                    if (searchResults != null) "未找到匹配文件" else "暂无文件",
                                                    fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextHint()
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
                                                        },
                                                    )
                                                } else {
                                                    // 文件行
                                                    FileItemRow(
                                                        file = item,
                                                        onClick = {
                                                            // 按「mimeType 优先、扩展名兜底」分流，绝不把视频/图片等二进制当文本读
                                                            when (serverFileKind(item)) {
                                                                ServerFileKind.ZIP -> zipActionFile = item
                                                                ServerFileKind.IMAGE -> openImageViewer(item)
                                                                ServerFileKind.TEXT -> openFileEditor(item)
                                                                ServerFileKind.VIDEO, ServerFileKind.AUDIO -> {
                                                                    // 视频/音频：用内置浏览器播放公开地址（流式播放，不再整文件读进内存）
                                                                    if (item.downloadUrl.isNotEmpty()) {
                                                                        webViewUrl = item.downloadUrl
                                                                        showWebViewDialog = true
                                                                    } else {
                                                                        android.widget.Toast.makeText(context, "该文件暂无公开访问地址，请通过网站访问或下载", android.widget.Toast.LENGTH_SHORT).show()
                                                                    }
                                                                }
                                                                ServerFileKind.UNSUPPORTED ->
                                                                    android.widget.Toast.makeText(context, "该类型不支持在线预览，请下载后查看", android.widget.Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        onRename = { startRename(item) },
                                                        onDeleteRequest = {
                                                            deleteTargetFileId = item.id
                                                            deleteTargetFileType = "文件"
                                                            showDeleteConfirmDialog = true
                                                        },
                                                        onUnzip = if (item.fileName.substringAfterLast('.', "").lowercase() == "zip") {
                                                            { zipActionFile = item }
                                                        } else null,
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
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .padding(28.dp)
            ) {
                Column {
                    Text(
                        text = "新建文件",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = com.aurora.chat.ui.theme.appTextPrimary(),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "输入文件名（含扩展名，如 index.html）",
                        fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(),
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
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = com.aurora.chat.ui.theme.appTextSecondary())
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
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .padding(28.dp)
            ) {
                Column {
                    Text(
                        text = "新建文件夹",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = com.aurora.chat.ui.theme.appTextPrimary(),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (currentFolderId != null) "将在当前目录创建子文件夹" else "在根目录创建新文件夹",
                        fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(),
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
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = com.aurora.chat.ui.theme.appTextSecondary())
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
    // 开机 / 关机确认（窗口级 Dialog，确保能正常弹出）
    // 网站托管只有「开机 / 关机」两态，没有「重启」
    // ═════════════════════════════════════════════════════
    powerConfirmRequest?.let { action ->
        val isStop = action == "stop"
        Dialog(
            onDismissRequest = { if (!powerBusy) powerConfirmRequest = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = !powerBusy
            )
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appSurface()),
                modifier = Modifier.fillMaxWidth(0.86f)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isStop) Icons.Filled.PowerSettingsNew else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = if (isStop) Color(0xFFDC2626) else Color(0xFF059669),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isStop) "关机确认" else "开机确认",
                            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (isStop) "关机后将停止网站服务，访客访问会提示「服务器已关机」。"
                        else "开机后将恢复网站服务与文件访问。",
                        fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(com.aurora.chat.ui.theme.appCardBg())
                                .clickable(enabled = !powerBusy) { powerConfirmRequest = null },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("取消", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isStop) Color(0xFFDC2626) else Color(0xFF059669))
                                .clickable(enabled = !powerBusy) {
                                    powerBusy = true
                                    scope.launch {
                                        try {
                                            val result = if (isStop) ChatRepository.shutdownServer() else ChatRepository.restartServer()
                                            if (result.success) {
                                                val st = result.data?.takeIf { it.isNotBlank() }
                                                    ?: if (isStop) "stopped" else "running"
                                                myServer = myServer?.copy(status = st)
                                                android.widget.Toast.makeText(context, if (isStop) "已关机" else "已开机", android.widget.Toast.LENGTH_SHORT).show()
                                                powerConfirmRequest = null
                                            } else {
                                                android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, (if (isStop) "关机失败: " else "开机失败: ") + e.message, android.widget.Toast.LENGTH_SHORT).show()
                                        } finally {
                                            powerBusy = false
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (powerBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Text(if (isStop) "确认关机" else "确认开机", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
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
                    Text("输入新的名称：", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
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
            text = { Text("确定要删除此${deleteTargetFileType}吗？\n删除后无法恢复。", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary()) },
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
    // ZIP 操作弹窗（解压 / 查看压缩包内文件）
    // ═════════════════════════════════════════════════════
    if (zipActionFile != null) {
        ZipActionDialog(
            file = zipActionFile!!,
            onDismiss = { zipActionFile = null },
            onUnzipped = { zipActionFile = null; refreshFileList() }
        )
    }


    // ═════════════════════════════════════════════════════
    // 文件编辑器（完整复用 AI 工作区同一套 CodeEditor，不另写编辑器）
    // 说明：文件管理整页是全屏 Dialog，而 CodeEditor 的根是内联 Box，直接内联渲染会被该
    //      Dialog 盖住（点了没反应）。这里只把它放进一层全屏 Dialog 抬高层级，
    //      编辑器内部实现、语法高亮、运行/格式化与联网读写全部保持原样。
    // ═════════════════════════════════════════════════════
    if (editingFile != null) {
        ModelLogger.info(context, "OPEN", "渲染 CodeEditor title=${editingFile?.fileName} contentLen=${editingContent.length} loading=$editingLoading")
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            CodeEditor(
                title = editingFile?.fileName ?: "",
                subtitle = "网站托管文件",
                initialContent = editingContent,
                isLoading = editingLoading,
                onSave = { text, closeAfter -> saveServerEditor(text, closeAfter) },
                onDismiss = { editingFile = null; editingContent = "" }
            )
        }
    }

    // ═════════════════════════════════════════════════════
    // 图片加载中遮罩（超过 5 秒会被看门狗取消并自动关闭）
    // ═════════════════════════════════════════════════════
    if (viewingImageLoading && viewingImageBytes == null) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("图片加载中（超过 5 秒自动取消）", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════
    // 全屏图片查看器（远程图片字节，点击直接查看）
    // ═════════════════════════════════════════════════════
    viewingImageBytes?.let { bytes ->
        // 图片查看同样放进全屏 Dialog 抬高层级，避免被文件页 Dialog 盖住（与编辑器同一处理方式）
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            val bitmap = remember(bytes) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            val imgZoom = remember { mutableFloatStateOf(1f) }
            BackHandler { viewingImageBytes = null }
            Box(
                Modifier.fillMaxSize().background(Color(0xFF111827))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { viewingImageBytes = null }
                    .statusBarsPadding()
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { viewingImageBytes = null },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        viewingImageName, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appDivider(),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Text("${(imgZoom.floatValue * 100).toInt()}%", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
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
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("该图片格式暂不支持预览", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                }
            }
        }
    }

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
                    .background(com.aurora.chat.ui.theme.appTextPrimary())
            ) {
                Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    // 顶栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(com.aurora.chat.ui.theme.appTextPrimary())
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
                                trackColor = com.aurora.chat.ui.theme.appDivider()
                            )
                        }
                        // 错误页面
                        if (webViewState == "error") {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFFEF4444))
                                    Spacer(Modifier.height(12.dp))
                                    Text("页面加载失败", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = errorMsg.ifEmpty { "请检查网络或使用浏览器打开" },
                                        fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
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
                                        override fun onDownloadStart(url: String, userAI: String, contentDisposition: String, mimeType: String, contentLength: Long) {
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

// 后端返回的 server_url 已是「完整公开地址」：
//  - 路径模式：https://域名/serve/{id}
//  - 子域名模式：https://s{id}.域名
// 前端不再自行拼接，避免重复 /serve/{id}。
// 仅当命中旧后端（只返回域名根）时才兜底补齐路径。
private fun serverFileUrl(serverUrl: String, serverId: Long = 0): String {
    val base = serverUrl.trimEnd('/')
    if (base.isEmpty()) return ""
    val host = try { java.net.URI(base).host ?: "" } catch (_: Exception) { "" }
    // 子域名模式（s{id}.域名）已是完整地址
    if (Regex("""^s\d+\..+""").matches(host)) return base
    // 路径模式且后端已带 /serve/：直接使用
    if (base.contains("/serve/")) return base
    // 旧后端只返回域名根：兜底补齐
    if (serverId > 0) return "$base/serve/$serverId"
    return base
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
            tint = com.aurora.chat.ui.theme.appTextSecondary()
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = com.aurora.chat.ui.theme.appTextSecondary()
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
    onDeleteRequest: () -> Unit = {},
    onUnzip: (() -> Unit)? = null,
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
            .background(com.aurora.chat.ui.theme.appSurface())
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showMenu = true }
                )
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                    Text(text = file.fileName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(text = "$sizeStr · $dateStr", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
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
                if (onUnzip != null) {
                    DropdownMenuItem(
                        text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp), tint = BlueMain); Spacer(Modifier.width(8.dp)); Text("解压") } },
                        onClick = { showMenu = false; onUnzip() }
                    )
                }
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
    onDeleteRequest: () -> Unit = {},
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
            .background(com.aurora.chat.ui.theme.appSurface())
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showMenu = true }
                )
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                    Text(text = folder.fileName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(text = "文件夹 · $dateStr", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
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
        "zip", "rar", "7z", "tar", "gz" -> com.aurora.chat.ui.theme.appTextSecondary()
        "apk" -> Color(0xFF16A34A)
        else -> com.aurora.chat.ui.theme.appTextSecondary()
    }
}

// ═══════ ZIP 操作弹窗（解压 / 查看文件内容）═══════
@Composable
private fun ZipActionDialog(
    file: ServerFileInfo,
    onDismiss: () -> Unit,
    onUnzipped: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showContents by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var unzipping by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun loadEntries() {
        if (loading || loaded) return
        loading = true
        loadError = null
        scope.launch {
            try {
                val r = AuroraApi.zipInfoServerFile(file.id)
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
            loaded = true
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.widthIn(max = 440.dp).fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(24.dp)
        ) {
            Column {
                // ── 头部 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(BlueMain.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Archive, contentDescription = null, modifier = Modifier.size(24.dp), tint = BlueMain)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ZIP 压缩包", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        Text(
                            file.fileName,
                            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                // ── 操作按钮（统一主蓝）──
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { if (!showContents) loadEntries(); showContents = !showContents },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BlueMain)
                    ) {
                        Icon(
                            if (showContents) Icons.Filled.ExpandLess else Icons.Filled.Description,
                            contentDescription = null, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (showContents) "收起" else "查看文件内容", fontSize = 14.sp)
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                unzipping = true
                                try {
                                    val r = AuroraApi.unzipServerFile(file.id)
                                    if (r.success) {
                                        toast("已解压到文件夹：${r.data?.optString("folder_name", "") ?: ""}")
                                        onUnzipped()
                                    } else toast(r.message)
                                } catch (e: Exception) {
                                    toast("解压失败: ${e.message}")
                                }
                                unzipping = false
                            }
                        },
                        modifier = Modifier.weight(1f), enabled = !unzipping,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BlueMain)
                    ) {
                        if (unzipping) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Filled.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("解压", fontSize = 14.sp)
                        }
                    }
                }
                // ── 文件内容列表 ──
                if (showContents) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp))
                            .background(com.aurora.chat.ui.theme.appCardBg()).border(1.dp, com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp))
                    ) {
                        when {
                            loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = BlueMain, modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                            }
                            loadError != null -> Text(
                                loadError ?: "", fontSize = 13.sp, color = Color(0xFFDC2626),
                                modifier = Modifier.padding(16.dp)
                            )
                            entries.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text("压缩包为空", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                            else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                                for ((index, e) in entries.withIndex()) {
                                    val ename = e.optString("name", "")
                                    val esize = e.optLong("size", 0)
                                    val eisDir = e.optBoolean("is_dir", false)
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (eisDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                            contentDescription = null, modifier = Modifier.size(18.dp),
                                            tint = if (eisDir) BlueMain else Color(0xFF94A3B8)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            ename, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary(),
                                            modifier = Modifier.weight(1f), maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        if (!eisDir) Text(formatServerFileSize(esize), fontSize = 11.sp, color = Color(0xFF94A3B8))
                                    }
                                    if (index != entries.lastIndex) {
                                        HorizontalDivider(thickness = 0.5.dp, color = com.aurora.chat.ui.theme.appDivider())
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = com.aurora.chat.ui.theme.appTextSecondary())
                    ) { Text("关闭", fontSize = 14.sp) }
                }
            }
        }
    }
}

private fun formatServerFileSize(size: Long): String {
    return when {
        size >= 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        size >= 1024 -> String.format("%.1f KB", size / 1024.0)
        else -> "$size B"
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
        colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appSurface()),
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
                        color = com.aurora.chat.ui.theme.appTextPrimary()
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = feature.desc,
                    fontSize = 12.sp,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
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

// ==================== 网站托管文件类型分类 ====================
// 目的：只有真正的文本/代码才进 CodeEditor；视频/音频/图片/压缩包等二进制走各自通道，
// 绝不再把二进制当 UTF-8 文本读进编辑器（几十 MB 视频会直接卡死/OOM）。

private enum class ServerFileKind { TEXT, IMAGE, VIDEO, AUDIO, ZIP, UNSUPPORTED }

/** 文本编辑器单文件上限：超过直接拒绝，避免把大文件读成字符串后卡死。 */
private const val TEXT_EDIT_MAX_BYTES = 2 * 1024 * 1024L

private val SERVER_VIDEO_EXTS = setOf(
    "mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp", "3g2", "ts", "flv", "wmv", "mpg", "mpeg"
)
private val SERVER_AUDIO_EXTS = setOf(
    "mp3", "wav", "m4a", "aac", "flac", "ogg", "oga", "amr", "wma", "opus"
)
private val SERVER_IMAGE_EXTS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "jfif", "tif", "tiff"
)
private val SERVER_TEXT_EXTS = setOf(
    "txt", "md", "markdown", "json", "xml", "yml", "yaml", "html", "htm", "css", "scss", "less",
    "js", "mjs", "cjs", "ts", "jsx", "tsx", "vue", "svelte",
    "py", "java", "kt", "kts", "c", "h", "cc", "cpp", "cxx", "hpp", "go", "rs", "rb", "php", "sql",
    "sh", "bash", "zsh", "bat", "ps1", "ini", "conf", "cfg", "log", "csv", "tsv",
    "gradle", "properties", "toml", "svg", "swift", "cs", "lua", "pl", "r", "dart", "scala",
    "gitignore", "env", "dockerfile", "makefile", "editorconfig"
)

/** 先看 mimeType，再看扩展名；只有确定是文本/代码才返回 TEXT。 */
private fun serverFileKind(file: ServerFileInfo): ServerFileKind {
    val mime = file.mimeType.lowercase()
    val ext = file.fileName.substringAfterLast('.', "").lowercase()
    if (mime == "application/zip" || mime == "application/x-zip-compressed" || ext == "zip") {
        return ServerFileKind.ZIP
    }
    if (mime.startsWith("video/") || ext in SERVER_VIDEO_EXTS) return ServerFileKind.VIDEO
    if (mime.startsWith("audio/") || ext in SERVER_AUDIO_EXTS) return ServerFileKind.AUDIO
    // SVG 本质是 XML 文本，BitmapFactory 解不了，归文本更稳
    if (ext == "svg" || mime == "image/svg+xml") return ServerFileKind.TEXT
    if (mime.startsWith("image/") || ext in SERVER_IMAGE_EXTS) return ServerFileKind.IMAGE
    if (mime.startsWith("text/") ||
        mime == "application/json" || mime == "application/xml" ||
        mime == "application/javascript" || mime == "application/x-javascript" ||
        ext in SERVER_TEXT_EXTS
    ) {
        return ServerFileKind.TEXT
    }
    // 无扩展名且后端只给了 octet-stream：小文件当文本试读（如 Dockerfile / README），大文件不冒险
    if (ext.isEmpty() && (mime.isEmpty() || mime == "application/octet-stream")) {
        return if (file.fileSize in 1L..(512L * 1024L)) ServerFileKind.TEXT else ServerFileKind.UNSUPPORTED
    }
    return ServerFileKind.UNSUPPORTED
}


