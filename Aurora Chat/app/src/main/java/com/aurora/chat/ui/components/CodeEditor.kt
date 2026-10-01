package com.aurora.chat.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.lua.LuaRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CeBlue = Color(0xFF2563EB)
private val CePurple = Color(0xFF7C3AED)
private val CeRed = Color(0xFFDC2626)
private val CeGreen = Color(0xFF10B981)

private const val UNDO_STACK_MAX = 50
private const val UNDO_TEXT_MAX_LEN = 100_000
private const val UNDO_GROUP_MS = 800L

/** 通用代码编辑器：支持行号、双指缩放、语法高亮、自动缩进、括号配对、选择高亮、
 *  搜索/替换、撤销/重做、跳转行、终端、Lua 脚本运行、格式转换、精简、清空、键盘锁定、未保存提醒。
 *
 *  @param title            顶栏显示的文件名/标题
 *  @param subtitle         顶栏副标题
 *  @param initialContent   初始文本（加载完成后传入）
 *  @param isLoading        是否处于加载中
 *  @param onSave           保存回调；closeAfter=true 表示保存后关闭
 *  @param onDismiss        关闭回调（组件内部会先处理未保存提醒）
 *  @param readOnly         只读模式（不显示保存按钮）
 *  @param luaRootFile      运行 Lua 时关联的本地文件（工作区用），不传则直接运行编辑器内存内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditor(
    title: String,
    subtitle: String = "",
    initialContent: String,
    isLoading: Boolean,
    onSave: suspend (content: String, closeAfter: Boolean) -> Unit,
    onDismiss: () -> Unit,
    readOnly: Boolean = false,
    luaRootFile: java.io.File? = null
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val noRipple = remember { MutableInteractionSource() }

    // 文本状态：以 initialContent 为 key 重建，保证切换文件时状态干净
    val editText = remember(initialContent) { mutableStateOf(TextFieldValue(initialContent)) }

    // 保存快照与脏状态
    var savedSnapshot by remember(initialContent) { mutableStateOf(initialContent) }
    var justSaved by remember { mutableStateOf(false) }
    val isDirty by remember(initialContent) {
        derivedStateOf { editText.value.text != savedSnapshot }
    }

    // 撤销/重做
    val undoStack = remember { mutableStateListOf<String>() }
    val redoStack = remember { mutableStateListOf<String>() }
    var lastUndoTs by remember { mutableLongStateOf(0L) }

    // 视图状态
    val zoomLevel = remember { mutableFloatStateOf(1f) }
    val editorHScroll = rememberScrollState()
    var showSearchDialog by remember { mutableStateOf(false) }
    var showReplaceDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceFind by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var replaceIdx by remember(replaceFind, editText.value.text) { mutableIntStateOf(0) }
    var showGotoDialog by remember { mutableStateOf(false) }
    var gotoLineInput by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showConvertDialog by remember { mutableStateOf(false) }
    var convertFrom by remember { mutableStateOf("") }
    var convertTo by remember { mutableStateOf("") }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    var termOutput by remember { mutableStateOf("") }
    var keyboardVisible by remember { mutableStateOf(true) }
    var selectedWord by remember { mutableStateOf<String?>(null) }

    // 代码折叠：记录被折叠的行区间[startLine, endLine]（0-based，含）
    val foldedRanges = remember { mutableStateListOf<Pair<Int, Int>>() }

    // 语法高亮扩展名
    val fileExt = remember(title) { title.substringAfterLast('.', "").lowercase() }

    val lineCount by remember(editText.value.text) {
        derivedStateOf { editText.value.text.count { it == '\n' } + 1 }
    }
    val charCount by remember(editText.value.text) {
        derivedStateOf { editText.value.text.length }
    }
    // 行号在折叠后按可见行显示
    val lineNumbersText = remember(lineCount, foldedRanges.toList()) {
        buildString {
            var visibleLine = 1
            for (i in 1..lineCount) {
                val folded = foldedRanges.any { it.first < i && i <= it.second }
                if (folded) continue
                if (visibleLine > 1) append('\n')
                append(visibleLine)
                visibleLine++
            }
        }
    }

    // 工具函数
    fun toast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    fun pushUndo(oldText: String) {
        if (oldText.length > UNDO_TEXT_MAX_LEN) return
        val now = System.currentTimeMillis()
        val grouped = undoStack.isNotEmpty() && now - lastUndoTs < UNDO_GROUP_MS
        lastUndoTs = now
        if (grouped) return
        if (undoStack.isNotEmpty() && undoStack.last() == oldText) return
        undoStack.add(oldText)
        if (undoStack.size > UNDO_STACK_MAX) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun doUndo() {
        if (undoStack.isEmpty()) return
        redoStack.add(editText.value.text)
        editText.value = editText.value.copy(text = undoStack.removeAt(undoStack.lastIndex))
    }

    fun doRedo() {
        if (redoStack.isEmpty()) return
        undoStack.add(editText.value.text)
        editText.value = editText.value.copy(text = redoStack.removeAt(redoStack.lastIndex))
    }

    fun save(closeAfter: Boolean) {
        scope.launch {
            onSave(editText.value.text, closeAfter)
            savedSnapshot = editText.value.text
            justSaved = true
        }
    }

    /**
     * 运行当前 .lua 脚本，输出到内嵌终端。
     * 工作区模式用本地文件路径运行；网站托管模式由于没有本地文件，直接运行编辑器内存内容。
     */
    fun runLuaScript(file: java.io.File? = null) {
        if (!title.endsWith(".lua", ignoreCase = true)) { toast("仅支持 .lua 文件"); return }
        showTerminal = true
        termOutput = ""
        val runner = LuaRunner(
            printLine = { line ->
                termOutput += line + "\n"
                if (termOutput.length > 500_000) termOutput = termOutput.takeLast(400_000)
            }
        )
        scope.launch(Dispatchers.IO) {
            val result = if (file != null) runner.execFile(file) else runner.exec(editText.value.text)
            withContext(Dispatchers.Main) {
                result.onFailure { termOutput += "[error] ${it.message ?: it.javaClass.simpleName}\n" }
            }
        }
    }

    fun jumpToMatch(query: String, idx: Int) {
        if (query.isEmpty()) return
        var p = 0
        var m = 0
        while (true) {
            val at = editText.value.text.indexOf(query, p)
            if (at < 0) break
            if (m == idx) {
                editText.value = editText.value.copy(selection = TextRange(at))
                break
            }
            m++
            p = at + query.length
        }
    }

    fun gotoLine() {
        val line = gotoLineInput.toIntOrNull()
        if (line == null || line < 1) { toast("请输入有效行号"); return }
        val text = editText.value.text
        var offset = 0
        var l = 1
        while (l < line && offset < text.length) {
            if (text[offset] == '\n') l++
            offset++
        }
        editText.value = editText.value.copy(selection = TextRange(offset.coerceAtMost(text.length)))
        showGotoDialog = false
    }

    fun currentLineStart(text: String, cursor: Int): Int {
        var i = cursor.coerceAtMost(text.length) - 1
        while (i >= 0 && text[i] != '\n') i--
        return i + 1
    }

    fun lineIndent(text: String, lineStart: Int): String {
        val sb = StringBuilder()
        var i = lineStart
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    /** 自动缩进 + 括号补全 + 选择高亮收集 */
    fun processInput(before: TextFieldValue, after: TextFieldValue): TextFieldValue {
        val old = before.text
        val new = after.text
        val cursor = after.selection.start
        if (new.length == old.length + 1 && cursor > 0) {
            val ch = new[cursor - 1]
            // 回车自动缩进
            if (ch == '\n') {
                val prevStart = currentLineStart(old, cursor - 1)
                val indent = lineIndent(old, prevStart)
                val prevLine = old.substring(prevStart, cursor - 1)
                val extra = if (prevLine.trimEnd().endsWith("{") || prevLine.trimEnd().endsWith(":")) {
                    "    "
                } else ""
                val insertion = indent + extra
                if (insertion.isNotEmpty()) {
                    val newText = new.substring(0, cursor) + insertion + new.substring(cursor)
                    val newCursor = cursor + insertion.length
                    return after.copy(text = newText, selection = TextRange(newCursor))
                }
            }
            // 括号自动配对
            val pair = when (ch) {
                '(' -> ")"
                '{' -> "}"
                '[' -> "]"
                '"' -> "\""
                '\'' -> "'"
                else -> null
            }
            if (pair != null) {
                val newText = new.substring(0, cursor) + pair + new.substring(cursor)
                return after.copy(text = newText, selection = TextRange(cursor))
            }
        }
        return after
    }

    fun onTextChange(newValue: TextFieldValue) {
        if (newValue.text != editText.value.text) {
            pushUndo(editText.value.text)
            val processed = processInput(editText.value, newValue)
            editText.value = processed
        } else {
            editText.value = newValue
        }
        // 收集选中词
        val sel = newValue.selection
        if (sel.start != sel.end) {
            val word = newValue.text.substring(sel.start.coerceAtLeast(0), sel.end.coerceAtMost(newValue.text.length))
            selectedWord = if (word.all { it.isLetterOrDigit() || it == '_' }) word else null
        } else {
            selectedWord = null
        }
    }

    // 折叠辅助
    fun toggleFold(line: Int) {
        val text = editText.value.text
        val lines = text.lines()
        if (line < 0 || line >= lines.size) return
        val baseIndent = lines[line].takeWhile { it == ' ' || it == '\t' }.length
        var end = line
        for (i in line + 1 until lines.size) {
            val indent = lines[i].takeWhile { it == ' ' || it == '\t' }.length
            if (lines[i].isBlank()) continue
            if (indent > baseIndent) end = i
            else break
        }
        if (end == line) return
        val existing = foldedRanges.indexOfFirst { it.first == line && it.second == end }
        if (existing >= 0) foldedRanges.removeAt(existing)
        else foldedRanges.add(line to end)
    }

    // 搜索/替换匹配数
    val searchMatchCount = remember(searchQuery, editText.value.text) {
        if (searchQuery.isEmpty()) 0 else countOccurrences(editText.value.text, searchQuery)
    }
    val replaceMatchCount = remember(replaceFind, editText.value.text) {
        if (replaceFind.isEmpty()) 0 else countOccurrences(editText.value.text, replaceFind)
    }

    // 高亮转换：语法/搜索/替换/选中词高亮（折叠占位用普通文本渲染）
    val highlightTransformation = remember(
        searchQuery, replaceFind, replaceIdx, showReplaceDialog,
        selectedWord, fileExt, editText.value.text
    ) {
        VisualTransformation { text ->
            val str = text.text
            val annotated = buildAnnotatedString {
                append(str)
                // 语法高亮
                applySyntaxHighlight(str, fileExt)
                // 搜索高亮
                if (searchQuery.isNotEmpty()) {
                    highlightOccurrences(str, searchQuery, Color(0x3380BFFF))
                }
                // 替换高亮
                if (replaceFind.isNotEmpty() && showReplaceDialog) {
                    var pos = 0
                    var matchIdx = 0
                    while (true) {
                        val at = str.indexOf(replaceFind, pos)
                        if (at < 0) break
                        val color = if (matchIdx == replaceIdx) Color(0x66FFB800) else Color(0x3380BFFF)
                        addStyle(SpanStyle(background = color), at, at + replaceFind.length)
                        matchIdx++
                        pos = at + replaceFind.length
                    }
                }
                // 选中词高亮
                selectedWord?.let { w ->
                    if (w.isNotBlank() && w != searchQuery) {
                        highlightOccurrences(str, w, Color(0x33A78BFA))
                    }
                }
            }
            TransformedText(annotated, OffsetMapping.Identity)
        }
    }

    // 返回键处理
    BackHandler(enabled = showTerminal) { showTerminal = false }
    BackHandler(enabled = !showTerminal) {
        if (isDirty && !readOnly) showExitConfirm = true else onDismiss()
    }

    // 终端状态
    var termInput by remember { mutableStateOf("") }
    val termScroll = rememberScrollState()
    val termFocus = remember { FocusRequester() }
    val termImm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var shellProc by remember { mutableStateOf<java.lang.Process?>(null) }

    DisposableEffect(showTerminal) {
        var proc: java.lang.Process? = null
        if (showTerminal) {
            // Android 普通应用没有完整 PATH，必须显式指向 /system/bin/sh；
            // 同时检测 busybox/toybox 等替代 shell，不可用再回显错误并关闭终端。
            val shellPath = listOf(
                "/system/bin/sh",
                "/system/bin/ash",
                "/system/bin/bash",
                "/system/bin/busybox",
                "/system/bin/toybox"
            ).firstOrNull { java.io.File(it).canExecute() }
            if (shellPath == null) {
                termOutput += "[error] 本设备未找到可用的系统 shell（普通应用通常无法直接执行 sh）。\n"
                shellProc = null
            } else {
                try {
                    // Android 普通应用子进程 PATH 不全，sh 内 ls/cat/cd 等基础命令会 not found；
                    // 与 SandboxTerminal/AiChatManager 保持一致，显式注入系统命令目录。
                    val pb = ProcessBuilder(shellPath).redirectErrorStream(true)
                    pb.environment()["PATH"] = "/system/bin:/system/xbin:/sbin:/vendor/bin:/data/local/bin"
                    // 工作目录必须锚定到 AI 工作区根目录：App 进程对 / 无读权限，
                    // 不设置 directory 时继承 cwd=/，ls 会报 Permission denied。
                    val workDir = java.io.File(ctx.filesDir, "ai_files")
                    workDir.mkdirs()
                    pb.directory(workDir)
                    proc = pb.start()
                    shellProc = proc
                    scope.launch(Dispatchers.IO) {
                        try {
                            java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream, "utf-8")).useLines { lines ->
                                lines.forEach { line ->
                                    termOutput += line + "\n"
                                    if (termOutput.length > 500_000) termOutput = termOutput.takeLast(400_000)
                                }
                            }
                            termOutput += "[shell 已退出]\n"
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    termOutput += "[error] 启动 shell 失败: ${e.message ?: e.javaClass.simpleName}\n"
                    shellProc = null
                }
            }
        }
        onDispose { try { proc?.destroy() } catch (_: Exception) {} }
    }

    fun submitCmd() {
        val c = termInput.trim()
        termInput = ""
        if (c.isEmpty()) { termOutput += "$ \n"; return }
        termOutput += "$ $c\n"
        if (c == "clear" || c == "cls") { termOutput = ""; return }
        val os = shellProc?.outputStream ?: run { showTerminal = false; return }
        try {
            os.write((c + "\n").toByteArray(Charsets.UTF_8))
            os.flush()
        } catch (e: Exception) {
            termOutput += "sh: ${e.message ?: "写入失败"}\n"
            showTerminal = false
        }
    }

    // 终端打开期间状态栏反色
    val termView = LocalView.current
    DisposableEffect(showTerminal) {
        val win = (termView.context as? android.app.Activity)?.window
        if (showTerminal && win != null) {
            androidx.core.view.WindowCompat.getInsetsController(win, termView).isAppearanceLightStatusBars = false
            androidx.core.view.WindowCompat.getInsetsController(win, termView).isAppearanceLightNavigationBars = false
        }
        onDispose {
            if (win != null) {
                androidx.core.view.WindowCompat.getInsetsController(win, termView).isAppearanceLightStatusBars = true
                androidx.core.view.WindowCompat.getInsetsController(win, termView).isAppearanceLightNavigationBars = true
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.White)
            .clickable(interactionSource = noRipple, indication = null) {}
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            // 顶栏
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(interactionSource = noRipple, indication = null) {
                            if (isDirty && !readOnly) showExitConfirm = true else onDismiss()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle.ifBlank { "代码编辑器" }, fontSize = 10.sp, color = Color(0xFF9CA3AF))
                }
                if (!readOnly) {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (isLoading) Color(0xFF93C5FD) else if (justSaved && !isDirty) CeGreen else CeBlue)
                            .clickable(enabled = !isLoading, interactionSource = noRipple, indication = null) { save(false) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isLoading) "保存中..." else if (justSaved && !isDirty) "已保存" else "保存", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

            // 编辑区
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    if (zoomChange != 1f) {
                                        zoomLevel.floatValue = (zoomLevel.floatValue * zoomChange).coerceIn(0.5f, 3f)
                                        event.changes.forEach { if (it.pressed) it.consume() }
                                    }
                                }
                                if (!event.changes.any { it.pressed }) break
                            }
                        }
                    }
            ) {
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CeBlue, modifier = Modifier.size(36.dp))
                    }
                } else {
                    val baseFontSize = (14 * zoomLevel.floatValue).coerceIn(7f, 42f)
                    val baseLineHeight = (22 * zoomLevel.floatValue).coerceIn(11f, 66f)
                    Row(Modifier.fillMaxSize()) {
                        // 行号列
                        Box(
                            Modifier
                                .width((44 * zoomLevel.floatValue).dp.coerceIn(28.dp, 80.dp))
                                .fillMaxHeight()
                                .background(Color(0xFFF9FAFB))
                                .padding(top = 12.dp, end = 8.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Text(
                                text = lineNumbersText,
                                fontSize = baseFontSize.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF9CA3AF),
                                lineHeight = baseLineHeight.sp
                            )
                        }
                        BoxWithConstraints(
                            Modifier.weight(1f).fillMaxHeight().horizontalScroll(editorHScroll)
                        ) {
                            BasicTextField(
                                value = editText.value,
                                onValueChange = { onTextChange(it) },
                                readOnly = readOnly || !keyboardVisible || showTerminal,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .widthIn(min = maxWidth)
                                    .width(IntrinsicSize.Max)
                                    .padding(start = 8.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                                textStyle = TextStyle(
                                    fontSize = baseFontSize.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF1F2937),
                                    lineHeight = baseLineHeight.sp
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(CeBlue),
                                visualTransformation = highlightTransformation,
                                decorationBox = { innerTextField ->
                                    if (editText.value.text.isEmpty()) {
                                        Text(
                                            text = "在此输入文件内容...",
                                            fontSize = baseFontSize.sp,
                                            fontFamily = FontFamily.Monospace,
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

            // 底部工具栏
            if (!isLoading) {
                @Composable
                fun Tool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .alpha(if (enabled) 1f else 0.35f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = enabled, interactionSource = noRipple, indication = null, onClick = onClick)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
                        Text(label, fontSize = 10.sp, color = Color(0xFF6B7280))
                    }
                }
                Column(
                    Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(top = 4.dp, bottom = 12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFFF8FAFC)).padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("行: $lineCount", fontSize = 11.sp, color = Color(0xFF6B7280))
                        Spacer(Modifier.width(16.dp))
                        Text("字: $charCount", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        Spacer(Modifier.width(16.dp))
                        Text("UTF-8", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        if (isDirty) {
                            Spacer(Modifier.width(16.dp))
                            Text("未保存", fontSize = 11.sp, color = CeRed, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Tool(Icons.Filled.Search, "搜索", CeBlue) { showSearchDialog = true }
                        Tool(Icons.Filled.FindReplace, "替换", CeBlue) { showReplaceDialog = true }
                        Tool(Icons.Filled.Undo, "撤销", CeBlue, undoStack.isNotEmpty()) { doUndo() }
                        Tool(Icons.Filled.Redo, "重做", CeBlue, redoStack.isNotEmpty()) { doRedo() }
                        Tool(Icons.Filled.Save, "保存", if (justSaved && !isDirty) CeGreen else CeBlue) { save(false) }
                        Tool(Icons.Filled.Terminal, "终端", Color(0xFF374151)) { showTerminal = true }
                        Tool(Icons.Filled.ContentCopy, "复制全部", CeBlue) {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("text", editText.value.text))
                            toast("已复制全部内容")
                        }
                        Tool(Icons.Filled.FormatListNumbered, "跳转行", CePurple) { gotoLineInput = ""; showGotoDialog = true }
                        Tool(Icons.Filled.Compress, "精简", CePurple) {
                            pushUndo(editText.value.text)
                            val minified = editText.value.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                            editText.value = TextFieldValue(minified)
                            toast("已精简")
                        }
                        Tool(Icons.Filled.UnfoldLess, "折叠", CePurple) {
                            val sel = editText.value.selection.start.coerceIn(0, editText.value.text.length)
                            val line = editText.value.text.substring(0, sel).count { it == '\n' }
                            toggleFold(line)
                        }
                        Tool(Icons.Filled.PlayArrow, "运行", CeGreen, title.endsWith(".lua", ignoreCase = true)) {
                            runLuaScript(luaRootFile)
                        }
                        Tool(Icons.Filled.SwapHoriz, "转换", CePurple) { showConvertDialog = true }
                        Tool(Icons.Filled.DeleteSweep, "清空", CeRed, editText.value.text.isNotEmpty()) { showClearConfirm = true }
                        Tool(
                            if (keyboardVisible) Icons.Filled.Keyboard else Icons.Filled.KeyboardHide,
                            if (keyboardVisible) "键盘" else "收起",
                            if (keyboardVisible) CeBlue else CeRed
                        ) {
                            keyboardVisible = !keyboardVisible
                            val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            try {
                                val token = (ctx as? android.app.Activity)?.currentFocus?.windowToken
                                if (token != null) imm.hideSoftInputFromWindow(token, 0)
                            } catch (_: Exception) {}
                            toast(if (!keyboardVisible) "键盘已锁定" else "键盘已解锁")
                        }
                    }
                }
            }
        }

        // 搜索弹窗
        if (showSearchDialog) {
            var searchIdx by remember(searchQuery, editText.value.text) { mutableIntStateOf(0) }
            val matchCount = if (searchQuery.isEmpty()) 0 else countOccurrences(editText.value.text, searchQuery)
            if (searchIdx >= matchCount) searchIdx = (matchCount - 1).coerceAtLeast(0)
            AlertDialog(
                onDismissRequest = { showSearchDialog = false; searchQuery = "" },
                title = { Text("搜索", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("输入搜索关键词") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        if (matchCount > 0) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("找到 $matchCount 处（当前第 ${searchIdx + 1} 处）", fontSize = 13.sp, color = Color(0xFF6B7280))
                                Row {
                                    TextButton(onClick = { searchIdx = if (searchIdx > 0) searchIdx - 1 else matchCount - 1; jumpToMatch(searchQuery, searchIdx) }) { Text("上一个", fontSize = 13.sp) }
                                    TextButton(onClick = { searchIdx = if (searchIdx < matchCount - 1) searchIdx + 1 else 0; jumpToMatch(searchQuery, searchIdx) }) { Text("下一个", fontSize = 13.sp) }
                                }
                            }
                        } else if (searchQuery.isNotEmpty()) { Text("未找到匹配", fontSize = 13.sp, color = CeRed) }
                    }
                },
                confirmButton = { TextButton(onClick = { showSearchDialog = false }) { Text("确定") } },
                dismissButton = { TextButton(onClick = { showSearchDialog = false; searchQuery = "" }) { Text("取消") } }
            )
        }

        // 替换弹窗
        if (showReplaceDialog) {
            if (replaceIdx >= replaceMatchCount) replaceIdx = (replaceMatchCount - 1).coerceAtLeast(0)
            var showReplaceAllConfirm by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { showReplaceDialog = false; replaceFind = ""; replaceWith = "" },
                title = { Text("替换", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        OutlinedTextField(value = replaceFind, onValueChange = { replaceFind = it }, placeholder = { Text("查找内容") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = replaceWith, onValueChange = { replaceWith = it }, placeholder = { Text("替换为") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        if (replaceMatchCount > 0) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("找到 $replaceMatchCount 处（当前第 ${replaceIdx + 1} 处）", fontSize = 13.sp, color = Color(0xFF6B7280))
                                Row {
                                    TextButton(onClick = { replaceIdx = if (replaceIdx > 0) replaceIdx - 1 else replaceMatchCount - 1; jumpToMatch(replaceFind, replaceIdx) }) { Text("上一个", fontSize = 13.sp) }
                                    TextButton(onClick = { replaceIdx = if (replaceIdx < replaceMatchCount - 1) replaceIdx + 1 else 0; jumpToMatch(replaceFind, replaceIdx) }) { Text("下一个", fontSize = 13.sp) }
                                }
                            }
                        } else if (replaceFind.isNotEmpty()) { Text("未找到匹配", fontSize = 13.sp, color = CeRed) }
                    }
                },
                confirmButton = {
                    Row {
                        if (replaceMatchCount > 0) {
                            TextButton(onClick = {
                                val f = replaceFind; val r = replaceWith
                                var pos = 0; var cur = 0
                                while (true) {
                                    val at = editText.value.text.indexOf(f, pos)
                                    if (at < 0) break
                                    if (cur == replaceIdx) {
                                        pushUndo(editText.value.text)
                                        editText.value = TextFieldValue(editText.value.text.substring(0, at) + r + editText.value.text.substring(at + f.length))
                                        toast("已替换第 ${replaceIdx + 1} 处"); break
                                    }
                                    cur++; pos = at + f.length
                                }
                            }) { Text("替换当前", fontSize = 13.sp, color = CeBlue) }
                        }
                        TextButton(onClick = { if (replaceMatchCount > 0) showReplaceAllConfirm = true else toast("未找到匹配内容") }) { Text("全部替换", fontSize = 13.sp, color = CeRed) }
                    }
                },
                dismissButton = { TextButton(onClick = { showReplaceDialog = false; replaceFind = ""; replaceWith = "" }) { Text("取消") } }
            )
            if (showReplaceAllConfirm) {
                AlertDialog(
                    onDismissRequest = { showReplaceAllConfirm = false },
                    title = { Text("确认全部替换", fontWeight = FontWeight.Bold, color = CeRed) },
                    text = { Text("确定要将全部 $replaceMatchCount 处「$replaceFind」替换为「$replaceWith」吗？\n\n此操作不可撤销。", fontSize = 14.sp, lineHeight = 22.sp) },
                    confirmButton = {
                        TextButton(onClick = {
                            pushUndo(editText.value.text)
                            editText.value = TextFieldValue(editText.value.text.replace(replaceFind, replaceWith))
                            toast("已全部替换 $replaceMatchCount 处")
                            showReplaceAllConfirm = false; showReplaceDialog = false; replaceFind = ""; replaceWith = ""
                        }) { Text("确认替换", color = CeRed) }
                    },
                    dismissButton = { TextButton(onClick = { showReplaceAllConfirm = false }) { Text("取消") } }
                )
            }
        }

        // 转换弹窗
        if (showConvertDialog) {
            AlertDialog(
                onDismissRequest = { showConvertDialog = false; convertFrom = ""; convertTo = "" },
                title = { Text("格式转换", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        OutlinedTextField(value = convertFrom, onValueChange = { convertFrom = it }, placeholder = { Text("被转换的格式（例: 大写）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = convertTo, onValueChange = { convertTo = it }, placeholder = { Text("转换后的格式（例: 小写）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text("常用转换: 大写→小写 小写→大写 驼峰→下划线 反转", fontSize = 12.sp, color = Color(0xFF6B7280))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val text = editText.value.text
                        val from = convertFrom.lowercase().trim()
                        val to = convertTo.lowercase().trim()
                        val result = when {
                            (from == "大写" || from == "uppercase") && (to == "小写" || to == "lowercase") -> text.lowercase()
                            (from == "小写" || from == "lowercase") && (to == "大写" || to == "uppercase") -> text.uppercase()
                            (to == "反" || to == "reverse" || to == "反转") -> text.reversed()
                            else -> text
                        }
                        pushUndo(text)
                        editText.value = TextFieldValue(result)
                        toast("转换完成")
                        showConvertDialog = false; convertFrom = ""; convertTo = ""
                    }) { Text("执行转换") }
                },
                dismissButton = { TextButton(onClick = { showConvertDialog = false; convertFrom = ""; convertTo = "" }) { Text("取消") } }
            )
        }

        // 跳转行弹窗
        if (showGotoDialog) {
            AlertDialog(
                onDismissRequest = { showGotoDialog = false },
                title = { Text("跳转到行", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("当前共 $lineCount 行，输入要跳转的行号(1~$lineCount):", fontSize = 13.sp, color = Color(0xFF6B7280))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = gotoLineInput,
                            onValueChange = { gotoLineInput = it.filter { ch -> ch.isDigit() }.take(7) },
                            placeholder = { Text("行号") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { gotoLine() }) { Text("跳转") } },
                dismissButton = { TextButton(onClick = { showGotoDialog = false }) { Text("取消") } }
            )
        }

        // 清空确认
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("确认清空", fontWeight = FontWeight.Bold, color = CeRed) },
                text = { Text("确定要清空全部内容吗？\n\n清空后可用「撤销」恢复，或直接不保存退出。", fontSize = 14.sp, lineHeight = 22.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        pushUndo(editText.value.text)
                        editText.value = TextFieldValue("")
                        showClearConfirm = false
                        toast("已清空(可撤销)")
                    }) { Text("清空", color = CeRed) }
                },
                dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
            )
        }

        // 退出确认
        if (showExitConfirm) {
            AlertDialog(
                onDismissRequest = { showExitConfirm = false },
                title = { Text("未保存提醒", fontWeight = FontWeight.Bold) },
                text = { Text("检测到文件未保存，是否保存并退出？", fontSize = 14.sp) },
                confirmButton = {
                    TextButton(onClick = { showExitConfirm = false; save(true) }) {
                        Text("保存并退出", color = CeBlue, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { showExitConfirm = false; onDismiss() }) { Text("不保存退出", color = CeRed) }
                        TextButton(onClick = { showExitConfirm = false }) { Text("取消") }
                    }
                }
            )
        }

        // 终端面板
        if (showTerminal) {
            Box(
                Modifier.fillMaxSize().background(Color.Black)
                    .clickable(interactionSource = noRipple, indication = null) {}
                    .imePadding()
            ) {
                Box(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(termScroll).padding(horizontal = 10.dp, vertical = 8.dp)
                        .clickable(interactionSource = noRipple, indication = null) {
                            termFocus.requestFocus()
                            val act = ctx as? android.app.Activity
                            val focusView = act?.currentFocus
                            if (focusView != null) termImm.showSoftInput(focusView, 0)
                        }
                ) {
                    Column {
                        if (termOutput.isNotEmpty()) {
                            Text(termOutput, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp, color = Color(0xFFE5E5E5))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$ ", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF4ADE80))
                            BasicTextField(
                                value = termInput,
                                onValueChange = { termInput = it },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFE5E5E5)),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4ADE80)),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submitCmd() }),
                                modifier = Modifier.weight(1f).focusRequester(termFocus)
                            )
                        }
                    }
                }
                LaunchedEffect(termOutput, termInput) { termScroll.animateScrollTo(termScroll.maxValue) }
                LaunchedEffect(showTerminal) {
                    if (showTerminal) {
                        // 先清焦点再申请终端焦点：避免物理键盘输入仍进入主编辑框（表现为"终端不让输入"）
                        focusManager.clearFocus()
                        termFocus.requestFocus()
                        kotlinx.coroutines.delay(200)
                        (ctx as? android.app.Activity)?.currentFocus?.let { termImm.showSoftInput(it, 0) }
                    }
                }
            }
        }
    }
}

