package com.aurora.chat.ui.components
import com.aurora.chat.ui.theme.LocalAppColors
import com.aurora.chat.ui.theme.ActiveAppColors

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.util.Log
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.ui.chat.AiChatManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.AlertDialog

private val CeBlue = Color(0xFF2563EB)
private val CePurple = Color(0xFF7C3AED)
private val CeRed = Color(0xFFDC2626)
private val CeGreen = Color(0xFF10B981)

private const val UNDO_STACK_MAX = 50
private const val UNDO_TEXT_MAX_LEN = 100_000
/**
 * 撤销栈总字符预算：只按"条数"封顶会导致 90K 文件缓存 50 份完整副本（约 4.5MB 纯字符串，
 * 还没算 Compose 快照），低内存机型直接 OOM 闪退。这里再加一道总量封顶：
 * 小文件仍是 50 步撤销，大文件按预算自动缩短历史深度（内存恒定）。
 */
private const val UNDO_TOTAL_MAX_LEN = 1_000_000
private const val UNDO_GROUP_MS = 800L
/** 增量撤销单条变更上限：防极端粘贴（整块 500K+ 字符替换）撑爆撤销栈。 */
private const val UNDO_DELTA_MAX_LEN = 500_000
/** 大文件分界（对齐 VS Code 官方标准，issue#27100：5MB 起进入大文件模式，关闭性能敏感功能）。 */
private const val LARGE_FILE_LIMIT = 5_000_000
/** 大文件模式下撤销深度上限（功能降级：只保留最浅几步，防止全量快照式操作失控）。 */
private const val LARGE_FILE_UNDO_MAX = 5

/**
 * 增量编辑指令（VS Code 式撤销的移动端实现）：只记录「变更起点 + 被替换原文 + 替换后新文」，
 * 撤销 = 反向替换。内存与每次变更量成正比，不再为 50 步撤销各存一份整串快照。
 */
private data class EditDelta(val start: Int, val oldText: String, val newText: String)
/** 语法高亮重算防抖：打字时不在主线程同步全量重算，等停顿再异步刷新（企业编辑器标准做法）。 */
private const val HIGHLIGHT_DEBOUNCE_MS = 70L

/**
 * 编辑器横向滚动宽度上限：超过这个宽度说明文本里有超长行，
 * 此时不再继续撑宽（避免极端布局把内存/测量拖垮），改为按视口宽度换行显示。
 */
private val MAX_EDITOR_WIDTH = 6000.dp

