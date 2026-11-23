package com.aurora.chat.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 工作区代码编辑器的正文渲染核心：Canvas 自绘，只画可视行。
 *
 * 为什么不用 BasicTextField：一个 TextField 会把**整篇文档**排成一个 Paragraph，
 * 每敲一个字符都要重新布局整段，代价与文件行数成正比 —— 大文件下打字必然一卡一卡。
 * 这里改成「行数组 + 视口遍历绘制」，每帧代价只跟屏幕能放下多少行有关（约 30 行），
 * 与文件多长无关。
 *
 * 性能要点（改之前务必先读）：
 * - 绘制：`onDraw` 只遍历 [firstVisible, lastVisible)，不碰视口外的行；
 * - 高亮：按行缓存 IntArray 三元组（start, end, style），编辑时只重扫改动的那几行；
 * - 文本：`ArrayList<String>` 行数组，插入/删除只 splice 相关行，不做全文重建；
 * - 状态：`text()` 结果按需缓存（[cachedText]），不会每帧 join 全文。
 */
class CanvasCodeEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ───────────────────────── 对外接口 ─────────────────────────

    /** 文本变化回调（新内容 + 光标绝对偏移）。 */
    var onTextEdited: ((text: String, caret: Int) -> Unit)? = null

    /** 行数/字数变化（状态栏用）。 */
    var onStats: ((lines: Int, chars: Int) -> Unit)? = null

    /** 光标行列变化（0-based 行，0-based 列）。 */
    var onCaretMoved: ((line: Int, col: Int) -> Unit)? = null

    /** 语言扩展名（kt/py/js…），换语言整篇重扫。 */
    var ext: String = ""
        set(value) {
            if (field == value) return
            field = value
            rehighlightAll()
            invalidate()
        }

    /** 字号倍率（和原来的双指缩放语义一致）。 */
    var fontScale: Float = 1f
        set(value) {
            val s = value.coerceIn(0.5f, 3f)
            if (s == field) return
            field = s
            applyTextSize()
            invalidate()
        }

    var readOnly: Boolean = false
        set(value) {
            field = value
            if (value) clearSelection()
        }

    /** 空文档时的占位提示。 */
    var placeholder: String = ""

    /**
     * 顶部安全区（被悬浮顶栏覆盖的高度，px）。正文绘制与滚动都避开它，
     * 避免自定义 View 在输入法弹出重测量时越界盖住顶栏、又把首行藏到顶栏下面。
     */
    var topInsetPx: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            // 安全区参与可滚动高度计算，改了就重算边界，否则滚到底会差一截
            applyContentSize()
            invalidate()
        }

    /** 外部整体换文（打开文件 / 撤销重做）。内容相同则什么都不做，避免打断光标。 */
    fun setTextIfDifferent(text: String) {
        if (text == cachedText) return
        val oldCaretLine = caretLine
        val oldCaretCol = caretCol
        lines.clear()
        splitToLines(text, lines)
        cachedText = null
        charCount = text.length
        highlightFrom = 0
        highlightRemoved = 0
        highlightInserted = lines.size
        rehighlight()
        select(-1, -1)
        caretLine = oldCaretLine.coerceIn(0, lines.size - 1)
        caretCol = oldCaretCol.coerceIn(0, lines[caretLine].length)
        recomputeBounds()
        scrollCaretIntoView()
        notifyStats()
        invalidate()
    }

    /** 当前全文（带缓存，连续调用不会重复 join）。 */
    fun peekText(): String {
        cachedText?.let { return it }
        val sb = StringBuilder(charCount + lines.size)
        for (i in lines.indices) {
            if (i > 0) sb.append('\n')
            sb.append(lines[i])
        }
        return sb.toString().also { cachedText = it }
    }

    /** 光标所在行（0-based）。 */
    fun caretLineIndex(): Int = caretLine

    /** 把光标移到绝对偏移（搜索跳转用）。 */
    fun setSelectionOffset(offset: Int) {
        var left = offset.coerceIn(0, peekText().length)
        var line = 0
        while (line < lines.size) {
            val len = lines[line].length + 1
            if (left <= len) break
            left -= len
            line++
        }
        caretLine = line.coerceIn(0, lines.size - 1)
        caretCol = left.coerceIn(0, lines[caretLine].length)
        select(-1, -1)
        scrollCaretIntoView()
        invalidate()
    }

    /** 选中 [start, end) 这个绝对区间（搜索命中高亮用）。 */
    fun selectRange(start: Int, end: Int) {
        val a = start.coerceIn(0, peekText().length)
        val b = end.coerceIn(a, peekText().length)
        val (l0, c0) = offsetToLineCol(a)
        val (l1, c1) = offsetToLineCol(b)
        caretLine = l1
        caretCol = c1
        selLine = l0
        selCol = c0
        scrollCaretIntoView()
        invalidate()
    }

    fun requestEditorFocus() {
        requestFocus()
        post { showKeyboard() }
    }

    // ───────────────────────── 文本模型 ─────────────────────────

    private val lines = ArrayList<String>()
    private var cachedText: String? = null
    private var charCount = 0

    private var caretLine = 0
    private var caretCol = 0

    /** 选区起点；-1 表示没有选区。 */
    private var selLine = -1
    private var selCol = -1

    private fun select(line: Int, col: Int) {
        selLine = line
        selCol = col
    }

    private val hasSelection: Boolean get() = selLine >= 0 && (selLine != caretLine || selCol != caretCol)

    // ───────────────────────── 绘制相关 ─────────────────────────

    private val density = resources.displayMetrics.density
    private val baseTextSize = 14f * density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = baseTextSize
    }
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = baseTextSize
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBFDBFE.toInt() }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt() }
    private val lineHlPaint = Paint().apply { color = 0xFFF6F8FA.toInt() }
    private val gutterBgPaint = Paint().apply { color = 0xFFF9FAFB.toInt() }
    private val gutterDividerPaint = Paint().apply { color = 0xFFEDF0F3.toInt() }

    private val textColors = intArrayOf(
        0xFF0B5FBF.toInt(), // keyword
        0xFFA31515.toInt(), // string
        0xFF098658.toInt(), // number
        0xFF2E7D32.toInt(), // comment
        0xFF267F99.toInt(), // type
        0xFF795E26.toInt()  // function
    )
    private val colorDefault = 0xFF1F2937.toInt()
    private val colorLineNumber = 0xFF9CA3AF.toInt()

    private var lineHeight = 0f
    private var charWidth = 0f
    private var numberWidth = 0f

    private val padTop = 12f * density
    private val padStart = 8f * density
    private val gutterGap = 10f * density

    private var scrollX = 0f
    private var scrollY = 0f
    private var maxScrollX = 0f
    private var maxScrollY = 0f

    /** 最宽那一行的宽度 / 行号，增量维护，别每次按键全量量。 */
    private var widest = 0f
    private var widestIdx = -1
    private var numberLineCount = -1

    private var caretOn = true
    private val caretBlink = object : Runnable {
        override fun run() {
            caretOn = !caretOn
            invalidate()
            postDelayed(this, 520)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        lines.add("")
        applyTextSize()
    }

    private fun applyTextSize() {
        textPaint.textSize = baseTextSize * fontScale
        gutterPaint.textSize = baseTextSize * fontScale
        val fm = textPaint.fontMetrics
        lineHeight = fm.descent - fm.ascent + fm.leading
        if (lineHeight <= 0f) lineHeight = textPaint.textSize * 1.4f
        charWidth = textPaint.measureText("M").let { if (it > 0f) it else textPaint.textSize * 0.6f }
        recomputeBounds()
    }

    private fun recomputeBounds() {
        numberWidth = gutterPaint.measureText(lines.size.toString())
        numberLineCount = lines.size
        widest = 0f
        widestIdx = -1
        for (i in lines.indices) {
            val w = textPaint.measureText(lines[i])
            if (w > widest) {
                widest = w
                widestIdx = i
            }
        }
        applyContentSize()
    }

    private fun applyContentSize() {
        val contentW = textOrigin() + widest + padStart
        maxScrollX = max(0f, contentW - width)
        maxScrollY = max(0f, padTop + topInsetPx + lines.size * lineHeight + padTop - height)
        scrollX = scrollX.coerceIn(0f, maxScrollX)
        scrollY = scrollY.coerceIn(0f, maxScrollY)
    }

    private fun gutterWidth(): Float = numberWidth + gutterGap

    private fun textOrigin(): Float = gutterWidth() + padStart

    // ───────────────────────── 语法高亮缓存 ─────────────────────────

    /** 每行一组三元组 [start, end, style]。 */
    private val hlRows = ArrayList<IntArray?>()

    /** 待重扫区间：从 [highlightFrom] 起，原来 [highlightRemoved] 行变成 [highlightInserted] 行。 */
    private var highlightFrom = 0
    private var highlightRemoved = 0
    private var highlightInserted = -1

    private fun rehighlightAll() {
        hlRows.clear()
        for (i in lines.indices) hlRows.add(tokenizeLine(i))
        highlightFrom = 0
        highlightRemoved = 0
        highlightInserted = -1
    }

    private fun rehighlight() {
        if (highlightInserted < 0) return
        if (hlRows.size + highlightInserted - highlightRemoved != lines.size) {
            rehighlightAll()
            return
        }
        if (highlightRemoved > 0) {
            val to = min(hlRows.size, highlightFrom + highlightRemoved)
            if (highlightFrom < to) hlRows.subList(highlightFrom, to).clear()
        }
        // 行数没变时用 set 覆盖，避免 ArrayList 每次插入都整段搬移
        val sameCount = highlightRemoved == highlightInserted
        for (k in 0 until highlightInserted) {
            val idx = highlightFrom + k
            if (idx >= lines.size) break
            val row = tokenizeLine(idx)
            if (sameCount && idx < hlRows.size) hlRows[idx] = row else hlRows.add(idx, row)
        }
        highlightInserted = -1
        highlightRemoved = 0
    }

    private fun splitToLines(text: String, out: MutableList<String>) {
        out.clear()
        var start = 0
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i] == '\n') {
                out.add(text.substring(start, i))
                start = i + 1
            }
            i++
        }
        out.add(text.substring(start))
    }

    private fun tokenizeLine(index: Int): IntArray? =
        LineTokens.tokenize(lines[index], LineTokens.keywordsFor(ext), ext)

    // ───────────────────────── 绘制 ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFFFFFFFF.toInt())

        val first = max(0, floor((scrollY - padTop - topInsetPx) / lineHeight).toInt())
        val visible = (height / lineHeight).toInt() + 2
        val last = min(lines.size, first + visible)
        val docTop = padTop + topInsetPx - scrollY

        // 当前行底纹（整屏宽，压在正文下面）
        if (caretLine in first until last) {
            val y = docTop + caretLine * lineHeight
            canvas.drawRect(0f, y, width.toFloat(), y + lineHeight, lineHlPaint)
        }

        val fm = textPaint.fontMetrics
        val baselineShift = -fm.ascent
        val gutterW = gutterWidth()

        // 正文区裁剪：横向滚动时文字不会画到行号列上
        canvas.save()
        canvas.clipRect(gutterW, 0f, width.toFloat(), height.toFloat())

        if (hasSelection) drawSelection(canvas, docTop, first, last)

        val origin = textOrigin() - scrollX
        for (i in first until last) {
            drawLine(canvas, i, docTop + i * lineHeight + baselineShift, origin)
        }

        if (placeholder.isNotEmpty() && lines.size == 1 && lines[0].isEmpty()) {
            textPaint.color = 0xFFD1D5DB.toInt()
            canvas.drawText(placeholder, origin + 2 * density, docTop + baselineShift, textPaint)
        }

        if (isFocused && caretOn && !hasSelection) {
            val cx = textOrigin() + measurePrefix(caretLine, caretCol.toFloat()) - scrollX
            val top = docTop + caretLine * lineHeight
            canvas.drawRect(cx, top + density, cx + 2f * density, top + lineHeight - density, caretPaint)
        }
        canvas.restore()

        // 行号列盖在最上层，保证不被滚动的文字压住
        canvas.drawRect(0f, 0f, gutterW, height.toFloat(), gutterBgPaint)
        canvas.drawRect(gutterW - density, 0f, gutterW, height.toFloat(), gutterDividerPaint)
        gutterPaint.color = colorLineNumber
        for (i in first until last) {
            val label = (i + 1).toString()
            val y = docTop + i * lineHeight + baselineShift
            canvas.drawText(label, gutterW - gutterGap - gutterPaint.measureText(label), y, gutterPaint)
        }
    }

    private fun drawLine(canvas: Canvas, lineIdx: Int, baselineY: Float, originX: Float) {
        val line = lines[lineIdx]
        if (line.isEmpty()) return
        val toks = if (lineIdx < hlRows.size) hlRows[lineIdx] else null
        if (toks == null || toks.isEmpty()) {
            textPaint.color = colorDefault
            canvas.drawText(line, originX, baselineY, textPaint)
            return
        }
        var cursor = 0
        var cx = originX
        var k = 0
        val n = line.length
        while (k + 2 < toks.size) {
            val start = toks[k].coerceIn(0, n)
            val end = toks[k + 1].coerceIn(start, n)
            val style = toks[k + 2]
            k += 3
            if (start < cursor || start >= n) continue
            if (start > cursor) {
                textPaint.color = colorDefault
                canvas.drawText(line, cursor, start, cx, baselineY, textPaint)
                cx += textPaint.measureText(line, cursor, start)
            }
            textPaint.color = if (style in textColors.indices) textColors[style] else colorDefault
            canvas.drawText(line, start, end, cx, baselineY, textPaint)
            cx += textPaint.measureText(line, start, end)
            cursor = end
        }
        if (cursor < n) {
            textPaint.color = colorDefault
            canvas.drawText(line, cursor, n, cx, baselineY, textPaint)
        }
    }

    private fun drawSelection(canvas: Canvas, docTop: Float, first: Int, last: Int) {
        val (l0, c0, l1, c1) = selBounds()
        selPaint.color = 0xFFBFDBFE.toInt()
        for (i in max(l0, first) until min(l1 + 1, last)) {
            val line = lines[i]
            val from = if (i == l0) c0.coerceIn(0, line.length) else 0
            val to = if (i == l1) c1.coerceIn(0, line.length) else line.length
            val x1 = textOrigin() + measurePrefix(i, from.toFloat()) - scrollX
            val x2 = if (i == l1) textOrigin() + measurePrefix(i, to.toFloat()) - scrollX
            else width.toFloat() + scrollX
            val y = docTop + i * lineHeight
            if (x2 > x1) canvas.drawRect(x1, y, x2, y + lineHeight, selPaint)
        }
    }

    /** 选区四端（行/列），保证 start 在 end 之前。 */
    private fun selBounds(): IntArray {
        val a = selLine
        val b = caretLine
        return if (a < b || (a == b && selCol <= caretCol)) {
            intArrayOf(a, selCol, b, caretCol)
        } else {
            intArrayOf(b, caretCol, a, selCol)
        }
    }

    private fun measurePrefix(lineIdx: Int, col: Float): Float {
        val idx = lineIdx.coerceIn(0, lines.size - 1)
        val line = lines[idx]
        val whole = col.toInt().coerceIn(0, line.length)
        val frac = (col - whole).coerceIn(0f, 1f)
        val w = if (whole == 0) 0f else textPaint.measureText(line, 0, whole)
        return w + frac * charWidth
    }

    // ───────────────────────── 尺寸 ─────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyContentSize()
    }

    // ───────────────────────── 编辑操作 ─────────────────────────

    private fun offsetToLineCol(offset: Int): Pair<Int, Int> {
        var left = offset
        var line = 0
        while (line < lines.size) {
            val len = lines[line].length + 1
            if (left <= len) break
            left -= len
            line++
        }
        val l = line.coerceIn(0, lines.size - 1)
        return l to left.coerceIn(0, lines[l].length)
    }

    private fun clearSelection() {
        selLine = -1
        selCol = -1
    }

    /** 用 [replacement] 替换 [l0,c0] ~ [l1,c1]，并维护行缓存与统计。 */
    private fun replaceRange(l0: Int, c0: Int, l1: Int, c1: Int, replacement: String) {
        val a = l0.coerceIn(0, lines.size - 1)
        val b = l1.coerceIn(a, lines.size - 1)
        val ca = c0.coerceIn(0, lines[a].length)
        val cb = c1.coerceIn(0, lines[b].length)

        val prefix = lines[a].substring(0, ca)
        val suffix = lines[b].substring(cb)
        val removedLines = b - a + 1

        val rep = if (replacement.isEmpty()) null else replacement.split('\n')
        val insert = ArrayList<String>(rep?.size ?: 1)
        if (rep == null) {
            insert.add(prefix + suffix)
        } else if (rep.size == 1) {
            insert.add(prefix + rep[0] + suffix)
        } else {
            insert.add(prefix + rep.first())
            for (k in 1 until rep.size - 1) insert.add(rep[k])
            insert.add(rep.last() + suffix)
        }

        // 最宽行被改短时要重算（其余情况只量新内容）
        val touchedWidest = widestIdx in a..b
        var removedChars = 0
        for (k in a..b) removedChars += lines[k].length
        for (k in 0 until removedLines) {
            if (a < lines.size) lines.removeAt(a)
        }
        lines.addAll(a, insert)

        // 行号列宽只跟行数有关
        if (lines.size != numberLineCount) {
            numberWidth = gutterPaint.measureText(lines.size.toString())
            numberLineCount = lines.size
        }
        var newMax = 0f
        var newMaxIdx = a
        for (k in insert.indices) {
            val w = textPaint.measureText(insert[k])
            if (w > newMax) {
                newMax = w
                newMaxIdx = a + k
            }
        }
        val shiftedWidest = if (widestIdx >= a + removedLines) widestIdx + insert.size - removedLines else widestIdx
        widestIdx = shiftedWidest
        if (newMax > widest) {
            widest = newMax
            widestIdx = newMaxIdx
        } else if (touchedWidest) {
            widest = 0f
            widestIdx = -1
            for (i in lines.indices) {
                val w = textPaint.measureText(lines[i])
                if (w > widest) {
                    widest = w
                    widestIdx = i
                }
            }
        }

        // 高亮只重扫改动行
        highlightFrom = a
        highlightRemoved = removedLines
        highlightInserted = insert.size
        rehighlight()

        cachedText = null
        // 字数增量维护：别每次按键扫全文
        var insertedChars = 0
        for (s in insert) insertedChars += s.length
        charCount = max(0, charCount - removedChars + insertedChars)

        caretLine = a + insert.size - 1
        caretCol = if (insert.size == 1) ca + (rep?.get(0)?.length ?: 0) else insert.last().length - suffix.length
        applyContentSize()
        invalidate()
        notifyEdited()
    }

    private fun notifyEdited() {
        caretOn = true
        removeCallbacks(caretBlink)
        postDelayed(caretBlink, 520)
        scrollCaretIntoView()
        notifyStats()
        onTextEdited?.invoke(peekText(), caretOffset())
        onCaretMoved?.invoke(caretLine, caretCol)
    }

    /** 光标绝对偏移（O(行数) 的加和，只在上报时算一次）。 */
    private fun caretOffset(): Int {
        var off = 0
        for (i in 0 until caretLine.coerceAtMost(lines.size)) off += lines[i].length + 1
        return off + caretCol
    }

    private fun notifyStats() {
        onStats?.invoke(lines.size, peekText().length)
    }

    private fun scrollCaretIntoView() {
        val cx = textOrigin() + measurePrefix(caretLine, caretCol.toFloat())
        val cy = padTop + topInsetPx + caretLine * lineHeight
        if (cx - scrollX < textOrigin()) scrollX = max(0f, cx - textOrigin())
        else if (cx - scrollX > width - padStart) scrollX = min(maxScrollX, cx - (width - padStart))
        // 顶部边界要算上悬浮顶栏的安全区，否则光标滚到首行会被顶栏挡在里面
        val safeTop = padTop + topInsetPx
        if (cy - scrollY < safeTop) scrollY = max(0f, cy - safeTop)
        else if (cy - scrollY > height - lineHeight) scrollY = min(maxScrollY, cy - height + lineHeight)
        scrollX = scrollX.coerceIn(0f, maxScrollX)
        scrollY = scrollY.coerceIn(0f, maxScrollY)
    }

    private fun deleteSelection(): Boolean {
        if (!hasSelection) return false
        val b = selBounds()
        replaceRange(b[0], b[1], b[2], b[3], "")
        clearSelection()
        return true
    }

    fun insertText(text: String) {
        if (readOnly || text.isEmpty()) return
        deleteSelection()
        replaceRange(caretLine, caretCol, caretLine, caretCol, text)
        clearSelection()
    }

    /** 智能回车：继承缩进，`{`/`:` 之后多缩一级。 */
    fun newlineSmart() {
        if (readOnly) return
        deleteSelection()
        val line = lines[caretLine]
        val before = line.substring(0, caretCol.coerceIn(0, line.length))
        val indent = before.takeWhile { it == ' ' || it == '\t' }
        val extra = if (before.trimEnd().endsWith("{") || before.trimEnd().endsWith(":")) "    " else ""
        replaceRange(caretLine, caretCol, caretLine, caretCol, "\n" + indent + extra)
        clearSelection()
    }

    /** 自动配对：输入 ( [ { " ' 时补右半，光标停在中间。 */
    fun insertWithPair(ch: Char) {
        if (readOnly) return
        deleteSelection()
        val pair = when (ch) {
            '(' -> ")"
            '{' -> "}"
            '[' -> "]"
            '"' -> "\""
            '\'' -> "'"
            else -> null
        }
        if (pair == null) {
            insertText(ch.toString())
            return
        }
        insertText(ch + pair)
        // 光标退回到括号中间
        caretCol = max(0, caretCol - pair.length)
        scrollCaretIntoView()
        invalidate()
    }

    fun deleteBefore() {
        if (readOnly) return
        if (deleteSelection()) return
        if (caretCol > 0) {
            replaceRange(caretLine, caretCol - 1, caretLine, caretCol, "")
        } else if (caretLine > 0) {
            val prevLen = lines[caretLine - 1].length
            replaceRange(caretLine - 1, prevLen, caretLine, 0, "")
        }
        clearSelection()
    }

    fun deleteForward() {
        if (readOnly) return
        if (deleteSelection()) return
        val line = lines[caretLine]
        if (caretCol < line.length) {
            replaceRange(caretLine, caretCol, caretLine, caretCol + 1, "")
        } else if (caretLine < lines.size - 1) {
            replaceRange(caretLine, caretCol, caretLine + 1, 0, "")
        }
        clearSelection()
    }

    /** Tab：有选区按块缩进/反缩进，无选区插入 4 空格。 */
    fun handleTab(outdent: Boolean) {
        if (readOnly) return
        if (!hasSelection) {
            if (outdent) return
            insertText("    ")
            return
        }
        val b = selBounds()
        val l0 = b[0]
        val l1 = b[2]
        val c0 = b[1]
        val c1 = b[3]
        for (i in l0..min(l1, lines.size - 1)) {
            val line = lines[i]
            if (outdent) {
                val cut = when {
                    line.startsWith("\t") -> 1
                    line.startsWith("    ") -> 4
                    line.startsWith(" ") -> line.length - line.trimStart(' ').length
                    else -> 0
                }
                if (cut > 0) lines[i] = line.substring(cut)
            } else {
                lines[i] = "    $line"
            }
        }
        rehighlightAll()
        cachedText = null
        charCount = 0
        for (s in lines) charCount += s.length + 1
        charCount = max(0, charCount - 1)
        selLine = l0
        selCol = if (outdent) max(0, c0 - 4) else c0 + 4
        caretCol = if (outdent) max(0, c1 - 4) else c1 + 4
        applyContentSize()
        invalidate()
        notifyEdited()
    }

    // ───────────────────────── IME ─────────────────────────

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_ACTION_NONE
        return EditorInputConnection(this)
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)) return
        post { imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun textBeforeCursor(maxChars: Int): CharSequence {
        if (maxChars <= 0) return ""
        val sb = StringBuilder()
        var line = caretLine
        var col = caretCol.coerceIn(0, lines[line].length)
        sb.append(lines[line], 0, col)
        line--
        while (line >= 0 && sb.length < maxChars) {
            sb.insert(0, "\n")
            val l = lines[line]
            val take = min(l.length, maxChars - sb.length)
            sb.insert(0, l, l.length - take, l.length)
            line--
        }
        return if (sb.length > maxChars) sb.substring(sb.length - maxChars) else sb
    }

    private fun textAfterCursor(maxChars: Int): CharSequence {
        if (maxChars <= 0) return ""
        val sb = StringBuilder()
        var line = caretLine
        var col = caretCol.coerceIn(0, lines[line].length)
        sb.append(lines[line], col, lines[line].length)
        line++
        while (line < lines.size && sb.length < maxChars) {
            sb.append('\n')
            val l = lines[line]
            val take = min(l.length, maxChars - sb.length)
            sb.append(l, 0, take)
            line++
        }
        return if (sb.length > maxChars) sb.substring(0, maxChars) else sb
    }

    // 组合输入（拼音）预览：先写进正文，下次更新前先撤掉
    private var compLine = -1
    private var compCol = -1

    private fun clearComposing() {
        if (compLine < 0) return
        val l = compLine.coerceIn(0, lines.size - 1)
        val c = compCol.coerceIn(0, lines[l].length)
        replaceRange(l, c, caretLine, caretCol, "")
        caretLine = l
        caretCol = c
        compLine = -1
        compCol = -1
    }

    internal fun imeCommit(text: String) {
        if (readOnly) return
        clearComposing()
        insertText(text)
        clearSelection()
    }

    internal fun imeSetComposing(text: String) {
        if (readOnly) return
        clearComposing()
        compLine = caretLine
        compCol = caretCol
        if (text.isEmpty()) return
        replaceRange(caretLine, caretCol, caretLine, caretCol, text)
        // 组合区带下划线
    }

    internal fun imeDeleteSurrounding(before: Int, after: Int) {
        if (readOnly) return
        clearComposing()
        repeat(before) { deleteBefore() }
        repeat(after) { deleteForward() }
    }

    internal fun imeKey(event: KeyEvent): Boolean {
        if (readOnly) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (event.action == KeyEvent.ACTION_DOWN) deleteBefore()
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (event.action == KeyEvent.ACTION_DOWN) deleteForward()
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN) newlineSmart()
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                if (event.action == KeyEvent.ACTION_DOWN) handleTab(event.isShiftPressed)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN) moveCaret(-1, 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) moveCaret(1, 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) moveCaret(0, -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) moveCaret(0, 1)
                return true
            }
        }
        val c = event.unicodeChar
        if (c != 0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val ch = c.toChar()
                if (ch == '(' || ch == '{' || ch == '[' || ch == '"' || ch == '\'') insertWithPair(ch)
                else insertText(ch.toString())
            }
            return true
        }
        return false
    }

    private fun moveCaret(dx: Int, dy: Int) {
        if (dy != 0) {
            caretLine = (caretLine + dy).coerceIn(0, lines.size - 1)
            caretCol = min(caretCol, lines[caretLine].length)
        } else {
            caretCol += dx
            if (caretCol < 0) {
                if (caretLine > 0) {
                    caretLine--
                    caretCol = lines[caretLine].length
                } else caretCol = 0
            } else if (caretCol > lines[caretLine].length) {
                if (caretLine < lines.size - 1) {
                    caretLine++
                    caretCol = 0
                } else caretCol = lines[caretLine].length
            }
        }
        clearSelection()
        scrollCaretIntoView()
        onCaretMoved?.invoke(caretLine, caretCol)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (imeKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    // ───────────────────────── 触摸：滚动 / 落光标 / 选区 ─────────────────────────

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var selecting = false
    private var axis = 0 // 0 未定，1 垂直，2 水平

    private val longPress = Runnable {
        if (dragging) return@Runnable
        selecting = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        selectWordAt(lastX, lastY)
    }

    private val fling = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            scrollX = scroller.currX.toFloat()
            scrollY = scroller.currY.toFloat()
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun showKeyboardAndFocus() {
        requestFocus()
        showKeyboard()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (readOnly) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(fling)
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                selecting = false
                axis = 0
                removeCallbacks(longPress)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                showKeyboardAndFocus()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (!dragging && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                if (selecting) {
                    extendSelectionTo(event.x, event.y)
                } else if (dragging) {
                    if (axis == 0) axis = if (abs(event.x - downX) > abs(event.y - downY)) 2 else 1
                    if (axis == 2) scrollX = (scrollX - dx).coerceIn(0f, maxScrollX)
                    else scrollY = (scrollY - dy).coerceIn(0f, maxScrollY)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val v = velocity
                v?.addMovement(event)
                if (!dragging) {
                    // 单击落光标
                    selecting = false
                    placeCaretAt(event.x, event.y)
                } else if (!selecting && v != null) {
                    v.computeCurrentVelocity(1000)
                    val vx = -v.xVelocity
                    val vy = -v.yVelocity
                    if (abs(vx) > 120 || abs(vy) > 120) {
                        scroller.fling(
                            scrollX.toInt(), scrollY.toInt(),
                            vx.toInt(), vy.toInt(),
                            0, maxScrollX.toInt(), 0, maxScrollY.toInt()
                        )
                        postOnAnimation(fling)
                    }
                }
                v?.recycle()
                velocity = null
                dragging = false
                selecting = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                velocity?.recycle()
                velocity = null
                dragging = false
                selecting = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun locate(x: Float, y: Float): Pair<Int, Int> {
        val line = floor((y - padTop - topInsetPx + scrollY) / lineHeight).toInt().coerceIn(0, lines.size - 1)
        val textX = x - textOrigin() + scrollX
        if (textX <= 0) return line to 0
        val paintX = textPaint
        val lineText = lines[line]
        if (lineText.isEmpty()) return line to 0
        // 二分找最近的字符边界（一行最长可能有几万字符，别线性扫）
        var lo = 0
        var hi = lineText.length
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val w = paintX.measureText(lineText, 0, mid)
            if (w < textX) lo = mid + 1 else hi = mid
        }
        var col = lo
        if (col > 0) {
            val w1 = paintX.measureText(lineText, 0, col - 1)
            val w2 = paintX.measureText(lineText, 0, col)
            if (abs(w1 - textX) <= abs(w2 - textX)) col -= 1
        }
        return line to col.coerceIn(0, lineText.length)
    }

    private fun placeCaretAt(x: Float, y: Float) {
        val (l, c) = locate(x, y)
        caretLine = l
        caretCol = c
        clearSelection()
        caretOn = true
        onCaretMoved?.invoke(l, c)
        invalidate()
    }

    private fun selectWordAt(x: Float, y: Float) {
        val (l, c) = locate(x, y)
        val line = lines[l]
        if (line.isEmpty()) return
        var s = c.coerceIn(0, line.length)
        var e = s
        fun isWord(ch: Char) = ch.isLetterOrDigit() || ch == '_'
        while (s > 0 && isWord(line[s - 1])) s--
        while (e < line.length && isWord(line[e])) e++
        if (s == e) {
            s = c.coerceIn(0, max(0, line.length - 1))
            e = min(line.length, s + 1)
        }
        selLine = l
        selCol = s
        caretLine = l
        caretCol = e
        invalidate()
    }

    private fun extendSelectionTo(x: Float, y: Float) {
        val (l, c) = locate(x, y)
        caretLine = l
        caretCol = c
        // 拖到边缘自动滚（顶部要跳过被悬浮顶栏覆盖的安全区，别在顶栏底下误触发）
        if (y < topInsetPx + lineHeight) scrollY = max(0f, scrollY - lineHeight)
        else if (y > height - lineHeight) scrollY = min(maxScrollY, scrollY + lineHeight)
        invalidate()
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(caretBlink, 520)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(caretBlink)
        removeCallbacks(longPress)
        removeCallbacks(fling)
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    // ───────────────────────── IME 连接 ─────────────────────────

    private class EditorInputConnection(private val view: CanvasCodeEditor) :
        BaseInputConnection(view, true) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            view.imeCommit(text?.toString() ?: "")
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            view.imeSetComposing(text?.toString() ?: "")
            return true
        }

        override fun finishComposingText(): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            view.imeDeleteSurrounding(beforeLength, afterLength)
            return true
        }

        override fun getTextBeforeCursor(maxChars: Int, flags: Int): CharSequence =
            view.textBeforeCursor(maxChars)

        override fun getTextAfterCursor(maxChars: Int, flags: Int): CharSequence =
            view.textAfterCursor(maxChars)

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                view.newlineSmart()
                return true
            }
            return view.imeKey(event)
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            // 大文件不给全文快照：输入法每敲一下拷一次全文，代价是文件大小。
            val text = view.peekText()
            if (text.length > 200_000) return null
            var off = 0
            for (i in 0 until view.caretLine.coerceAtMost(view.lines.size)) {
                off += view.lines[i].length + 1
            }
            off += view.caretCol
            val et = ExtractedText()
            et.text = text
            et.startOffset = 0
            et.selectionStart = off.coerceIn(0, text.length)
            et.selectionEnd = et.selectionStart
            return et
        }
    }
}