private fun countOccurrences(text: String, query: String): Int {
    var c = 0; var p = 0
    while (true) {
        val at = text.indexOf(query, p)
        if (at < 0) break
        c++; p = at + query.length
    }
    return c
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.highlightOccurrences(str: String, query: String, color: Color) {
    var pos = 0
    while (true) {
        val at = str.indexOf(query, pos)
        if (at < 0) break
        addStyle(SpanStyle(background = color), at, at + query.length)
        pos = at + query.length
    }
}

private val keywordMap = mapOf(
    "lua" to setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto",
        "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true",
        "until", "while"
    ),
    "kt" to setOf(
        "package", "import", "fun", "val", "var", "const", "class", "object", "interface", "data", "sealed", "open", "abstract",
        "private", "protected", "public", "internal", "override", "lateinit", "init", "constructor", "super", "this", "return",
        "if", "else", "when", "while", "for", "do", "break", "continue", "throw", "try", "catch", "finally", "true", "false", "null", "in", "is", "as", "by", "where", "out", "infix", "operator", "inline", "crossinline", "noinline", "reified", "suspend", "yield"
    ),
    "java" to setOf(
        "package", "import", "public", "private", "protected", "static", "final", "abstract", "class", "interface", "extends",
        "implements", "return", "void", "if", "else", "while", "for", "do", "break", "continue", "switch", "case", "default",
        "try", "catch", "finally", "throw", "new", "this", "super", "true", "false", "null", "instanceof", "synchronized", "volatile", "transient", "native", "strictfp", "const", "goto"
    ),
    "js" to setOf(
        "import", "export", "from", "default", "function", "return", "const", "let", "var", "class", "extends", "super", "this",
        "if", "else", "while", "for", "do", "break", "continue", "switch", "case", "default", "try", "catch", "finally", "throw",
        "new", "typeof", "instanceof", "true", "false", "null", "undefined", "async", "await", "yield"
    ),
    "ts" to setOf(
        "import", "export", "from", "default", "function", "return", "const", "let", "var", "class", "extends", "super", "this",
        "if", "else", "while", "for", "do", "break", "continue", "switch", "case", "default", "try", "catch", "finally", "throw",
        "new", "typeof", "instanceof", "true", "false", "null", "undefined", "async", "await", "yield", "interface", "type", "enum", "namespace", "declare", "readonly", "public", "private", "protected"
    ),
    "py" to setOf(
        "import", "from", "as", "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue", "pass",
        "try", "except", "finally", "raise", "with", "lambda", "yield", "True", "False", "None", "and", "or", "not", "in", "is", "global", "nonlocal", "async", "await"
    ),
    "go" to setOf(
        "package", "import", "func", "return", "var", "const", "type", "struct", "interface", "map", "chan", "go", "defer",
        "if", "else", "for", "range", "break", "continue", "switch", "case", "default", "fallthrough", "select", "true", "false", "nil"
    ),
    "html" to setOf(),
    "css" to setOf(),
    "json" to setOf(),
    "xml" to setOf()
)