/** 通用代码编辑器：支持行号、双指缩放、语法高亮、自动缩进、括号配对、选择高亮、
 *  搜索/替换、撤销/重做、跳转行、终端、Python 脚本运行、格式转换、精简、清空、键盘锁定、未保存提醒。
 *
 *  @param title            顶栏显示的文件名/标题
 *  @param subtitle         顶栏副标题
 *  @param initialContent   初始文本（加载完成后传入）
 *  @param isLoading        是否处于加载中
 *  @param onSave           保存回调；closeAfter=true 表示保存后关闭
 *  @param onDismiss        关闭回调（组件内部会先处理未保存提醒）
 *  @param readOnly         只读模式（不显示保存按钮）
 *  @param rootFile         运行脚本（.py）时关联的本地文件：有则按该文件运行（其所在目录即工作目录），
 *                          不传则直接运行编辑器内存内容（没有本地文件的场景）
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
    rootFile: java.io.File? = null
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 悬浮顶栏的实际高度（px）。这里必须是 Compose 状态而不是直接往 View 上写：
    // 编辑器 View 只在 isLoading=false 之后才创建，而顶栏的 onSizeChanged 早在加载中
    // 就已经触发过一次（那时 canvasEditor 还是 null），因此高度写入 View 的时序不可靠，
    // 表现为正文首行被顶栏永久盖住。改成「顶栏高度先落到状态里，再统一回灌给 View」。
    var topBarInsetPx by remember { mutableFloatStateOf(0f) }
    // 在组合阶段读取一次，顶栏高度变化时会重组，从而把新值同步进已存在的 View
    val topBarInset = topBarInsetPx
    val noRipple = remember { MutableInteractionSource() }

    // 文本状态：正文由 CanvasCodeEditor 持有（只画可视行），这里保留一份镜像给
    // 保存 / 搜索替换 / 撤销等逻辑使用。镜像是按需同步的，不是渲染来源。
    val editText = remember(initialContent) { mutableStateOf(TextFieldValue(initialContent)) }
    val canvasEditor = remember { mutableStateOf<CanvasCodeEditor?>(null) }

    // 保存快照与脏状态
    var savedSnapshot by remember(initialContent) { mutableStateOf(initialContent) }
    var justSaved by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    // 脏标记由编辑器回调直接置位，不再每次按键比较整串文本（O(文件大小)）
    var isDirty by remember(initialContent) { mutableStateOf(false) }

    // 撤销/重做（增量指令式，见 EditDelta：只存变更区间+文本，不再存整串快照）
    val undoStack = remember { mutableStateListOf<EditDelta>() }
    val redoStack = remember { mutableStateListOf<EditDelta>() }
    var lastUndoTs by remember { mutableLongStateOf(0L) }

    // 大文件性能模式（VS Code 标准，L#27100：≥5MB 进入大文件降级）：
    // 关闭语法高亮等全量重算类功能、撤销深度降为 5 步，仅保留基础编辑，防内存与主线程失控。
    val isLargeFile = remember(initialContent) { initialContent.length >= LARGE_FILE_LIMIT }

    // 视图状态
    val zoomLevel = remember { mutableFloatStateOf(1f) }
    // 行首索引：跳行/当前行用。改成按需构建 —— 以前每敲一个键就在后台全文重建一次（O(文件大小)）
    var lineStartsState by remember(initialContent) { mutableStateOf(IntArray(0)) }
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
    // 「修改中」实时编辑视图（复刻 CodeBuddy「修改」面板）开关
    var showLiveModify by remember { mutableStateOf(false) }

    // 代码折叠：记录被折叠的行区间[startLine, endLine]（0-based，含）
    val foldedRanges = remember { mutableStateListOf<Pair<Int, Int>>() }

    // 语法高亮扩展名
    val fileExt = remember(title) { title.substringAfterLast('.', "").lowercase() }

    // 行数/字数由编辑器回调上报（它在编辑时增量维护），不再每次按键扫全文
    var lineCount by remember(initialContent) {
        mutableIntStateOf(initialContent.count { it == '\n' } + 1)
    }
    var charCount by remember(initialContent) { mutableIntStateOf(initialContent.length) }

    /**
     * 最长行字符数：只在换文件/精简这类整体替换时用得到，正文宽度现在由
     * CanvasCodeEditor 自己增量维护，不再参与 Compose 布局。
     */
    var longestLineChars by remember(initialContent) { mutableStateOf(computeLongestLineChars(initialContent)) }

    // 工具函数
    fun toast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** 撤销栈双封顶：条数 50（大文件模式降为 5）且累计变更量（old+new 文本总长）不超过预算，内存恒定。 */
    fun trimUndoStack() {
        val maxSteps = if (isLargeFile) LARGE_FILE_UNDO_MAX else UNDO_STACK_MAX
        while (undoStack.size > maxSteps ||
            (undoStack.size > 1 && undoStack.sumOf { it.oldText.length + it.newText.length } > UNDO_TOTAL_MAX_LEN)
        ) {
            undoStack.removeAt(0)
        }
    }

    /** 计算 oldText→newText 的最小变更区间（共同前缀+后缀裁剪），无变化返回 null。 */
    fun diffDelta(oldText: String, newText: String): EditDelta? {
        if (oldText == newText) return null
        val ol = oldText.length
        val nl = newText.length
        var pre = 0
        val maxPre = minOf(ol, nl)
        while (pre < maxPre && oldText[pre] == newText[pre]) pre++
        var suf = 0
        while (suf < ol - pre && suf < nl - pre && oldText[ol - 1 - suf] == newText[nl - 1 - suf]) suf++
        return EditDelta(pre, oldText.substring(pre, ol - suf), newText.substring(pre, nl - suf))
    }

    /** 在文本上应用增量（undo 传反向 (start, newText, oldText)，redo 传正向）。 */
    fun applyDelta(text: String, d: EditDelta): String =
        text.replaceRange(d.start, d.start + d.oldText.length, d.newText)

    /**
     * 增量撤销入栈：记录「旧→新」的最小变更。800ms 内连续编辑若落在同一结果区域
     * （打字/退格/连续替换）则合并为上一步，undo 一次回到最初状态；光标跳到别处再改则另起一步。
     */
    fun pushUndo(oldText: String, newText: String) {
        val d = diffDelta(oldText, newText) ?: return
        if (d.newText.length > UNDO_DELTA_MAX_LEN) return
        val now = System.currentTimeMillis()
        val grouped = undoStack.isNotEmpty() && now - lastUndoTs < UNDO_GROUP_MS
        lastUndoTs = now
        if (grouped) {
            val last = undoStack.last()
            if (d.start >= last.start && d.start <= last.start + last.newText.length) {
                undoStack[undoStack.lastIndex] = EditDelta(
                    last.start,
                    last.oldText,
                    applyDelta(last.newText, EditDelta(d.start - last.start, d.oldText, d.newText))
                )
                trimUndoStack()
                redoStack.clear()
                return
            }
        }
        undoStack.add(d)
        trimUndoStack()
        redoStack.clear()
    }

    fun doUndo() {
        if (undoStack.isEmpty()) return
        val d = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(d)
        val newText = applyDelta(editText.value.text, EditDelta(d.start, d.newText, d.oldText))
        val caret = d.start.coerceAtMost(newText.length)
        editText.value = TextFieldValue(newText, TextRange(caret))
        canvasEditor.value?.setTextIfDifferent(newText)
        canvasEditor.value?.setSelectionOffset(caret)
        isDirty = true
    }

    fun doRedo() {
        if (redoStack.isEmpty()) return
        val d = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(d)
        trimUndoStack()
        val newText = applyDelta(editText.value.text, EditDelta(d.start, d.oldText, d.newText))
        editText.value = TextFieldValue(newText, TextRange((d.start + d.newText.length).coerceAtMost(newText.length)))
        canvasEditor.value?.setTextIfDifferent(newText)
        canvasEditor.value?.setSelectionOffset((d.start + d.newText.length).coerceAtMost(newText.length))
        isDirty = true
    }

    fun save(closeAfter: Boolean) {
        if (isSaving || readOnly) return
        scope.launch {
            isSaving = true
            try {
                val content = canvasEditor.value?.peekText() ?: editText.value.text
                onSave(content, closeAfter)
                savedSnapshot = content
                isDirty = false
                justSaved = true
            } catch (e: Exception) {
                Log.e("CodeEditor", "保存失败", e)
                toast("保存失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                isSaving = false
            }
        }
    }

    /** 终端输出追加（统一截断上限，避免超长输出把界面拖垮）。 */
    fun termAppend(s: String) {
        termOutput += s
        if (termOutput.length > 500_000) termOutput = termOutput.takeLast(400_000)
    }




    fun jumpToMatch(query: String, idx: Int) {
        if (query.isEmpty()) return
        var p = 0
        var m = 0
        val text = editText.value.text
        while (true) {
            val at = text.indexOf(query, p)
            if (at < 0) break
            if (m == idx) {
                editText.value = editText.value.copy(selection = TextRange(at))
                canvasEditor.value?.selectRange(at, at + query.length)
                break
            }
            m++
            p = at + query.length
        }
    }

    // 行首索引兜底：缓存缺失或与当前文本行数不符时现场重建（正常路径 O(log n) 二分，兜底路径 O(n) 但极轻量）
    fun ensureLineStarts(text: String): IntArray {
        val ls = lineStartsState
        if (ls.isEmpty() || ls.size != text.count { it == '\n' } + 1) {
            val rebuilt = buildLineStarts(text)
            lineStartsState = rebuilt
            return rebuilt
        }
        return ls
    }

    // 光标自动滚动已经交给 CanvasCodeEditor 自己处理（它知道行高和视口），这里不再插手。

    fun gotoLine() {
        val line = gotoLineInput.toIntOrNull()
        if (line == null || line < 1) { toast("请输入有效行号"); return }
        val text = editText.value.text
        val ls = ensureLineStarts(text)
        // 目标行行首偏移：行号 → 索引 = 行号 - 1（O(log n) 二分定位，替代原逐字符扫描）
        val offset = if (line <= ls.size) ls[line - 1] else text.length
        val safe = offset.coerceAtMost(text.length)
        editText.value = editText.value.copy(selection = TextRange(safe))
        canvasEditor.value?.setSelectionOffset(safe)
        showGotoDialog = false
    }

    fun currentLineStart(text: String, cursor: Int): Int {
        val ls = ensureLineStarts(text)
        val c = cursor.coerceAtMost(text.length)
        var lo = 0
        var hi = ls.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (ls[mid] <= c) { ans = ls[mid]; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /**
     * Tab / Shift+Tab 缩进（企业编辑器基本能力）。
     *  · 无选区：插入 4 空格；Shift+Tab 删除当前行前导空格（最多 4 个）。
     *  · 有选区：对选区覆盖的每一行整体缩进 / 反缩进，选区同步平移。
     * 始终消费该按键，避免焦点被 Tab 抢走。
     */
    fun handleTab(outdent: Boolean) {
        val tv = editText.value
        val text = tv.text
        val sel = tv.selection
        if (sel.start == sel.end) {
            if (outdent) {
                val ls = currentLineStart(text, sel.start)
                var j = ls
                var removed = 0
                while (removed < 4 && j < text.length && text[j] == ' ') { j++; removed++ }
                if (removed > 0) {
                    val newText = text.substring(0, ls) + text.substring(j)
                    pushUndo(text, newText)
                    editText.value = tv.copy(text = newText, selection = TextRange((sel.start - removed).coerceAtLeast(ls)))
                }
            } else {
                val pad = "    "
                val newText = text.substring(0, sel.start) + pad + text.substring(sel.end)
                pushUndo(text, newText)
                editText.value = tv.copy(
                    text = newText,
                    selection = TextRange(sel.start + pad.length)
                )
            }
            return
        }
        val ls = currentLineStart(text, sel.start)
        val segEnd = sel.end.coerceAtMost(text.length)
        val lines = text.substring(ls, segEnd).lines()
        var totalDelta = 0
        val transformed = if (outdent) {
            lines.joinToString("\n") { ln ->
                var i = 0
                while (i < 4 && i < ln.length && ln[i] == ' ') i++
                totalDelta -= i
                ln.substring(i)
            }
        } else {
            totalDelta = lines.size * 4
            lines.joinToString("\n") { "    $it" }
        }
        val newText = text.substring(0, ls) + transformed + text.substring(segEnd)
        val ns = (sel.start + totalDelta).coerceAtLeast(ls)
        val ne = (sel.end + totalDelta).coerceAtLeast(ns)
        pushUndo(text, newText)
        editText.value = tv.copy(text = newText, selection = TextRange(ns, ne))
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
            val processed = processInput(editText.value, newValue)
            pushUndo(editText.value.text, processed.text)
            editText.value = processed
            justSaved = false
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

    // 搜索/替换匹配数（后台线程计算，打字/改词不卡主线程；计数滞后一帧无感）
    var searchMatchCount by remember(searchQuery) { mutableIntStateOf(0) }
    LaunchedEffect(searchQuery, editText.value.text) {
        searchMatchCount = if (searchQuery.isEmpty()) 0
        else withContext(Dispatchers.Default) { countOccurrences(editText.value.text, searchQuery) }
    }
    var replaceMatchCount by remember(replaceFind) { mutableIntStateOf(0) }
    LaunchedEffect(replaceFind, editText.value.text) {
        replaceMatchCount = if (replaceFind.isEmpty()) 0
        else withContext(Dispatchers.Default) { countOccurrences(editText.value.text, replaceFind) }
    }

    // 语法高亮已经搬进 CanvasCodeEditor：按行缓存 + 编辑时只重扫改动的那几行。
    // 这里不再为整篇文档构建 AnnotatedString，也没有 VisualTransformation 的全文重算——
    // 那套东西每次布局都要跑一遍，是大文件卡顿的另一个源头。

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
                                    val ln = line
                                    scope.launch(Dispatchers.Main) { termAppend(ln + "\n") }
                                }
                            }
                            withContext(Dispatchers.Main) { termAppend("[shell 已退出]\n") }
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
        Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())
            .clickable(interactionSource = noRipple, indication = null) {}
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            // 顶栏已提升为悬浮层（见下方 Box 的 overlay，绘制在最上层，避免被编辑器越界覆盖）

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
                    Column(Modifier.fillMaxSize()) {
                        // 大文件性能模式横幅（VS Code 5MB 标准降级提示）
                        if (isLargeFile) {
                            Row(
                                Modifier.fillMaxWidth().background(Color(0xFFFFF3CD)).padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("大文件模式（≥5MB）：已关闭语法高亮，撤销深度降为 $LARGE_FILE_UNDO_MAX 步", fontSize = 11.sp, color = Color(0xFF92600A))
                            }
                        }
                        // 正文：Canvas 自绘编辑器（只渲染可视行，成本与文件长度无关）
                        AndroidView(
                            factory = { c ->
                                CanvasCodeEditor(c).apply {
                                    this.ext = fileExt
                                    this.fontScale = zoomLevel.floatValue
                                    this.readOnly = readOnly || !keyboardVisible || showTerminal
                                    this.topInsetPx = topBarInset
                                    placeholder = "在此输入文件内容..."
                                    setTextIfDifferent(editText.value.text)
                                    onTextEdited = { t, caret ->
                                        editText.value = TextFieldValue(t, TextRange(caret.coerceIn(0, t.length)))
                                        isDirty = t != savedSnapshot
                                        justSaved = false
                                    }
                                    onStats = { lines, chars ->
                                        lineCount = lines
                                        charCount = chars
                                    }
                                    canvasEditor.value = this
                                }
                            },
                            update = { v ->
                                v.ext = fileExt
                                v.fontScale = zoomLevel.floatValue
                                v.readOnly = readOnly || !keyboardVisible || showTerminal
                                v.topInsetPx = topBarInset
                                v.setTextIfDifferent(editText.value.text)
                                canvasEditor.value = v
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // 底部工具栏
            if (!isLoading) {
                Column(
                    Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface()).navigationBarsPadding().padding(top = 4.dp, bottom = 12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appCardBg()).padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("行: $lineCount", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                        Spacer(Modifier.width(16.dp))
                        Text("字: $charCount", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        Spacer(Modifier.width(16.dp))
                        Text("UTF-8", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        if (isDirty) {
                            Spacer(Modifier.width(16.dp))
                            Text("未保存", fontSize = 11.sp, color = CeRed, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface()).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EditorTool(Icons.Filled.Search, "搜索", CeBlue) { showSearchDialog = true }
                        EditorTool(Icons.Filled.FindReplace, "替换", CeBlue) { showReplaceDialog = true }
                        EditorTool(Icons.Filled.Undo, "撤销", CeBlue, undoStack.isNotEmpty()) { doUndo() }
                        EditorTool(Icons.Filled.Redo, "重做", CeBlue, redoStack.isNotEmpty()) { doRedo() }
                        EditorTool(Icons.Filled.Save, "保存", if (justSaved && !isDirty) CeGreen else CeBlue) { save(false) }
                        EditorTool(Icons.Filled.Terminal, "终端", com.aurora.chat.ui.theme.appTextSecondary()) { showTerminal = true }
                        EditorTool(Icons.Filled.ContentCopy, "复制全部", CeBlue) {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("text", editText.value.text))
                            toast("已复制全部内容")
                        }
                        EditorTool(Icons.Filled.FormatListNumbered, "跳转行", CePurple) { gotoLineInput = ""; showGotoDialog = true }
                        EditorTool(Icons.Filled.Compress, "精简", CePurple) {
                            val oldT = editText.value.text
                            val minified = oldT.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                            pushUndo(oldT, minified)
                            editText.value = TextFieldValue(minified)
                            toast("已精简")
                        }
                        EditorTool(Icons.Filled.UnfoldLess, "折叠", CePurple) {
                            val sel = editText.value.selection.start.coerceIn(0, editText.value.text.length)
                            val line = editText.value.text.substring(0, sel).count { it == '\n' }
                            toggleFold(line)
                        }
                        EditorTool(Icons.Filled.SwapHoriz, "转换", CePurple) { showConvertDialog = true }
                        EditorTool(Icons.Filled.DeleteSweep, "清空", CeRed, editText.value.text.isNotEmpty()) { showClearConfirm = true }
                        EditorTool(
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
                        EditorTool(Icons.Filled.Edit, "修改预览", CePurple) { showLiveModify = true }
                    }
                }
            }
        }

        // 顶栏悬浮层：绘制顺序在编辑区之后（最上层），自带白色背景，
        // 即使自定义编辑器在输入法弹出重测量时越界，也永远盖不住标题与保存按钮。
        // 外层只负责状态栏留白 + 白底；内层内容 Row 用 onSizeChanged 量到"顶栏本身高度"，
        // 回传给编辑器当顶部安全区，正文据此避让（不会被顶栏遮住首行）。
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .statusBarsPadding()
                .background(com.aurora.chat.ui.theme.appSurface()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onSizeChanged { topBarInsetPx = it.height.toFloat() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(interactionSource = noRipple, indication = null) {
                            if (isDirty && !readOnly) showExitConfirm = true else onDismiss()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = com.aurora.chat.ui.theme.appTextPrimary())
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle.ifBlank { "代码编辑器" }, fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
                if (!readOnly) {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (isSaving) Color(0xFF93C5FD) else if (justSaved && !isDirty) CeGreen else CeBlue)
                            .clickable(enabled = !isSaving, interactionSource = noRipple, indication = null) { save(false) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isSaving) "保存中..." else if (justSaved && !isDirty) "已保存" else "保存", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        // 修改中实时编辑视图（复刻 CodeBuddy「修改」面板）：作为 Compose 覆盖层叠在编辑器之上
        if (showLiveModify) {
            LiveEditPanel(
                content = editText.value.text,
                fileName = title,
                onClose = { showLiveModify = false }
            )
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
                                Text("找到 $matchCount 处（当前第 ${searchIdx + 1} 处）", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
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
                                Text("找到 $replaceMatchCount 处（当前第 ${replaceIdx + 1} 处）", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
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
                                        val oldT = editText.value.text
                                        val newT = oldT.substring(0, at) + r + oldT.substring(at + f.length)
                                        pushUndo(oldT, newT)
                                        editText.value = TextFieldValue(newT)
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
                            val oldT = editText.value.text
                            val newT = oldT.replace(replaceFind, replaceWith)
                            pushUndo(oldT, newT)
                            editText.value = TextFieldValue(newT)
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
                        Text("常用转换: 大写→小写 小写→大写 驼峰→下划线 反转", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
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
                            isConvert(from, to, "驼峰", "camel", "下划线", "snake") -> camelToSnake(text)
                            isConvert(from, to, "下划线", "snake", "驼峰", "camel") -> snakeToCamel(text)
                            (to == "反" || to == "reverse" || to == "反转") -> text.reversed()
                            else -> text
                        }
                        pushUndo(text, result)
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
                        Text("当前共 $lineCount 行，输入要跳转的行号(1~$lineCount):", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
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
                        val oldT = editText.value.text
                        pushUndo(oldT, "")
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

// ==================== 编辑器工具栏按钮（顶层 Composable，避免内嵌重组反模式） ====================
@Composable
private fun EditorTool(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val noRipple = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, interactionSource = noRipple, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
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

// ==================== 格式转换 ====================

/** 判断「从→到」是否匹配某一组中文/英文别名（如 驼峰/camel → 下划线/snake）。 */
private fun isConvert(from: String, to: String, fa: String, fb: String, ta: String, tb: String): Boolean =
    (from == fa || from == fb) && (to == ta || to == tb)

/** lowerCamel / UpperCamel → snake_case（连续大写整体归并，如 HTTPServer → http_server）。 */
private fun camelToSnake(s: String): String {
    val sb = StringBuilder()
    s.forEachIndexed { i, c ->
        when {
            c.isUpperCase() -> {
                val prev = s.getOrNull(i - 1)
                val next = s.getOrNull(i + 1)
                if (prev != null && (prev.isLowerCase() || (prev.isUpperCase() && next?.isLowerCase() == true))) {
                    sb.append('_')
                }
                sb.append(c.lowercase())
            }
            else -> sb.append(c)
        }
    }
    return sb.toString().replace(Regex("_+"), "_").lowercase().trim('_')
}

/** snake_case → lowerCamel（首段全小写，后续每段首字母大写）。 */
private fun snakeToCamel(s: String): String =
    s.split('_').filter { it.isNotEmpty() }
        .mapIndexed { i, part -> if (i == 0) part.lowercase() else part.lowercase().replaceFirstChar { it.uppercase() } }
        .joinToString("")

// ==================== 高亮 / 布局计算的纯函数（可移到后台线程执行） ====================
// 语法高亮改为「按行缓存 + 增量重扫」（对齐 VS Code TextMate 增量 tokenize 的移动端落地）：
// 每行只存自己的 token 区间与行末块注释状态；文本变化时 diff 出最小变更区间，
// 只重扫「变更首行 → 状态收敛行」，其后整段复用旧缓存，不再全量重扫。
// 打字时在 Dispatchers.Default 后台防抖执行，主线程只承接 AnnotatedString 组装。

private const val STYLE_KEYWORD = 0
private const val STYLE_NUMBER = 1
private const val STYLE_STRING = 2
private const val STYLE_COMMENT = 3

/** 单行高亮缓存：spans 为 [relStart, relEnd, styleId, ...] 扁平数组（行内偏移），inBlockAfter 为行结束时的块注释状态。 */
private class LineHL(val spans: IntArray, val inBlockAfter: Boolean)

/** 构建全文行首偏移索引（O(行数) 极轻量，行号/跳行/当前行/光标滚动共用）。 */
private fun buildLineStarts(s: String): IntArray {
    val starts = ArrayList<Int>(s.length / 32 + 1)
    starts.add(0)
    for (i in s.indices) if (s[i] == '\n') starts.add(i + 1)
    return starts.toIntArray()
}

/** 单行 tokenize：扫描 text[start, end)（不含换行），携带进入时的块注释状态，产出该行 spans 与结束状态。 */
private fun tokenizeLineRange(text: String, start: Int, end: Int, ext: String, inBlockComment: Boolean): LineHL {
    val keywords = keywordMap[ext] ?: emptySet()
    val comment = commentSyntaxFor(ext)
    val firstChars = if (keywords.isEmpty()) null else keywordFirstChars(ext, keywords)
    val n = end
    val list = ArrayList<Int>(24)
    var i = start
    var inBlock = inBlockComment
    val bOpen = comment?.blockOpen
    val bClose = comment?.blockClose

    // 跨行块注释：上一行遗留的注释状态，先在本行找结束标记
    if (inBlock && bClose != null) {
        val cend = text.indexOf(bClose, start)
        if (cend < 0 || cend >= end) {
            list.add(0); list.add(end - start); list.add(STYLE_COMMENT)
            return LineHL(list.toIntArray(), true)
        }
        list.add(0); list.add(cend + bClose.length - start); list.add(STYLE_COMMENT)
        i = cend + bClose.length
        inBlock = false
    }

    while (i < n) {
        val c = text[i]

        // 块注释
        if (!inBlock && bOpen != null && text.startsWith(bOpen, i)) {
            val cend = text.indexOf(bClose!!, i + bOpen.length)
            if (cend < 0 || cend >= end) {
                list.add(i - start); list.add(end - start); list.add(STYLE_COMMENT)
                i = n
                inBlock = true
                continue
            }
            list.add(i - start); list.add(cend + bClose.length - start); list.add(STYLE_COMMENT)
            i = cend + bClose.length
            continue
        }

        // 行注释
        val lineMark = comment?.line
        if (lineMark != null && text.startsWith(lineMark, i)) {
            list.add(i - start); list.add(end - start); list.add(STYLE_COMMENT)
            i = n
            continue
        }

        // 字符串（反斜杠转义；行内未闭合则到行尾，与旧全文实现"遇换行视为未闭合"一致）
        if (c == '"' || c == '\'') {
            var j = i + 1
            while (j < n) {
                val d = text[j]
                if (d == '\\') { j += 2; continue }
                if (d == c) { j++; break }
                j++
            }
            val stop = if (j > n) n else j
            list.add(i - start); list.add(stop - start); list.add(STYLE_STRING)
            i = stop
            continue
        }

        // 数字
        if (c.isDigit() || c == '.' || (c == '-' && i + 1 < n && text[i + 1].isDigit())) {
            val prev = if (i > start) text[i - 1] else ' '
            if (!(prev.isLetterOrDigit() || prev == '_' || prev == '.')) {
                var j = i
                if (text[j] == '-') j++
                while (j < n && text[j].isDigit()) j++
                if (j < n && text[j] == '.') { j++; while (j < n && text[j].isDigit()) j++ }
                val next = if (j < n) text[j] else ' '
                if (j > i && !(next.isLetterOrDigit() || next == '_' || next == '.')) {
                    list.add(i - start); list.add(j - start); list.add(STYLE_NUMBER)
                    i = j
                    continue
                }
            }
        }

        // 标识符 / 关键字
        if (c == '_' || c.isLetter()) {
            var j = i + 1
            while (j < n) {
                val d = text[j]
                if (d == '_' || d.isLetterOrDigit()) j++ else break
            }
            if (firstChars != null && firstChars.contains(c)) {
                val word = text.substring(i, j)
                if (keywords.contains(word)) { list.add(i - start); list.add(j - start); list.add(STYLE_KEYWORD) }
            }
            i = j
            continue
        }

        i++
    }
    return LineHL(list.toIntArray(), inBlock)
}

/** 全量构建行缓存（首开 / 切换语言时兜底）。 */
private fun buildLineCache(text: String, ext: String): ArrayList<LineHL> {
    val cache = ArrayList<LineHL>(text.length / 32 + 1)
    var inBlock = false
    var lineStart = 0
    for (i in text.indices) {
        if (text[i] == '\n') {
            val hl = tokenizeLineRange(text, lineStart, i, ext, inBlock)
            cache.add(hl)
            inBlock = hl.inBlockAfter
            lineStart = i + 1
        }
    }
    cache.add(tokenizeLineRange(text, lineStart, text.length, ext, inBlock))
    return cache
}

/**
 * 增量更新行缓存：diff 出最小变更区间，只重扫「变更首行 → 状态收敛行」，其后整段平移复用旧缓存。
 * 收敛判据 = 该行行首落在未变后缀区、且行边界与旧文本对齐（行内容必然相同，tokenize 结果可整体复用）。
 */
private fun updateLineCache(cache: ArrayList<LineHL>, oldText: String, newText: String, ext: String) {
    val oldStarts = buildLineStarts(oldText)
    val newStarts = buildLineStarts(newText)
    val oldCount = oldStarts.size
    val newCount = newStarts.size
    val delta = newCount - oldCount
    val ol = oldText.length
    val nl = newText.length
    var pre = 0
    val maxPre = minOf(ol, nl)
    while (pre < maxPre && oldText[pre] == newText[pre]) pre++
    var suf = 0
    while (suf < ol - pre && suf < nl - pre && oldText[ol - 1 - suf] == newText[nl - 1 - suf]) suf++
    val suffixStart = nl - suf

    // 变更首行（new 侧）：最后一个行首 <= pre 的行
    var firstLine = 0
    var lo = 0
    var hi = newCount - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (newStarts[mid] <= pre) { firstLine = mid; lo = mid + 1 } else hi = mid - 1
    }

    val oldSnapshot = ArrayList(cache)
    var i = firstLine
    var state = if (i > 0 && i - 1 < oldSnapshot.size) oldSnapshot[i - 1].inBlockAfter else false
    while (i < newCount) {
        val ls = newStarts[i]
        val le = if (i + 1 < newCount) newStarts[i + 1] else nl
        val oldIdx = i - delta
        // 收敛：未变后缀区内行边界对齐 → 整行与旧文本相同，其后可复用
        if (ls >= suffixStart && oldIdx >= 0 && oldIdx < oldCount && oldStarts[oldIdx] == ls) break
        val hl = tokenizeLineRange(newText, ls, le, ext, state)
        if (i < cache.size) cache[i] = hl else cache.add(hl)
        state = hl.inBlockAfter
        i++
    }
    // 行数收尾 + 收敛点起平移复用
    while (cache.size < newCount) cache.add(oldSnapshot[oldSnapshot.size - 1])
    if (cache.size > newCount) while (cache.size > newCount) cache.removeAt(cache.size - 1)
    for (k in i until newCount) {
        val src = k - delta
        cache[k] = if (src in 0 until oldCount) oldSnapshot[src] else oldSnapshot[oldSnapshot.size - 1]
    }
}

private fun styleFor(id: Int): SpanStyle = when (id) {
    STYLE_KEYWORD -> SpanStyle(color = COLOR_KEYWORD)
    STYLE_NUMBER -> SpanStyle(color = COLOR_NUMBER)
    STYLE_STRING -> SpanStyle(color = COLOR_STRING)
    else -> SpanStyle(color = COLOR_COMMENT)
}

/**
 * 从行缓存重建带语法高亮的 AnnotatedString，再叠加搜索/替换/选中词高亮。
 * append(text) 为 O(n) 文本拷贝（AnnotatedString 不可变，无法避免），
 * 语法 tokenize 已按行增量完成，此处只做 span 复制，远快于全量解析。
 */
private fun buildAnnotatedFromCache(
    text: String,
    lineStarts: IntArray,
    cache: ArrayList<LineHL>,
    searchQuery: String,
    replaceFind: String,
    replaceIdx: Int,
    showReplaceDialog: Boolean,
    selectedWord: String?
): AnnotatedString = buildAnnotatedString {
    append(text)
    for (row in cache.indices) {
        val base = lineStarts[row]
        val spans = cache[row].spans
        var p = 0
        while (p < spans.size) {
            addStyle(styleFor(spans[p + 2]), base + spans[p], base + spans[p + 1])
            p += 3
        }
    }
    if (searchQuery.isNotEmpty()) {
        highlightOccurrences(text, searchQuery, Color(0x3380BFFF))
    }
    if (replaceFind.isNotEmpty() && showReplaceDialog) {
        var pos = 0
        var matchIdx = 0
        while (true) {
            val at = text.indexOf(replaceFind, pos)
            if (at < 0) break
            val color = if (matchIdx == replaceIdx) Color(0x66FFB800) else Color(0x3380BFFF)
            addStyle(SpanStyle(background = color), at, at + replaceFind.length)
            matchIdx++
            pos = at + replaceFind.length
        }
    }
    selectedWord?.let { w ->
        if (w.isNotBlank() && w != searchQuery) {
            highlightOccurrences(text, w, Color(0x33A78BFA))
        }
    }
}

/** 估算最长行字符数（仅扫前 300K 字符，足够判断布局宽度并夹上限）。纯函数，可在后台执行。 */
private fun computeLongestLineChars(s: String): Int {
    val n = if (s.length <= 300_000) s.length else 300_000
    var max = 0
    var cur = 0
    var i = 0
    while (i < n) {
        if (s[i] == '\n') {
            if (cur > max) max = cur
            cur = 0
        } else {
            cur++
        }
        i++
    }
    if (cur > max) max = cur
    return max
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

// ==================== 语法高亮（单次线性扫描） ====================
// 【性能】旧实现是 4~5 个全文正则各扫一遍、逐个 addStyle，并且 Regex 在函数体内每次调用重新编译；
// 而高亮会在每次文本变化时被触发一次，大文件（90K+）代价被成倍放大。
// 现在改为：规则常量提到顶层 + 一次 while 循环线性扫描产出全部 span（着色种类与旧版一致，
// 另外注释/字符串内部不再被误着色为关键字或数字，观感更正确）。

private val COLOR_KEYWORD = Color(0xFF7C3AED)
private val COLOR_NUMBER = Color(0xFFEA580C)
private val COLOR_STRING = Color(0xFF16A34A)
private val COLOR_COMMENT get() = ActiveAppColors.textSecondary

/** 各语言的注释规则：行注释起始符 + 块注释起止符 */
private class CommentSyntax(
    val line: String?,
    val blockOpen: String? = null,
    val blockClose: String? = null
)

private fun commentSyntaxFor(ext: String): CommentSyntax? = when (ext) {
    "kt", "java", "js", "ts", "go", "c", "cpp", "h", "hpp", "rs" -> CommentSyntax("//", "/*", "*/")
    "lua" -> CommentSyntax("--", "--[[", "]]")
    "py", "sh", "yaml", "yml" -> CommentSyntax("#")
    else -> null
}

/** 关键字首字符集合：绝大多数标识符首字符不在集合内，可直接跳过 substring 分配 */
private val keywordFirstCharsCache = java.util.concurrent.ConcurrentHashMap<String, Set<Char>>()

private fun keywordFirstChars(ext: String, keywords: Set<String>): Set<Char> =
    keywordFirstCharsCache.getOrPut(ext) { keywords.mapTo(HashSet()) { it.first() } }

// 旧全文线性扫描版 applySyntaxHighlight 已由 tokenizeLineRange（按行缓存 + 增量重扫）取代。