/**
 * 单行分词：无跨行状态，所以"只重算改动行"和"全量重算"结果一致。
 * 用 Matcher.region 就地限定范围，不为每行 substring 出新字符串。
 */
internal object LineTokens {

    private const val S_KEYWORD = 0
    private const val S_STRING = 1
    private const val S_NUMBER = 2
    private const val S_COMMENT = 3
    private const val S_TYPE = 4
    private const val S_FUNC = 5

    private val stringRe = java.util.regex.Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
    private val numberRe = java.util.regex.Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b")
    private val identRe = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*")

    private val KOTLIN = setOf(
        "fun", "val", "var", "class", "interface", "object", "when", "if", "else", "for", "while",
        "return", "suspend", "data", "sealed", "enum", "companion", "override", "public", "private",
        "protected", "internal", "import", "package", "null", "true", "false", "this", "super",
        "as", "is", "in", "out", "try", "catch", "finally", "throw", "do", "break", "continue",
        "lateinit", "init", "by", "where", "typealias", "annotation", "open", "abstract", "const"
    )
    private val JAVA = setOf(
        "public", "private", "protected", "class", "interface", "extends", "implements", "static",
        "final", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short",
        "new", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break",
        "continue", "try", "catch", "finally", "throw", "throws", "this", "super", "null", "true",
        "false", "package", "import", "abstract", "synchronized", "volatile", "transient", "instanceof"
    )
    private val PYTHON = setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue",
        "import", "from", "as", "try", "except", "finally", "raise", "with", "lambda", "yield",
        "None", "True", "False", "and", "or", "not", "in", "is", "pass", "global", "nonlocal",
        "assert", "del", "async", "await"
    )
    private val JS = setOf(
        "function", "const", "let", "var", "return", "if", "else", "for", "while", "do", "switch",
        "case", "default", "break", "continue", "try", "catch", "finally", "throw", "new", "class",
        "extends", "super", "this", "null", "undefined", "true", "false", "import", "export",
        "from", "async", "await", "typeof", "instanceof", "yield"
    )
    private val C_LIKE = setOf(
        "int", "char", "float", "double", "void", "long", "short", "unsigned", "signed", "struct",
        "union", "enum", "typedef", "static", "const", "return", "if", "else", "for", "while", "do",
        "switch", "case", "default", "break", "continue", "goto", "sizeof", "include", "define"
    )
    private val GO = setOf(
        "func", "package", "import", "var", "const", "type", "struct", "interface", "map", "chan",
        "go", "defer", "select", "case", "default", "switch", "if", "else", "for", "range", "return",
        "break", "continue", "nil", "true", "false"
    )
    private val EMPTY = emptySet<String>()

    fun keywordsFor(ext: String): Set<String> = when (ext) {
        "kt", "kts" -> KOTLIN
        "java", "cs", "scala", "dart" -> JAVA
        "py" -> PYTHON
        "js", "jsx", "ts", "tsx", "mjs", "cjs" -> JS
        "c", "h", "cpp", "cc", "cxx", "hpp", "m", "mm" -> C_LIKE
        "go" -> GO
        "swift", "rs", "rb", "php", "sql", "sh", "bash", "json", "xml", "html", "css", "md", "txt" -> EMPTY
        else -> EMPTY
    }

    fun tokenize(line: String, keywords: Set<String>, ext: String): IntArray? {
        if (line.isEmpty()) return null
        val n = line.length

        val commentMark = lineCommentFor(ext)
        val commentAt = if (commentMark != null) line.indexOf(commentMark) else -1
        val codeEnd = if (commentAt >= 0) commentAt else n

        val starts = ArrayList<Int>(16)
        val ends = ArrayList<Int>(16)
        val styles = ArrayList<Int>(16)

        if (codeEnd > 0) {
            val ms = stringRe.matcher(line).apply { region(0, codeEnd) }
            while (ms.find()) { starts.add(ms.start()); ends.add(ms.end()); styles.add(S_STRING) }

            val mn = numberRe.matcher(line).apply { region(0, codeEnd) }
            while (mn.find()) { starts.add(mn.start()); ends.add(mn.end()); styles.add(S_NUMBER) }

            val mi = identRe.matcher(line).apply { region(0, codeEnd) }
            while (mi.find()) {
                val style = when {
                    keywords.contains(mi.group()) -> S_KEYWORD
                    Character.isUpperCase(line[mi.start()]) -> S_TYPE
                    mi.end() < codeEnd && line[mi.end()] == '(' -> S_FUNC
                    else -> -1
                }
                if (style >= 0) { starts.add(mi.start()); ends.add(mi.end()); styles.add(style) }
            }
        }
        if (commentAt >= 0) {
            starts.add(commentAt); ends.add(n); styles.add(S_COMMENT)
        }
        if (starts.isEmpty()) return null

        // 三趟扫描会重叠：按起点排序，保留先到的、跳过被覆盖的
        val order = indicesSortedByStart(starts)
        val merged = ArrayList<Int>(starts.size * 3)
        var cursor = 0
        for (idx in order) {
            val s = starts[idx]
            val e = ends[idx]
            if (s < cursor || e <= s) continue
            merged.add(s); merged.add(e); merged.add(styles[idx])
            cursor = e
        }
        return merged.toIntArray()
    }

    private fun indicesSortedByStart(starts: ArrayList<Int>): IntArray {
        val idx = IntArray(starts.size) { it }
        // 插入排序：一行里 token 数量很小（几十个），比 sortedBy 装箱排序快
        for (i in 1 until idx.size) {
            val v = idx[i]
            var j = i - 1
            while (j >= 0 && starts[idx[j]] > starts[v]) {
                idx[j + 1] = idx[j]
                j--
            }
            idx[j + 1] = v
        }
        return idx
    }

    fun lineCommentFor(ext: String): String? = when (ext) {
        "kt", "kts", "java", "js", "jsx", "ts", "tsx", "c", "h", "cpp", "cc", "hpp", "cs", "go", "rs", "swift", "scala", "dart" -> "//"
        "py", "rb", "sh", "bash", "yml", "yaml", "toml", "properties", "conf" -> "#"
        "sql" -> "--"
        else -> null
    }
}