private val numberRegex = Regex("(?<![\\w.])(-?\\d+\\.?\\d*|[\\.]\\d+)(?![\\w.])")
private val singleLineCommentRegex = Regex("(//|#).*(?=\n|$)")
private val multiLineCommentRegex = Regex("/\\*[\\s\\S]*?\\*/")

private fun androidx.compose.ui.text.AnnotatedString.Builder.applySyntaxHighlight(text: String, ext: String) {
    val keywords = keywordMap[ext] ?: emptySet()
    if (keywords.isNotEmpty()) {
        val wordRegex = Regex("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
        wordRegex.findAll(text).forEach { m ->
            if (keywords.contains(m.value)) {
                addStyle(SpanStyle(color = Color(0xFF7C3AED)), m.range.first, m.range.last + 1)
            }
        }
    }
    // 数字
    numberRegex.findAll(text).forEach { m ->
        addStyle(SpanStyle(color = Color(0xFFEA580C)), m.range.first, m.range.last + 1)
    }
    // 字符串（简单处理，避免跨行复杂解析）
    val stringRegex = Regex("\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"|'[^'\\\\]*(\\\\.[^'\\\\]*)*'")
    stringRegex.findAll(text).forEach { m ->
        addStyle(SpanStyle(color = Color(0xFF16A34A)), m.range.first, m.range.last + 1)
    }
    // 注释
    if (ext in setOf("kt", "java", "js", "ts", "go", "c", "cpp", "h", "hpp", "rs")) {
        singleLineCommentRegex.findAll(text).forEach { m ->
            addStyle(SpanStyle(color = Color(0xFF6B7280)), m.range.first, m.range.last + 1)
        }
        multiLineCommentRegex.findAll(text).forEach { m ->
            addStyle(SpanStyle(color = Color(0xFF6B7280)), m.range.first, m.range.last + 1)
        }
    } else if (ext == "lua") {
        Regex("--.*(?=\n|$)").findAll(text).forEach { m ->
            addStyle(SpanStyle(color = Color(0xFF6B7280)), m.range.first, m.range.last + 1)
        }
        Regex("""--\[\[[\s\S]*?\]\]""").findAll(text).forEach { m ->
            addStyle(SpanStyle(color = Color(0xFF6B7280)), m.range.first, m.range.last + 1)
        }
    } else if (ext == "py" || ext == "sh" || ext == "yaml" || ext == "yml") {
        val pyComment = Regex("#.*(?=\n|$)")
        pyComment.findAll(text).forEach { m ->
            addStyle(SpanStyle(color = Color(0xFF6B7280)), m.range.first, m.range.last + 1)
        }
    }
}
