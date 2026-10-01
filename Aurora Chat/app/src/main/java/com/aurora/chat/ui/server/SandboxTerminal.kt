package com.aurora.chat.ui.server

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.local.LocalStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Calendar

private const val CMD_HISTORY_MAX = 50

// ── 命令注册表（在此增删命令，命令菜单自动更新） ──
private data class CmdDef(val trigger: String, val desc: String)
private val cmdDefs = listOf(
    CmdDef("设置", "进入终端设置菜单"),
    CmdDef("命令菜单", "查看所有可用命令"),
    CmdDef("清屏", "清空屏幕内容"),
    CmdDef("退出终端", "退出当前终端"),
    CmdDef("退出登录", "退出当前账号"),
    CmdDef("注销账号", "注销Aurora Chat账号"),
    CmdDef("退出软件", "关闭整个应用"),
    CmdDef("打开手电筒", "打开/关闭手电筒"),
    CmdDef("打开摄像头", "选择前置/后置摄像头"),
    CmdDef("创建群聊", "创建一个新的群聊"),
    CmdDef("添加好友", "发送好友申请"),
    CmdDef("进入群聊", "申请加入一个群聊"),
    CmdDef("下载应用", "下载安装APK应用"),
    CmdDef("手机息屏", "关闭手机屏幕（需root）"),
    CmdDef("手机关机", "关闭手机电源（需root）"),
    CmdDef("批量创建文件", "批量创建文件并自动编号"),
    CmdDef("将所有设置恢复成默认", "恢复终端所有默认设置"),
)

// ── 终端状态 ──
private enum class TermState { NORMAL, COMMAND_MENU, SETTINGS_MENU, SETTINGS_PROMPT, SETTINGS_ROOT, SETTINGS_COLOR, EXIT_CONFIRM, DELETE_CONFIRM, DELETE_PASSWORD, LOGOUT_CONFIRM,
    CREATE_GROUP_AVATAR, CREATE_GROUP_NAME, CREATE_GROUP_SIG, CREATE_GROUP_ANNOUNCE, CREATE_GROUP_WELCOME, CREATE_GROUP_CONFIRM,
    ADD_FRIEND_ID, ADD_FRIEND_MSG, JOIN_GROUP_INPUT, JOIN_GROUP_REASON,
    DOWNLOAD_URL,
    BATCH_CREATE_COUNT, BATCH_CREATE_NAME, BATCH_CREATE_DIR }

@Composable
fun SandboxTerminalDialog(
    projectId: Long = 0,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val noRipple = remember { MutableInteractionSource() }

    var showExitConfirm by remember { mutableStateOf(false) }

    // ── 全屏窗口 ──
    val rootView = LocalView.current
    val dialogWindow = remember {
        var p: Any? = rootView.parent
        while (p != null) {
            if (p is DialogWindowProvider) return@remember p.window
            p = (p as? android.view.View)?.parent
        }
        null
    }
    SideEffect {
        val w = dialogWindow ?: return@SideEffect
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        w.statusBarColor = android.graphics.Color.TRANSPARENT
        w.navigationBarColor = android.graphics.Color.TRANSPARENT
        w.setDimAmount(0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false)
            w.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
            w.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }
    }

    // ── 状态变量 ──
    data class Line(val text: String, val isInput: Boolean, val copyCommand: String? = null)
    var lines by remember { mutableStateOf(listOf<Line>()) }
    var currentInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var commandHistory by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableStateOf(-1) }
    var cursorVisible by remember { mutableStateOf(true) }
    var flashlightOn by remember { mutableStateOf(false) }
    var showCameraPreview by remember { mutableStateOf(false) }
    var cameraLens by remember { mutableIntStateOf(0) }
    var pendingCameraAsk by remember { mutableStateOf(false) }
    var groupAvatarBase64 by remember { mutableStateOf("") }
    var groupAvatarName by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var groupSig by remember { mutableStateOf("") }
    var groupAnnounce by remember { mutableStateOf("") }
    var groupWelcome by remember { mutableStateOf("") }
    var addFriendTarget by remember { mutableStateOf("") }
    var joinGroupId by remember { mutableStateOf(0L) }
    var pendingAvatar by remember { mutableStateOf(false) }
    var batchFileCount by remember { mutableIntStateOf(0) }
    var batchFileName by remember { mutableStateOf("") }
    var batchFileDir by remember { mutableStateOf("") }


    // ── 设置（持久化存储） ──
    val prefs = ctx.getSharedPreferences("terminal_settings", android.content.Context.MODE_PRIVATE)
    var termState by remember { mutableStateOf(TermState.NORMAL) }
    var cmdPrompt by remember { mutableStateOf(prefs.getString("cmdPrompt", "$") ?: "$") }
    var rootEnabled by remember { mutableStateOf(prefs.getBoolean("rootEnabled", false)) }
    var bgColor by remember { mutableIntStateOf(prefs.getInt("bgColor", 0xFF000000.toInt())) }
    var fgColor by remember { mutableIntStateOf(prefs.getInt("fgColor", 0xFFE6EDF3.toInt())) }

    // 设置变更时自动保存
    fun saveSettings() {
        prefs.edit()
            .putString("cmdPrompt", cmdPrompt)
            .putBoolean("rootEnabled", rootEnabled)
            .putInt("bgColor", bgColor)
            .putInt("fgColor", fgColor)
            .apply()
    }

    val promptColor = Color(fgColor)
    val textColor = Color(fgColor)
    val displayPrompt = when (termState) {
        TermState.NORMAL -> "$cmdPrompt "
        else -> "请输入:"
    }

    // 光标闪烁
    LaunchedEffect(Unit) {
        while (true) { delay(530); cursorVisible = !cursorVisible }
    }

    // 内容过长时自动滚到底部
    LaunchedEffect(currentInput) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    // 点击空白处恢复输入法
    fun showKeyboard() {
        try {
            val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0)
            scope.launch {
                try {
                    focusRequester.requestFocus()
                    delay(200)
                    scrollState.animateScrollTo(scrollState.maxValue)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ── 欢迎语 ──
    val userName = remember { AuroraApi.currentUserName.ifEmpty { "用户" } }
    LaunchedEffect(Unit) {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greet = when { h < 6 -> "凌晨好"; h < 12 -> "早上好"; h < 18 -> "下午好"; else -> "晚上好" }
        lines = listOf(
            Line("Aurora Terminal v1.0", false), Line("", false),
            Line("$greet$userName，你当前使用的终端版本是Aurora Terminal v1.0，QQ官方群:1084612899，输入 /设置 进入设置菜单，输入 /命令菜单 查看所有可用命令！", false),
            Line("", false)
        )
    }

    // ── 本地执行 ──
    suspend fun execLocal(cmd: String, useRoot: Boolean = false): String = withContext(Dispatchers.IO) {
        try {
            val shell = if (useRoot) "su" else "sh"
            // 工作目录锚定到工作区(ai_files)+ PATH 兜底:App 进程 PATH 常缺失 /system/bin,
            // 否则 sh 内 ls/cat/cd 等基础命令会报 not found,表现为「终端不让用这些命令」。
            val wd = com.aurora.chat.ui.chat.AiChatManager.agentRoot(ctx)
            val envp = if (useRoot) null else arrayOf("PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:/data/local/bin")
            val proc = Runtime.getRuntime().exec(arrayOf(shell, "-c", cmd), envp, wd)
            val stdout = BufferedReader(InputStreamReader(proc.inputStream)).readText().trim()
            val stderr = BufferedReader(InputStreamReader(proc.errorStream)).readText().trim()
            proc.waitFor()
            val sb = StringBuilder()
            if (stdout.isNotBlank()) sb.appendLine(stdout)
            if (stderr.isNotBlank()) sb.appendLine(stderr)
            if (sb.isBlank()) sb.append("(exit code: ${proc.exitValue()})")
            sb.toString().trimEnd()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ── 输出行（含颜色） ──
    fun addLine(text: String, isInput: Boolean = false, copyCommand: String? = null) {
        lines = lines + Line(text, isInput, copyCommand)
    }
    fun addEmpty() { lines = lines + Line("", false) }
    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && pendingAvatar) {
            pendingAvatar = false
            scope.launch {
                try {
                    val inputStream = ctx.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes(); inputStream?.close()
                    if (bytes != null) {
                        groupAvatarBase64 = java.util.Base64.getEncoder().encodeToString(bytes)
                        groupAvatarName = uri.lastPathSegment ?: "avatar.jpg"
                        addLine("已选择: $groupAvatarName", false)
                    }
                } catch (_: Exception) { addLine("选择图片失败", false) }
                addLine("请输入群名称", false); addEmpty()
                termState = TermState.CREATE_GROUP_NAME
            }
        }
    }

    fun bgToName(c: Color): String = when (c) {
        Color.Black -> "黑色"; Color.White -> "白色"; else -> "#${Integer.toHexString(c.hashCode()).substring(2)}"
    }
    fun fgToName(c: Color): String = when (c) {
        Color.White -> "白色"; Color.Black -> "黑色"; else -> "#${Integer.toHexString(c.hashCode()).substring(2)}"
    }

    // 颜色解析
    fun parseColor(input: String): Int? {
        val clean = input.trim().lowercase().replace("色", "")
        return when (clean) {
            "黑", "black", "#000000" -> 0xFF000000.toInt()
            "白", "white", "#ffffff", "#fff" -> 0xFFFFFFFF.toInt()
            "红", "red", "#ff0000" -> 0xFFFF0000.toInt()
            "绿", "green", "#00ff00" -> 0xFF00FF00.toInt()
            "蓝", "blue", "#0000ff" -> 0xFF0000FF.toInt()
            "黄", "yellow", "#ffff00" -> 0xFFFFFF00.toInt()
            "紫", "purple", "#800080" -> 0xFF800080.toInt()
            "灰", "gray", "#808080" -> 0xFF808080.toInt()
            "橙", "orange", "#ffa500" -> 0xFFFFA500.toInt()
            "青", "cyan", "#00ffff" -> 0xFF00FFFF.toInt()
            else -> {
                val h = clean.removePrefix("#")
                if (h.matches(Regex("^[0-9a-fA-F]{6}$"))) {
                    Integer.parseInt(h, 16) or 0xFF000000.toInt()
                } else null
            }
        }
    }

    // ── 处理设置输入 ──
    fun handleSettingsInput(input: String) {
        when (termState) {
            TermState.SETTINGS_MENU -> {
                when (input.trim()) {
                    "1" -> { addLine("你当前使用的命令提示符是$cmdPrompt,请输入你想要的命令提示符回车保存", false); addEmpty(); termState = TermState.SETTINGS_PROMPT }
                    "2" -> { addLine("是否开启root模式，开启root模式需要你的设备拥有root权限，开启后你输入的命令将获得最高权限，输入 '是' 或 '否'", false); addEmpty(); termState = TermState.SETTINGS_ROOT }
                    "3" -> { val bg = Color(bgColor); val fg = Color(fgColor); addLine("你当前使用的终端颜色是${bgToName(bg)}+${fgToName(fg)},请输入你想要的颜色，格式:X色+X色，背景颜色+字体颜色", false); addEmpty(); termState = TermState.SETTINGS_COLOR }
                    "Q", "q" -> { addLine("已退出设置", false); addEmpty(); termState = TermState.NORMAL }
                    else -> { addLine("无效输入，请输入1-3或Q返回", false); addEmpty() }
                }
            }
            TermState.SETTINGS_PROMPT -> {
                val c = input.trim()
                if (c.isNotBlank()) {
                    cmdPrompt = c
                    addLine("保存成功", false); addEmpty()
                }
                termState = TermState.NORMAL; saveSettings()
            }
            TermState.SETTINGS_ROOT -> {
                when (input.trim()) {
                    "是", "yes" -> {
                        scope.launch {
                            val hasRoot = try {
                                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                                p.waitFor() == 0
                            } catch (_: Exception) { false }
                            if (hasRoot) {
                                rootEnabled = true; addLine("root模式已开启，命令将以root权限执行", false); addEmpty()
                            } else {
                                addLine("您的设备未root无法开启", false); addEmpty()
                            }
                            termState = TermState.NORMAL; saveSettings()
                        }
                        return
                    }
                    "否", "no" -> { addLine("root模式未开启", false); addEmpty(); termState = TermState.NORMAL; saveSettings() }
                    else -> { addLine("请输入 '是' 或 '否'", false); addEmpty(); return }
                }
            }
            TermState.SETTINGS_COLOR -> {
                val parts = input.split("+", "-", " ")
                if (parts.size != 2) {
                    addLine("${input.trim()}不是合规颜色，请重新输入", false); addEmpty(); return
                }
                val bg = parseColor(parts[0])
                val fg = parseColor(parts[1])
                if (bg == null || fg == null) {
                    addLine("${input.trim()}不是合规颜色，请重新输入", false); addEmpty(); return
                }
                bgColor = bg; fgColor = fg
                addLine("保存成功", false); addEmpty()
                termState = TermState.NORMAL; saveSettings()
            }
            TermState.EXIT_CONFIRM -> {
                when (input.trim()) {
                    "是", "yes" -> onDismiss()
                    "否", "no" -> { addLine("已取消退出", false); addEmpty(); termState = TermState.NORMAL }
                    else -> { addLine("请输入 '是' 或 '否'", false); addEmpty(); return }
                }
            }
            TermState.LOGOUT_CONFIRM -> {
                when (input.trim()) {
                    "是", "yes" -> {
                        com.aurora.chat.data.local.LocalStorage.logout(ctx)
                        com.aurora.chat.TcpService.stop(ctx)
                        com.aurora.chat.ui.viewmodel.ChatViewModel.removeUserData(AuroraApi.currentUserId)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                    "否", "no" -> { addLine("已取消退出登录", false); addEmpty(); termState = TermState.NORMAL }
                    else -> { addLine("请输入 '是' 或 '否'", false); addEmpty(); return }
                }
            }
            TermState.DELETE_CONFIRM -> {
                when (input.trim()) {
                    "是", "yes" -> { addLine("请输入当前账号密码", false); addEmpty(); termState = TermState.DELETE_PASSWORD }
                    "否", "no" -> { addLine("已取消注销", false); addEmpty(); termState = TermState.NORMAL }
                    else -> { addLine("请输入 '是' 或 '否'", false); addEmpty(); return }
                }
            }
            TermState.DELETE_PASSWORD -> {
                val pw = input.trim()
                if (pw.isBlank()) { addLine("密码不能为空", false); addEmpty(); return }
                addLine("正在验证并注销账号...", false)
                scope.launch {
                    try {
                        val r = AuroraApi.selfDeleteAccount(pw)
                        if (r.success) {
                            addLine("账号已注销", false)
                            addEmpty()
                            kotlinx.coroutines.delay(1500)
                            // 强制退出到登录页
                            com.aurora.chat.data.local.LocalStorage.logout(ctx)
                            com.aurora.chat.TcpService.stop(ctx)
                            com.aurora.chat.ui.viewmodel.ChatViewModel.removeUserData(AuroraApi.currentUserId)
                            android.os.Process.killProcess(android.os.Process.myPid())
                        } else {
                            addLine(r.message, false); addEmpty(); termState = TermState.NORMAL
                        }
                    } catch (e: Exception) {
                        addLine("注销失败: ${e.message}", false); addEmpty(); termState = TermState.NORMAL
                    }
                }
            }
            TermState.CREATE_GROUP_AVATAR -> {
                if (input.trim() == "确定") {
                    pendingAvatar = true
                    avatarLauncher.launch("image/*")
                } else {
                    addLine("请输入'确定'选择图片", false); addEmpty()
                }
            }
            TermState.CREATE_GROUP_NAME -> {
                groupName = input.trim()
                if (groupName.isBlank()) { addLine("群名称不能为空", false); addEmpty(); return }
                addLine("已保存", false)
                addLine("请输入群签名（回车跳过）", false); addEmpty()
                termState = TermState.CREATE_GROUP_SIG
            }
            TermState.CREATE_GROUP_SIG -> {
                if (input.trim().isNotEmpty()) groupSig = input.trim()
                addLine("已保存", false)
                addLine("请输入群公告（回车跳过）", false); addEmpty()
                termState = TermState.CREATE_GROUP_ANNOUNCE
            }
            TermState.CREATE_GROUP_ANNOUNCE -> {
                if (input.trim().isNotEmpty()) groupAnnounce = input.trim()
                addLine("已保存", false)
                addLine("请输入群欢迎语（回车跳过）", false); addEmpty()
                termState = TermState.CREATE_GROUP_WELCOME
            }
            TermState.CREATE_GROUP_WELCOME -> {
                if (input.trim().isNotEmpty()) groupWelcome = input.trim()
                addLine("已保存", false)
                addLine("确认创建群聊？输入'是'创建", false); addEmpty()
                termState = TermState.CREATE_GROUP_CONFIRM
            }
            TermState.CREATE_GROUP_CONFIRM -> {
                if (input.trim() == "是") {
                    addLine("正在创建群聊...", false)
                    scope.launch {
                        try {
                            val r = AuroraApi.createGroup(AuroraApi.currentUserId, groupName, groupSig, groupAnnounce,
                                welcomeEnabled = groupWelcome.isNotEmpty(), welcomeText = groupWelcome)
                            if (r.success && r.data != null) {
                                val gid = r.data.optLong("id", 0)
                                var displayId = r.data.optLong("display_id", 0)
                                if (displayId == 0L) {
                                    try { displayId = org.json.JSONObject(r.data.toString()).optLong("display_id", 0) } catch (_: Exception) {}
                                }
                                if (gid > 0 && groupAvatarBase64.isNotEmpty()) {
                                    try { AuroraApi.uploadGroupAvatar(gid, groupAvatarBase64) } catch (_: Exception) {}
                                }
                                addLine("创建群聊成功 群ID是$displayId", false); addEmpty()
                            } else addLine("创建失败: ${r.message}", false); addEmpty()
                        } catch (e: Exception) { addLine("创建失败: ${e.message}", false); addEmpty() }
                        termState = TermState.NORMAL
                    }
                } else {
                    addLine("已取消创建", false); addEmpty(); termState = TermState.NORMAL
                }
            }
            TermState.ADD_FRIEND_ID -> {
                val keyword = input.trim()
                if (keyword.isBlank()) { addLine("请输入ID或邮箱", false); addEmpty(); return }
                addFriendTarget = keyword
                addLine("请输入好友申请内容", false); addEmpty()
                termState = TermState.ADD_FRIEND_MSG
            }
            TermState.ADD_FRIEND_MSG -> {
                val msg = input.trim()
                addLine("正在发送好友申请...", false)
                scope.launch {
                    try {
                        val r = AuroraApi.sendFriendRequest(addFriendTarget, msg)
                        if (r.success) addLine("好友申请已发出", false)
                        else addLine(r.message, false)
                    } catch (e: Exception) { addLine("发送失败: ${e.message}", false) }
                    addEmpty(); termState = TermState.NORMAL
                }
            }
            TermState.JOIN_GROUP_INPUT -> {
                val gid = input.trim().toLongOrNull()
                if (gid == null || gid <= 0) { addLine("请输入有效的群ID", false); addEmpty(); return }
                joinGroupId = gid
                addLine("请输入申请内容（回车直接加入）", false); addEmpty()
                termState = TermState.JOIN_GROUP_REASON
            }
            TermState.JOIN_GROUP_REASON -> {
                val reason = input.trim()
                scope.launch {
                    try {
                        val r = AuroraApi.submitJoinRequest(joinGroupId, reason)
                        if (r.success) addLine(r.data ?: "操作成功", false)
                        else addLine(r.message, false)
                    } catch (e: Exception) { addLine("操作失败: ${e.message}", false) }
                    addEmpty(); termState = TermState.NORMAL
                }
            }
            TermState.DOWNLOAD_URL -> {
                val url = input.trim()
                if (url.isBlank()) { addLine("请输入有效的下载链接", false); addEmpty(); return }
                var fileName = url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() } ?: "download.apk"
                if (!fileName.endsWith(".apk", ignoreCase = true)) fileName += ".apk"
                try {
                    val dm = ctx.getSystemService(android.app.DownloadManager::class.java)
                    val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                        .setTitle("Aurora下载 - $fileName")
                        .setDescription("正在下载…")
                        .setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(ctx, android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(true)
                    val downloadId = dm.enqueue(req)
                    addLine("已创建下载任务", false)
                    scope.launch {
                        val query = android.app.DownloadManager.Query().setFilterById(downloadId)
                        var lastPct = -2
                        while (true) {
                            delay(800)
                            try {
                                dm.query(query).use { cursor ->
                                    if (!cursor.moveToFirst()) return@use
                                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                    val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                                    when (status) {
                                        android.app.DownloadManager.STATUS_RUNNING -> {
                                            if (pct != lastPct) { addLine("下载中: $pct%", false); lastPct = pct }
                                        }
                                        android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                                            if (lastPct != 100) addLine("下载中: 100%", false)
                                            addLine("下载完成", false)
                                            delay(500)
                                            try {
                                                val downloadDir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                                                val file = java.io.File(downloadDir, fileName)
                                                if (file.exists()) {
                                                    val fileUri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                        setDataAndType(fileUri, "application/vnd.android.package-archive")
                                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    if (intent.resolveActivity(ctx.packageManager) != null) {
                                                        ctx.startActivity(intent)
                                                        addLine("正在启动安装…", false)
                                                    } else {
                                                        addLine("未找到安装程序，请到通知栏手动安装", false)
                                                    }
                                                } else {
                                                    addLine("文件不存在，请到通知栏手动安装", false)
                                                }
                                            } catch (e: Exception) {
                                                addLine("自动安装失败: ${e.message}，请到通知栏手动安装", false)
                                            }
                                            addEmpty(); termState = TermState.NORMAL; return@launch
                                        }
                                        android.app.DownloadManager.STATUS_FAILED -> {
                                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_REASON))
                                            addLine("下载失败 错误码:$reason", false)
                                            addEmpty(); termState = TermState.NORMAL; return@launch
                                        }
                                        android.app.DownloadManager.STATUS_PENDING -> {
                                            if (lastPct != -1) { addLine("等待下载中…", false); lastPct = -1 }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                addLine("进度查询异常: ${e.message}", false)
                                addEmpty(); termState = TermState.NORMAL; return@launch
                            }
                        }
                    }
                } catch (e: Exception) {
                    addLine("启动下载失败: ${e.message}", false); addEmpty()
                    termState = TermState.NORMAL
                }
            }

            TermState.COMMAND_MENU -> {
                when (input.trim().uppercase()) {
                    "Q" -> { addLine("已退出命令菜单", false); addEmpty(); termState = TermState.NORMAL }
                    else -> { addLine("输入 Q 离开", false); addEmpty() }
                }
            }
            TermState.BATCH_CREATE_COUNT -> {
                val n = input.trim().toIntOrNull()
                if (n == null || n <= 0) { addLine("请输入有效的数量", false); addEmpty(); return }
                batchFileCount = n
                addLine("已保存", false)
                addLine("请输入文件基本名称", false); addEmpty()
                termState = TermState.BATCH_CREATE_NAME
            }
            TermState.BATCH_CREATE_NAME -> {
                val name = input.trim()
                if (name.isBlank()) { addLine("文件名不能为空", false); addEmpty(); return }
                batchFileName = name
                addLine("已保存", false)
                addLine("请输入创建目录（直接回车当前目录）", false); addEmpty()
                termState = TermState.BATCH_CREATE_DIR
            }
            TermState.BATCH_CREATE_DIR -> {
                batchFileDir = input.trim()
                addLine("正在批量创建文件...", false)
                scope.launch {
                    try {
                        val dir = batchFileDir.ifEmpty { "." }
                        val cmds = buildString {
                            for (i in 1..batchFileCount) {
                                val suffix = if (batchFileCount > 1) "_$i" else ""
                                appendLine("touch \"$dir/${batchFileName}$suffix\"")
                            }
                        }
                        val result = execLocal(cmds, rootEnabled)
                        if (!result.contains("Error")) addLine("成功创建 $batchFileCount 个文件", false) else addLine(result, false)
                    } catch (e: Exception) { addLine("创建失败: ${e.message}", false) }
                    addEmpty(); termState = TermState.NORMAL
                }
            }
            TermState.NORMAL -> {}
        }
    }

    // ── 退出确认 ──
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("退出终端", color = Color.White, fontSize = 17.sp) },
            text = { Text("确定退出终端？", color = Color(0xFFB0B0B0), fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { showExitConfirm = false; onDismiss() }) { Text("确定", color = Color.White) } },
            dismissButton = { TextButton(onClick = { showExitConfirm = false }) { Text("取消", color = Color(0xFF9E9E9E)) } }
        )
    }

    fun executeCommand(cmd: String) {
        if (cmd.isBlank() && termState == TermState.NORMAL) return
        val trimmed = cmd.trim()

        //  注销账号
        if (trimmed == "注销账号") {
            val prompt = if (termState != TermState.NORMAL) "请输入:" else "$cmdPrompt "
            addLine("$prompt$cmd", true)
            addLine("是否注销Aurora Chat账号 请输入 '是' 或 '否'", false); addEmpty()
            termState = TermState.DELETE_CONFIRM
            return
        }

        //  退出登录
        if (trimmed == "退出登录") {
            val prompt = if (termState != TermState.NORMAL) "请输入:" else "$cmdPrompt "
            addLine("$prompt$cmd", true)
            addLine("是否退出登录 请输入 '是' 或 '否'", false); addEmpty()
            termState = TermState.LOGOUT_CONFIRM
            return
        }

        //  退出终端（直接退出无确认）
        if (trimmed == "退出终端") {
            addLine("$cmdPrompt $cmd", true)
            onDismiss()
            return
        }

        // 设置菜单处理
        if (termState != TermState.NORMAL) {
            // 命令菜单特殊处理：允许直接输入其他命令（除 Q 退出）
            if (termState == TermState.COMMAND_MENU) {
                if (trimmed.uppercase() == "Q") {
                    addLine("请输入:$cmd", true)
                    addLine("已退出命令菜单", false); addEmpty()
                    termState = TermState.NORMAL
                } else if (cmd.isNotEmpty()) {
                    termState = TermState.NORMAL
                    executeCommand(cmd)
                } else {
                    addLine("请输入:$cmd", true)
                    addLine("输入 Q 离开", false); addEmpty()
                }
                return
            }
            addLine("请输入:$cmd", true)
            handleSettingsInput(cmd)
            return
        }

        // 检测 恢复默认设置
        if (trimmed == "将所有设置恢复成默认") {
            addLine("$cmdPrompt $cmd", true)
            cmdPrompt = "$"; rootEnabled = false
            bgColor = 0xFF000000.toInt(); fgColor = 0xFFE6EDF3.toInt()
            saveSettings()
            addLine("已恢复所有默认设置", false); addEmpty()
            return
        }

        //  退出软件
        if (trimmed == "退出软件") {
            addLine("$cmdPrompt $cmd", true)
            (ctx as? android.app.Activity)?.finishAffinity()
            Thread { Thread.sleep(500); android.os.Process.killProcess(android.os.Process.myPid()) }.start()
            return
        }

        //  打开手电筒
        if (trimmed == "打开手电筒") {
            addLine("$cmdPrompt $cmd", true)
            try {
                val cm = ctx.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val id = cm.cameraIdList[0]
                cm.setTorchMode(id, true)
                flashlightOn = true
                addLine("手电筒已开启，输入Q关闭", false); addEmpty()
            } catch (e: Exception) {
                addLine("无法打开手电筒: ${e.message}", false); addEmpty()
            }
            return
        }
        if (trimmed.uppercase() == "Q" && flashlightOn) {
            try {
                val cm = ctx.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val id = cm.cameraIdList[0]
                cm.setTorchMode(id, false)
            } catch (_: Exception) {}
            flashlightOn = false
            addLine("$cmdPrompt $cmd", true)
            addLine("手电筒已关闭", false); addEmpty()
            return
        }

        //  打开摄像头
        if (trimmed == "打开摄像头") {
            addLine("$cmdPrompt $cmd", true)
            addLine("请选择摄像头: 输入1(前置) 2(后置) Q(取消)", false); addEmpty()
            pendingCameraAsk = true
            return
        }
        if (pendingCameraAsk) {
            when (trimmed) {
                "1" -> { cameraLens = 1; pendingCameraAsk = false; showCameraPreview = true; addLine("$cmdPrompt $cmd", true); addLine("已打开前置摄像头", false); addEmpty() }
                "2" -> { cameraLens = 0; pendingCameraAsk = false; showCameraPreview = true; addLine("$cmdPrompt $cmd", true); addLine("已打开后置摄像头", false); addEmpty() }
                "Q", "q" -> { pendingCameraAsk = false; addLine("$cmdPrompt $cmd", true); addLine("已取消", false); addEmpty() }
                else -> { addLine("请输入1(前置) 2(后置) Q(取消)", false); addEmpty(); return }
            }
            return
        }
        if (trimmed == "打开前置摄像头") {
            cameraLens = 1; showCameraPreview = true; addLine("$cmdPrompt $cmd", true); addLine("已打开前置摄像头", false); addEmpty()
            return
        }
        if (trimmed == "打开后置摄像头") {
            cameraLens = 0; showCameraPreview = true; addLine("$cmdPrompt $cmd", true); addLine("已打开后置摄像头", false); addEmpty()
            return
        }

        //  创建群聊
        if (trimmed == "创建群聊") {
            addLine("$cmdPrompt $cmd", true)
            addLine("请上传群头像，输入'确定'选择图片", false); addEmpty()
            termState = TermState.CREATE_GROUP_AVATAR
            return
        }
        //  添加好友
        if (trimmed == "添加好友") {
            addLine("$cmdPrompt $cmd", true)
            addLine("请输入你要添加好友的ID或邮箱", false); addEmpty()
            termState = TermState.ADD_FRIEND_ID
            return
        }
        //  进入群聊
        if (trimmed == "进入群聊") {
            addLine("$cmdPrompt $cmd", true)
            addLine("请输入群ID", false); addEmpty()
            termState = TermState.JOIN_GROUP_INPUT
            return
        }

        //  清屏
        if (trimmed == "清屏") {
            lines = listOf()
            return
        }

        //  下载应用
        if (trimmed == "下载应用") {
            addLine("$cmdPrompt $cmd", true)
            addLine("请输入下载源（安装包直链）", false); addEmpty()
            termState = TermState.DOWNLOAD_URL
            return
        }

        //  命令菜单（自动从 cmdDefs 生成，描述右对齐）
        if (trimmed == "/命令菜单" || trimmed == "命令菜单") {
            addLine("$cmdPrompt $cmd", true)
            addLine("命令列表", false)
            addEmpty()
            fun displayWidth(s: String): Int {
                var w = 0
                for (c in s) w += if (c.code > 127) 2 else 1
                return w
            }
            val maxW = cmdDefs.maxOf { d: CmdDef -> displayWidth(d.trigger) }
            for (d in cmdDefs) {
                val w = displayWidth(d.trigger)
                val pad = " ".repeat(maxW - w + 4)
                addLine(d.trigger + pad + d.desc, false, d.trigger)
            }
            addEmpty()
            addLine("输入 Q 离开", false)
            addEmpty()
            termState = TermState.COMMAND_MENU
            return
        }
        //  手机息屏
        if (trimmed == "手机息屏") {
            addLine("$cmdPrompt $cmd", true)
            if (!rootEnabled) {
                addLine("未开启root模式，请先输入 /设置 在设置菜单中开启root模式", false); addEmpty()
            } else {
                scope.launch {
                    try {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 26"))
                        addLine("已执行息屏操作", false)
                    } catch (e: Exception) {
                        addLine("息屏失败: ${e.message}（可能无root权限）", false)
                    }
                    addEmpty()
                }
            }
            return
        }

        //  手机关机
        if (trimmed == "手机关机") {
            addLine("$cmdPrompt $cmd", true)
            if (!rootEnabled) {
                addLine("未开启root模式，请先输入 /设置 在设置菜单中开启root模式", false); addEmpty()
            } else {
                addLine("正在关机...", false)
                scope.launch {
                    try {
                        // 直接执行关机，不依赖 execLocal 的返回值判断
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p"))
                        // 不 waitFor，关机命令会被系统中断
                        addLine("已执行关机命令，手机即将关闭", false)
                    } catch (e: Exception) {
                        addLine("关机失败: ${e.message}（可能无root权限）", false)
                    }
                    addEmpty()
                }
            }
            return
        }

        //  批量创建文件
        if (trimmed == "批量创建文件") {
            addLine("$cmdPrompt $cmd", true)
            addLine("请输入要创建的文件数量", false); addEmpty()
            termState = TermState.BATCH_CREATE_COUNT
            return
        }

        if (trimmed == "/设置" || trimmed == "设置") {
            addLine("$cmdPrompt $cmd", true)
            addLine("1.命令提示符", false)
            addLine("2.root模式", false)
            addLine("3.终端颜色", false)
            addLine("输入序号进入详情输入Q返回", false)
            addEmpty()
            termState = TermState.SETTINGS_MENU
            return
        }

        isLoading = true
        addLine("$cmdPrompt $cmd", true)
        if (commandHistory.size >= CMD_HISTORY_MAX) commandHistory = commandHistory.drop(1).toMutableList()
        commandHistory = commandHistory + cmd
        historyIndex = -1
        currentInput = ""
        scope.launch {
            val result = execLocal(cmd, rootEnabled)
            addLine(result, false)
            addEmpty()
            isLoading = false
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(bgColor))
                .imePadding()
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        var total = 0f
                        total += delta
                        if (kotlin.math.abs(total) > 150f) {
                            total = 0f; showExitConfirm = true
                        }
                    },
                    onDragStopped = { }
                )
        ) {
            // ── 输出区域（可滚动，整个画面） ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .clickable(interactionSource = noRipple, indication = null) {
                        showKeyboard()
                    }
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            ) {
                Column {
                    lines.forEach { line ->
                        if (line.copyCommand != null) {
                            Text(
                                text = line.text,
                                fontSize = 14.sp,
                                color = textColor,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 22.sp,
                                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    try {
                                        val cb = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cb.setPrimaryClip(android.content.ClipData.newPlainText("command", line.copyCommand))
                                        android.widget.Toast.makeText(ctx, "「${line.copyCommand}」命令已复制", android.widget.Toast.LENGTH_SHORT).show()
                                        showKeyboard()
                                    } catch (_: Exception) {}
                                }
                            )
                        } else {
                            Text(
                                text = line.text,
                                fontSize = 14.sp,
                                color = textColor,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 22.sp
                            )
                        }
                    }
                    if (isLoading) {
                        Text("> ...", fontSize = 14.sp, color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace)
                    }
                    // ── 输入行（融合在输出里） ──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(displayPrompt, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = promptColor, fontFamily = FontFamily.Monospace)
                        if (currentInput.isNotEmpty()) {
                            Text(currentInput, fontSize = 14.sp, color = textColor,
                                fontFamily = FontFamily.Monospace)
                        }
                        if (cursorVisible) {
                            Text("▌", fontSize = 14.sp, color = promptColor, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Spacer(Modifier.height(80.dp))
                }
            }

            // ── 隐藏输入框（极小，只用来获取键盘输入） ──
            BasicTextField(
                value = currentInput,
                onValueChange = {
                    if (it.length < currentInput.length) { currentInput = it; return@BasicTextField }
                    if (it.endsWith('\n')) {
                        val cmd = it.dropLast(1)
                        currentInput = ""
                        if (cmd.isNotEmpty()) {
                            executeCommand(cmd)
                        } else if (termState != TermState.NORMAL) {
                            executeCommand("")
                        } else {
                            addLine(displayPrompt, true)
                        }
                        return@BasicTextField
                    }
                    currentInput = it
                },
                modifier = Modifier.fillMaxWidth().height(1.dp).focusRequester(focusRequester),
                textStyle = TextStyle(fontSize = 1.sp, color = Color.Transparent),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
                decorationBox = { innerTextField -> innerTextField() }
            )
        }
    }

    // ── 摄像头预览弹窗 ──
    if (showCameraPreview) {
        val camSelector = if (cameraLens == 1) androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA else androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        Dialog(
            onDismissRequest = { showCameraPreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false)
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx2 ->
                        val previewView = androidx.camera.view.PreviewView(ctx2).apply { scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER }
                        try {
                            val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx2).get()
                            val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, camSelector, preview)
                        } catch (_: Exception) {}
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.TopEnd) {
                    androidx.compose.material3.TextButton(onClick = { showCameraPreview = false }) {
                        androidx.compose.material3.Text("关闭", color = Color.White)
                    }
                }
            }
        }
    }
}
