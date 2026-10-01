package com.aurora.chat.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.R
import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.data.api.UserInfo
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.data.upload.MediaUploadManager
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.ui.text.UrlText
import com.aurora.chat.ui.chat.ChatMsg
import com.aurora.chat.ui.chat.FlashIcon
import com.aurora.chat.ui.chat.FlashViewerDialog
import com.aurora.chat.ui.chat.VideoThumbnailView
import com.aurora.chat.ui.chat.media.ChatMediaImage
import com.aurora.chat.ui.chat.media.MediaStore
import com.aurora.chat.ui.chat.isLocationCardMessage
import com.aurora.chat.ui.chat.parseLocationCard
import com.aurora.chat.ui.chat.LocationCardData
import com.aurora.chat.ui.chat.isShareCardMessage
import com.aurora.chat.ui.chat.parseShareCard
import com.aurora.chat.ui.chat.isPrivacyRequestCard
import com.aurora.chat.ui.chat.isPrivacyResponseCard
import com.aurora.chat.ui.chat.PrivacyCard
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * AI 自定义头像的全局可观察状态：用户上传后替换默认 AI 头像（ai_avatar 资源）。
 * 使用 mutableStateOf 以便对话中已渲染的 AI 气泡在上传/恢复后自动刷新。
 */
object AiAvatarState {
    var bitmap by mutableStateOf<android.graphics.Bitmap?>(null)
}

/**
 * AI 自定义名称的全局可观察状态：用户设置后替换默认名称（AI）。
 * 使用 mutableStateOf 以便聊天列表 / 对话标题 / AI 气泡在设置后自动刷新。
 */
object AiNameState {
    var name by mutableStateOf<String?>(null)
}

/**
 * 默认 AI 头像：渐变蓝色圆角徽章 + 白色「AI」，代表通用 AI（不再使用 DeepSeek 鲸鱼 logo）。
 * 字体大小随头像尺寸自动缩放，替代原先依赖 ai_avatar 图片资源的兜底显示。
 */
@Composable
fun AiDefaultAvatar(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(
            Brush.verticalGradient(listOf(Color(0xFF79A3FF), Color(0xFF3E63DD)))
        ),
        contentAlignment = Alignment.Center
    ) {
        val fs = (maxWidth.value * 0.32f).sp
        Text(
            text = "AI",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = fs,
            letterSpacing = (fs.value * 0.14f).sp,
            maxLines = 1
        )
    }
}

/**
 * 图片渲染（委托给重写后的 ChatMediaImage，统一媒体来源与失败兜底，避免显示空气泡）。
 */
@Composable
private fun MediaImageView(
    url: String,
    isMine: Boolean,
    loadMedia: Boolean,
    highlightAnim: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    onImageClick: (String) -> Unit,
    onBubbleLongPress: () -> Unit
) = ChatMediaImage(
    url = url,
    isMine = isMine,
    loadMedia = loadMedia,
    highlightAnim = highlightAnim,
    onImageClick = onImageClick,
    onBubbleLongPress = onBubbleLongPress
)

/** 文本分段：把 AI 消息里的 Markdown 代码块/竖线表格拆出来，其余保持为纯文本段 */
/** 表格单元格：文本 + 可选内联样式（加粗 / 前景色 / 背景色），竖线与 HTML 表格共用 */
internal data class TableCell(
    val text: String,
    val bold: Boolean = false,
    val color: Color? = null,
    val bgColor: Color? = null
)

internal sealed interface MsgSeg {
    data class TextSeg(val text: String) : MsgSeg
    data class CodeSeg(val language: String, val code: String) : MsgSeg
    data class TableSeg(val headerCells: List<TableCell>, val rows: List<List<TableCell>>) : MsgSeg
}

/**
 * 解析 Markdown 的代码块（` ``` `）与竖线表格，返回按原文顺序排列的文本/代码/表格段。
 * 代码块按「行」判定：开启围栏只认行首（可含空白）>=3 反引号，闭合围栏只认行首反引号 run
 * >= 开启围栏长度且其后仅空白；内部内容 verbatim，内联 1~2 反引号永不结束/误开代码块。
 * 未闭合的代码块（流式中）也按代码块实时输出。
 * 返回 null 表示文本不含任何代码块或表格（调用方可直接走原文快路径）。
 */
internal fun parseMsgSegments(text: String): List<MsgSeg>? {
    val result = mutableListOf<MsgSeg>()
    val textBuf = StringBuilder()
    var hasStructured = false
    // 把一段「非代码」文本再按竖线表格拆分（普通文本段原样保留为 TextSeg）
    fun flushText() {
        if (textBuf.isEmpty()) return
        val buf = textBuf.toString()
        textBuf.setLength(0)
        if (segmentableText(buf) && splitTextWithTables(buf, result)) hasStructured = true
        else result.add(MsgSeg.TextSeg(buf))
    }
    val lines = text.split("\n")
    var inCode = false
    var fenceLen = 0
    var fenceLang = ""
    var codeSb = StringBuilder()
    for (rawLine in lines) {
        val line = if (rawLine.endsWith("\r")) rawLine.dropLast(1) else rawLine
        if (inCode) {
            if (matchClosingFence(line, fenceLen)) {
                hasStructured = true
                result.add(MsgSeg.CodeSeg(fenceLang, codeSb.toString()))
                inCode = false
            } else {
                if (codeSb.isNotEmpty()) codeSb.append('\n')
                codeSb.append(line)
            }
        } else {
            val f = matchOpeningFence(line)
            if (f != null) {
                flushText()
                hasStructured = true
                fenceLang = f.second; fenceLen = f.first
                codeSb = StringBuilder()
                inCode = true
            } else {
                if (textBuf.isNotEmpty()) textBuf.append('\n')
                textBuf.append(line)
            }
        }
    }
    if (inCode) {
        // 流式未闭合的代码块：把已到达内容实时渲染为代码块，等结尾围栏行到达后再正常闭合
        hasStructured = true
        result.add(MsgSeg.CodeSeg(fenceLang, codeSb.toString()))
    } else {
        flushText()
    }
    return if (hasStructured) result else null
}

/** 按竖线拆分表格行单元格：去掉首尾空段、trim 每格、去掉外层 ** 加粗标记 */
internal fun splitPipeCells(line: String): List<String> {
    val cells = mutableListOf<String>()
    var cur = StringBuilder()
    for (ch in line) {
        if (ch == '|') { cells.add(cur.toString()); cur = StringBuilder() }
        else cur.append(ch)
    }
    cells.add(cur.toString())
    while (cells.size > 1 && cells.first().trim().isEmpty()) cells.removeAt(0)
    while (cells.size > 1 && cells.last().trim().isEmpty()) cells.removeAt(cells.lastIndex)
    return cells.map { it.trim().removeSurrounding("**") }
}

/** 表格分隔行：只由空格/冒号/短横/竖线构成，且含短横 */
internal fun isSeparatorLine(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return false
    return Regex("^\\|?[\\s:\\-|]+\\|?$").matches(t) && t.contains("-")
}

/** 把一段非代码文本里的连续竖线表格拆成「文本段/表格段」，返回是否识别出至少一个表格 */
internal fun splitPipeText(chunk: String, out: MutableList<MsgSeg>): Boolean {
    var foundTable = false
    val lines = chunk.split("\n")
    val sb = StringBuilder()
    fun flushText() {
        if (sb.isNotEmpty()) { out.add(MsgSeg.TextSeg(sb.toString())); sb.clear() }
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val headerCells = splitPipeCells(line)
        val isTableRow = headerCells.size >= 2
        val isSep = isSeparatorLine(line)
        if (isTableRow && !isSep) {
            flushText()
            var idx = i + 1
            if (idx < lines.size && isSeparatorLine(lines[idx])) idx++
            val rows = mutableListOf<List<TableCell>>()
            while (idx < lines.size) {
                val rc = splitPipeCells(lines[idx])
                if (rc.size < 2 || isSeparatorLine(lines[idx])) break
                rows.add(rc.map { TableCell(cleanTableCell(it)) })
                idx++
            }
            out.add(MsgSeg.TableSeg(headerCells.map { TableCell(cleanTableCell(it)) }, rows))
            foundTable = true
            i = idx
        } else {
            sb.append(line)
            if (i < lines.size - 1) sb.append("\n")
            i++
        }
    }
    flushText()
    return foundTable
}

/** 该文本是否可能含竖线表格或 HTML 表格（决定是否走分段解析，否则直接当纯文本） */
internal fun segmentableText(s: String): Boolean =
    s.contains('|') || s.contains("<table", ignoreCase = true)

private data class CellStyle(val color: Color? = null, val bgColor: Color? = null, val bold: Boolean = false)

/** 解析单元格内联 style 的颜色 / 背景色 / 加粗；无法解析的颜色一律忽略 */
private fun parseCellStyle(styleAttr: String): CellStyle {
    val s = " $styleAttr "
    var color: Color? = null
    var bg: Color? = null
    var bold = false
    val cRe = Regex("color\\s*:\\s*#[0-9a-fA-F]{3,8}")
    val c = cRe.find(s)
    if (c != null) color = parseHexColor(c.value.substringAfter('#'))
    val bRe = Regex("background(?:-color)?\\s*:\\s*#[0-9a-fA-F]{3,8}")
    val b = bRe.find(s)
    if (b != null) bg = parseHexColor(b.value.substringAfter('#'))
    val fRe = Regex("font-weight\\s*:\\s*([^;\\n\\r]+)")
    val f = fRe.find(s)
    if (f != null) {
        val v = f.groupValues[1].trim().lowercase()
        bold = v == "bold" || (v.toIntOrNull() ?: 0) >= 600
    }
    return CellStyle(color, bg, bold)
}

/** 把 #rgb / #rrggbb / #aarrggbb 十六进制颜色映射为 Compose Color；无法解析返回 null */
private fun parseHexColor(hex: String): Color? {
    val h = hex.trim().lowercase()
    val rrggbb = when (h.length) {
        3 -> h.map { "$it$it" }.joinToString("")
        6 -> h
        8 -> h.take(6)
        else -> return null
    }.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rrggbb)
}

/** 解码常见 HTML 实体（先解码命名实体，再处理 &amp; 避免双重解码） */
private fun decodeHtmlEntities(s: String): String =
    s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&nbsp;", " ").replace("&amp;", "&")

/** 从未闭合标签取 style 属性值（支持双引号/单引号/裸值）；无则返回空串 */
private fun extractStyleAttr(openTag: String): String {
    val m = Regex("""style\s*=\s*(?:"([^"]*)"|'([^']*)'|([^>\s]+))""").find(openTag) ?: return ""
    for (g in 1..m.groupValues.lastIndex) if (m.groupValues[g].isNotEmpty()) return m.groupValues[g]
    return ""
}

/** 组合单元格内容与样式；isTh 表头默认加粗 */
private fun buildCell(rawContent: String, styleAttr: String, isTh: Boolean): TableCell {
    val style = parseCellStyle(styleAttr)
    val stripped = rawContent.replace(Regex("(?s)<[^>]*>"), " ")
    val txt = decodeHtmlEntities(cleanTableCell(stripped)).trim()
    return TableCell(text = txt, bold = style.bold || isTh, color = style.color, bgColor = style.bgColor)
}

/** 解析单个 <tr> 内部：按文档顺序提取 <td>/<th> 单元格，返回 (单元格, 是否含 <th>) */
private fun parseRowCells(inner: String): Pair<List<TableCell>, Boolean> {
    var hasTh = false
    val cells = mutableListOf<TableCell>()
    val lower = inner.lowercase()
    for (m in Regex("(?i)<(td|th)\\b[^>]*>").findAll(inner)) {
        val contentStart = m.range.last + 1
        val elem = m.groupValues[1].lowercase()
        val styleAttr = extractStyleAttr(m.value)
        val close = lower.indexOf("</$elem", contentStart)
        val contentEnd = if (close >= 0) close else inner.length
        cells.add(buildCell(inner.substring(contentStart, contentEnd), styleAttr, elem == "th"))
        if (elem == "th") hasTh = true
    }
    return cells to hasTh
}

/** 解析 <table ...> ...（可无 </table>）为 TableSeg；无可渲染行返回 null */
private fun parseHtmlTable(block: String): MsgSeg.TableSeg? {
    if (block.isEmpty()) return null
    val cleaned = block.replace(Regex("(?s)<!--.*?-->"), " ")
    val lower = cleaned.lowercase()
    val rows = mutableListOf<Pair<List<TableCell>, Boolean>>() // cells, hasTh
    for (m in Regex("(?i)<tr\\b[^>]*>").findAll(cleaned)) {
        val contentStart = m.range.last + 1
        val closeTr = lower.indexOf("</tr", contentStart)
        val contentEnd = if (closeTr >= 0) closeTr else cleaned.length
        val (cells, hasTh) = parseRowCells(cleaned.substring(contentStart, contentEnd))
        if (cells.isNotEmpty()) rows.add(cells to hasTh)
    }
    if (rows.isEmpty()) return null
    // 表头：优先取首个含 <th> 的行；否则取首行（与竖线表格的「首行为表头」约定一致）
    val headerIdx = rows.indexOfFirst { it.second }
    val hi = if (headerIdx < 0) 0 else headerIdx
    val headerCells = rows[hi].first
    if (headerCells.isEmpty()) return null
    val dataRows = rows.mapIndexedNotNull { idx, (c, _) -> if (idx == hi) null else c }
    return MsgSeg.TableSeg(headerCells, dataRows)
}

/** 定位标签首字母位置，带单词边界（前后需为非字母数字）与标签后边界检查 */
private fun indexTagFrom(lower: String, tag: String, from: Int): Int {
    var i = lower.indexOf(tag, from)
    while (i >= 0) {
        val prevOk = i == 0 || !lower[i - 1].isLetterOrDigit()
        val after = if (i + tag.length < lower.length) lower[i + tag.length] else '>'
        val afterOk = after == '>' || after.isWhitespace() || after == '/'
        if (prevOk && afterOk) return i
        i = lower.indexOf(tag, i + 1)
    }
    return -1
}

/** 未闭合 <table>：向后取到最后一个完整 </tr> / </td> / </th>，避免吞掉后续普通文本；无任何完整格则返回 openStart */
private fun unclosedTableEnd(chunk: String, lower: String, openStart: Int): Int {
    val lastTr = lower.lastIndexOf("</tr")
    if (lastTr > openStart) { val gt = chunk.indexOf('>', lastTr); if (gt >= 0) return gt + 1 }
    val lastCell = maxOf(lower.lastIndexOf("</td"), lower.lastIndexOf("</th"))
    if (lastCell > openStart) { val gt = chunk.indexOf('>', lastCell); if (gt >= 0) return gt + 1 }
    return openStart
}

/**
 * 把一段非代码文本同时按「竖线表格」与「HTML <table>」拆分，返回是否识别出至少一个表格。
 * HTML 表格解析为 TableSeg 复用同一渲染器；无法解析的 <table 按普通文本原样保留，不吞并周围文本。
 */
internal fun splitTextWithTables(chunk: String, out: MutableList<MsgSeg>): Boolean {
    var found = false
    val lower = chunk.lowercase()
    val n = chunk.length
    var pos = 0
    var open = indexTagFrom(lower, "<table", 0)
    while (open >= 0) {
        val closeStart = lower.indexOf("</table", open)
        val end = if (closeStart >= 0) {
            val gt = chunk.indexOf('>', closeStart); if (gt >= 0) gt + 1 else n
        } else {
            unclosedTableEnd(chunk, lower, open)
        }
        val block = if (end > open) chunk.substring(open, end) else ""
        val parsed = parseHtmlTable(block)
        if (parsed != null) {
            if (open > pos && splitPipeText(chunk.substring(pos, open), out)) found = true
            out.add(parsed)
            found = true
            pos = end
            open = indexTagFrom(lower, "<table", pos)
        } else {
            // 不是可渲染表格：连同前导文本一起保留为普通文本，仅跳过该标签位防死循环
            if (open + 1 > pos && splitPipeText(chunk.substring(pos, open + 1), out)) found = true
            pos = open + 1
            open = indexTagFrom(lower, "<table", pos)
        }
    }
    if (pos < n && splitPipeText(chunk.substring(pos), out)) found = true
    return found
}

/**
 * 代码语言 → 保存文件的 MIME 类型。
 * 系统文件管理器（ACTION_CREATE_DOCUMENT）会根据 intent 的 MIME 自动追加扩展名：
 * 若对已知代码语言用 "text/plain"，用户输入 gh.py 会被存成 gh.py.txt。
 * 因此按语言映射到对应 MIME，让文件管理器保留用户输入的扩展名；未知语言回退 text/plain。
 */
internal fun codeBlockMime(language: String): String = when (language.lowercase().trim()) {
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js", "javascript", "mjs" -> "text/javascript"
    "json" -> "application/json"
    "py", "python" -> "text/x-python"
    "sql" -> "text/x-sql"
    "xml" -> "text/xml"
    "md", "markdown" -> "text/markdown"
    "csv" -> "text/csv"
    "yaml", "yml" -> "text/yaml"
    "java" -> "text/x-java-source"
    "kt", "kotlin", "kts" -> "text/x-kotlin"
    "ts", "typescript" -> "text/typescript"
    "txt" -> "text/plain"
    else -> "text/plain"
}

/**
 * 代码块头部操作条（语言 + 「分享/保存/复制」）：文档内代码块头部与悬浮工具条共用此实现，
 * 保证分享/保存/复制逻辑只维护一份。本身不含圆角裁剪，圆角由调用方（体内面板 / 悬浮条）提供。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeHeaderActions(language: String, code: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showShare by remember { mutableStateOf(false) }
    // “保存”：调起系统文件管理器选择保存路径/文件名，把代码写成普通文本文件。
    // 按语言设置 CreateDocument 的 MIME：已知代码语言不再用 text/plain，避免系统强制追加 .txt。
    val saveMime = remember(language) { codeBlockMime(language) }
    val saveLauncher = rememberLauncherForActivityResult(
        remember(saveMime) { ActivityResultContracts.CreateDocument(saveMime) }
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(code.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }
    val ext = when (language.lowercase().trim()) {
        "html", "htm" -> "html"; "css" -> "css"; "js", "javascript", "mjs" -> "js"
        "json" -> "json"; "sql" -> "sql"; "xml" -> "xml"; "py", "python" -> "py"
        "java" -> "java"; "kt", "kotlin", "kts" -> "kt"; "sh", "bash" -> "sh"
        "md", "markdown" -> "md"; "csv" -> "csv"; "yaml", "yml" -> "yaml"
        "ts", "typescript" -> "ts"
        else -> "txt"
    }
    val fileName = (if (language.isBlank()) "AI_code" else "AI_$language") + ".$ext"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFF3F4F6))
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            language,
            fontSize = 11.sp,
            color = Color(0xFF6B7280),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        Text(
            "分享",
            fontSize = 11.sp,
            color = Color(0xFF2563EB),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { showShare = true }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Text(
            "保存",
            fontSize = 11.sp,
            color = Color(0xFF2563EB),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                    try { saveLauncher.launch(fileName) } catch (_: Exception) {}
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Text(
            "复制",
            fontSize = 11.sp,
            color = Color(0xFF2563EB),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(language, code))
                        Toast.makeText(context, "已复制代码", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
    // 分享给好友：好友选择弹窗（底部上滑的卡片）
    if (showShare) {
        ShareToFriendSheet(
            onDismiss = { showShare = false },
            onPick = { fid, _, isGroup ->
                showShare = false
                try {
                    val dir = File(context.cacheDir, "share_codes").apply { mkdirs() }
                    val f = File(dir, fileName)
                    f.writeText(code)
                    // 走与普通文件消息完全相同的上传+发送链路（对方收到一张可下载的文件卡片）
                    MediaUploadManager.enqueueFromUri(context, fid, isGroup, Uri.fromFile(f), "text/plain", "", 0)
                    Toast.makeText(context, if (isGroup) "已分享到群聊（发送中）" else "已分享给好友（发送中）", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

/**
 * 单个代码块：标题栏（CodeHeaderActions）与灰色代码内容无缝拼接为 ONE 块，
 * 共享一个 8.dp 圆角面板，标题栏紧贴代码内容、之间无任何间隙/分隔，整体读取为一块连续灰块。
 */
@Composable
private fun CodeBlockView(language: String, code: String, onBackToTop: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
    ) {
        CodeHeaderActions(language, code)
        // 灰色代码内容区（等宽、超长行横向滚动），与标题栏直接相邻、无间隙
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF3F4F6))
        ) {
            Text(
                code,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Color(0xFF1F2937),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
            )
        }
        // 底部栏：最右侧「回到顶部」，点击滚回该代码块顶部（所在消息顶部）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF3F4F6))
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                language,
                fontSize = 11.sp,
                color = Color(0xFF6B7280),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                "回到顶部",
                fontSize = 11.sp,
                color = Color(0xFF2563EB),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onBackToTop() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * 渲染一个 Markdown 竖线表格：浅色带边框容器、加粗表头（表头下细分隔线）、
 * 列间细竖线分隔，单元格自适应换行不溢出消息宽度。
 */
@Composable
private fun PipeTableView(seg: MsgSeg.TableSeg) {
    val cols = (listOf(seg.headerCells.size) + seg.rows.map { it.size }).maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFFFFFF))
            // 用 drawWithContent：先画单元格，再把边框/分隔线画在最上层，保证左/右/上/下四边都可见
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                if (h > 0f) {
                    val borderColor = Color(0xFFE5E7EB)
                    val px = 1.dp.toPx()
                    // 外框：圆角矩形描边，右侧/左侧/顶边/底边四条闭合边框全部可见
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(px / 2f, px / 2f),
                        size = androidx.compose.ui.geometry.Size(w - px, h - px),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = px)
                    )
                    // 列间分隔线（每两列之间一条，与按 weight 均分的列边界对齐）
                    if (cols > 1) {
                        for (i in 1 until cols) {
                            val x = w * i / cols
                            drawLine(borderColor, Offset(x, 0f), Offset(x, h), strokeWidth = px)
                        }
                    }
                }
            }
    ) {
        TableRow(seg.headerCells, cols, true)
        // 表头下细分隔线
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E7EB)))
        seg.rows.forEachIndexed { index, row ->
            // 除第一行正文外，每行正文之间都画一条细分隔线，使整个表格呈闭合网格
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E7EB)))
            }
            TableRow(row, cols, false)
        }
    }
}

@Composable
private fun TableRow(cells: List<TableCell>, cols: Int, isHeader: Boolean) {
    // 单元格文本已在解析时清洗（去反引号/加粗标记/HTML 标签与实体）；此处按单元格自身样式渲染
    val padded = cells + List((cols - cells.size).coerceAtLeast(0)) { TableCell("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isHeader) Color(0xFFF3F4F6) else Color(0xFFFFFFFF))
    ) {
        for (cell in padded) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(if (cell.bgColor != null) Modifier.background(cell.bgColor) else Modifier)
            ) {
                Text(
                    cell.text,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = cell.color ?: if (isHeader) Color(0xFF1F2937) else Color(0xFF374151),
                    fontWeight = if (cell.bold || isHeader) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** 表格单元格显示去标记：去掉内联反引号与 ** 加粗标记，保持干净文本 */
private fun cleanTableCell(s: String): String =
    s.replace("`", "").replace("**", "").trim()

/**
 * 分享给好友：底部上滑的选择卡片（ModalBottomSheet）。
 * 支持搜索；包含「好友」与「群聊」两类目标，点击即回调其会话 id、名称与是否群聊。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareToFriendSheet(onDismiss: () -> Unit, onPick: (Long, String, Boolean) -> Unit) {
    var friends by remember { mutableStateOf<List<UserInfo>?>(null) }
    var groups by remember { mutableStateOf<List<ConversationInfo>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val fr = ChatRepository.getFriends()
        val gc = ChatRepository.getConversations()
        if (fr.success && fr.data != null) friends = fr.data
        if (gc.success && gc.data != null) groups = gc.data!!.filter { it.id < 0 } // 群会话 id 为负
        if ((fr.success && fr.data != null) == false && (gc.success && gc.data != null) == false) loadError = true
    }

    val q = query.trim()
    val showFriends = (friends ?: emptyList()).filter { q.isEmpty() || it.username.contains(q, ignoreCase = true) }
    val showGroups = (groups ?: emptyList()).filter { q.isEmpty() || it.username.contains(q, ignoreCase = true) }
    val loaded = friends != null || groups != null

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Text(
                "分享给好友 / 群聊",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1F2937),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            )
            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索好友 / 群聊", fontSize = 14.sp, color = Color(0xFF9CA3AF)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))

            if (!loaded) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (loadError) {
                        Text("加载失败", fontSize = 13.sp, color = Color(0xFF6B7280))
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = Color(0xFF1E40AF)
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (showFriends.isNotEmpty()) {
                        item(key = "friends_header") {
                            Text(
                                "好友",
                                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = Color(0xFF6B7280),
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp)
                            )
                        }
                        items(showFriends, key = { "f_${it.id}" }) { f ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(f.id, f.username, false) }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(userId = f.id, userName = f.username, size = 38.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(f.username, fontSize = 15.sp, color = Color(0xFF1F2937), maxLines = 1)
                            }
                        }
                    }
                    if (showGroups.isNotEmpty()) {
                        item(key = "groups_header") {
                            Text(
                                "群聊",
                                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = Color(0xFF6B7280),
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp)
                            )
                        }
                        items(showGroups, key = { "g_${it.id}" }) { g ->
                            // 群会话 id 为负：internalGroupId = -convId - 1000
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(g.id, g.username, true) }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GroupAvatar(internalGroupId = -g.id - 1000L, groupName = g.username, size = 38.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(g.username, fontSize = 15.sp, color = Color(0xFF1F2937), maxLines = 1)
                            }
                        }
                    }
                    if (showFriends.isEmpty() && showGroups.isEmpty()) {
                        item(key = "empty") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("无匹配结果", fontSize = 13.sp, color = Color(0xFF6B7280))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 纯文本分段（AI 扁平气泡内普通文本）——供 decomposed 单元格与内联渲染复用，行为与改造前一致。 */
@Composable
internal fun AiTextSegment(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    defaultColor: Color,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    selectable: Boolean,
    onBubbleTap: () -> Unit,
    onBubbleLongPress: () -> Unit,
    onAtMentionClick: (String) -> Unit,
    isGroup: Boolean,
    groupMemberNames: Set<String>,
    markdown: Boolean = false
) {
    if (text.isEmpty()) return
    if (markdown) {
        // AI 对话专属：解析 Markdown 标题（#~######）+ 段落，段落内由 UrlText 处理加粗
        val blocks = remember(text) { parseMarkdownBlocks(text) }
        Column {
            for ((level, content) in blocks) {
                if (level == -1) {
                    // 「总结」：加粗但字号不变，与正文同尺寸
                    Text(
                        content, fontSize = fontSize, fontWeight = FontWeight.Bold, color = defaultColor,
                        lineHeight = lineHeight,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                } else if (level > 0) {
                    val hSize = when (level) { 1 -> 22f; 2 -> 20f; 3 -> 18f; 4 -> 16f; 5 -> 15f; else -> 14f }.sp
                    Text(
                        content, fontSize = hSize, fontWeight = FontWeight.Bold, color = defaultColor,
                        lineHeight = (hSize.value + 3f).sp,
                        modifier = Modifier.padding(top = if (level <= 2) 6.dp else 3.dp, bottom = 2.dp)
                    )
                } else if (content.isNotEmpty()) {
                    UrlText(
                        text = content,
                        fontSize = fontSize,
                        defaultColor = defaultColor,
                        lineHeight = lineHeight,
                        selectable = selectable,
                        clickable = true,
                        onClick = { if (!selectable) onBubbleTap() },
                        onLongPress = { if (!selectable) onBubbleLongPress() },
                        onAtMentionClick = onAtMentionClick,
                        isGroup = isGroup,
                        groupMemberNames = groupMemberNames,
                        markdown = true
                    )
                }
            }
        }
        return
    }
    UrlText(
        text = text,
        fontSize = fontSize,
        defaultColor = defaultColor,
        lineHeight = lineHeight,
        selectable = selectable,
        clickable = true,
        onClick = { if (!selectable) onBubbleTap() },
        onLongPress = { if (!selectable) onBubbleLongPress() },
        onAtMentionClick = onAtMentionClick,
        isGroup = isGroup,
        groupMemberNames = groupMemberNames
    )
}

/** Markdown 块解析：返回 (标题级别 or 0=段落, 内容) 列表。0=普通段落（保留多行），1..6=对应级别标题，-1=「总结」加粗小标题（字号不变）。 */
private fun parseMarkdownBlocks(text: String): List<Pair<Int, String>> {
    val out = mutableListOf<Pair<Int, String>>()
    val headerRe = Regex("^(#{1,6})\\s+(.*)$")
    val sb = StringBuilder()
    for (raw in text.split("\n")) {
        val m = headerRe.find(raw)
        if (m != null) {
            if (sb.isNotEmpty()) { out.add(0 to sb.toString()); sb.clear() }
            out.add(m.groupValues[1].length to m.groupValues[2].trim())
        } else {
            val t = raw.trim()
            // 单独成行的「总结」：加粗标出、但字号不必放大
            if (t == "总结" || t.startsWith("总结：") || t.startsWith("总结:")) {
                if (sb.isNotEmpty()) { out.add(0 to sb.toString()); sb.clear() }
                out.add(-1 to raw.trim())
            } else {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(raw)
            }
        }
    }
    if (sb.isNotEmpty()) out.add(0 to sb.toString())
    return out
}

/**
 * 开启围栏检测（基于行）：行首（可含前导空白）出现 >=3 个连续反引号即视为开启围栏，
 * 其后（trim 后）为可选语言。非行首的 ` ` ``` `（如正文里的多反引号/内联代码）一律不开启代码块，
 * 避免把外部文本错误切片。
 * @return (围栏长度, 语言)；非围栏行返回 null。
 */
private fun matchOpeningFence(line: String): Pair<Int, String>? {
    var i = 0
    val n = line.length
    while (i < n && (line[i] == ' ' || line[i] == '\t')) i++
    val start = i
    while (i < n && line[i] == '`') i++
    val run = i - start
    if (run < 3) return null
    return run to line.substring(i).trim()
}

/**
 * 闭合围栏检测（基于行）：行首（可含前导空白）的反引号连续段长度 >= 开启围栏长度，
 * 且该连续段之后只允许空白字符。内容内部出现的 1~2 个反引号永远不构成闭合。
 */
private fun matchClosingFence(line: String, fenceLen: Int): Boolean {
    var i = 0
    val n = line.length
    while (i < n && (line[i] == ' ' || line[i] == '\t')) i++
    val start = i
    while (i < n && line[i] == '`') i++
    if (i - start < fenceLen) return false
    while (i < n) { if (line[i] != ' ' && line[i] != '\t') return false; i++ }
    return true
}

/**
 * 流式增量 markdown 段解析器：对不断增长的流式文本，只扫描每次新增的尾部，
 * 已定稿段（代码块、其前的文本/表格）被复用，避免每 token 全量重扫整段字符串造成卡顿与退化成纯文本。
 * 代码块按「行」判定：开启围栏必须是行首（可含空白）>=3 反引号，闭合围栏必须是行首反引号 run
 * >= 开启围栏长度且其后仅空白。代码块内部内容 verbatim（不做任何 markdown 重解析），
 * 其中的内联反引号永不终止代码块，也永不把外部文本切片。
 * 未闭合代码块在闭合围栏行到达前即已作为 CodeSeg 实时渲染，闭合后方正常落定。
 */
private class StreamingMarkdownParser {
    private val committed = mutableListOf<MsgSeg>()   // 已定稿段（在代码块边界之后固定下来，后续不再变化）
    private val textBuf = StringBuilder()             // 尾部的已完成普通文本行（渲染时再拆分表格）
    private val pending = StringBuilder()             // 尚未判定的「行」缓冲：可能成为围栏行/代码行/普通文本行
    private var openCode: CodeFence? = null           // 正在输出、尚未闭合的代码块（实时显示）
    private var covered = 0                           // 已消费的输入字符数

    private class CodeFence(val lang: String, val fenceLen: Int, val sb: StringBuilder)

    fun advance(text: String): List<MsgSeg>? {
        if (text.length < covered) { // 文本被替换/缩短（编辑、重生成）→ 整体重建
            committed.clear(); textBuf.setLength(0); pending.setLength(0); openCode = null; covered = 0
        }
        // 只把新增的字符追加进 pending（已 over 的字符已被分派到 committed/textBuf/openCode，不会被再次扫描）
        pending.append(text, covered, text.length)
        covered = text.length
        processCompleteLines()
        return toDisplay()
    }

    /** 逐「完整行」（含换行）处理；末尾不完整的那一行暂留在 pending，待后续增量到达时再判定。 */
    private fun processCompleteLines() {
        while (true) {
            val raw = pending.toString()
            val nl = raw.indexOf('\n')
            if (nl < 0) break               // 没有完整行（可能只有不完整的末行）
            val line = if (nl > 0 && raw[nl - 1] == '\r') raw.substring(0, nl - 1) else raw.substring(0, nl)
            pending.delete(0, nl + 1)
            val oc = openCode
            if (oc != null) {
                // 代码块内：仅当整行是闭合围栏才关闭，否则整行 verbatim 追加进代码内容
                if (matchClosingFence(line, oc.fenceLen)) {
                    committed.add(MsgSeg.CodeSeg(oc.lang, oc.sb.toString()))
                    openCode = null
                } else {
                    if (oc.sb.isNotEmpty()) oc.sb.append('\n')
                    oc.sb.append(line)
                }
            } else {
                val f = matchOpeningFence(line)
                if (f != null) {
                    commitTextBuf()         // 围栏前的文本先行定稿，保持段序
                    openCode = CodeFence(f.second.ifEmpty { "code" }, f.first, StringBuilder())
                } else {
                    if (textBuf.isNotEmpty()) textBuf.append('\n')
                    textBuf.append(line)
                }
            }
        }
    }

    private fun commitTextBuf() {
        if (textBuf.isEmpty()) return
        val buf = textBuf.toString()
        textBuf.setLength(0)
        val tmp = mutableListOf<MsgSeg>()
        if (segmentableText(buf) && splitTextWithTables(buf, tmp)) committed.addAll(tmp)
        else committed.add(MsgSeg.TextSeg(buf))
    }

    private fun toDisplay(): List<MsgSeg>? {
        if (committed.isEmpty() && textBuf.isEmpty() && openCode == null && pending.isEmpty()) return null
        val list = ArrayList<MsgSeg>(committed.size + 3)
        list.addAll(committed)
        val oc = openCode
        if (oc != null) {
            // 代码块模式：可见代码 = 已完成代码行 + 末尾未完成行
            val tail = pending.toString()
            val code = when {
                tail.isEmpty() -> oc.sb.toString()
                oc.sb.isEmpty() -> tail
                else -> oc.sb.toString() + "\n" + tail
            }
            list.add(MsgSeg.CodeSeg(oc.lang, code))
        } else {
            appendTextRun(list)
        }
        return list
    }

    private fun appendTextRun(list: MutableList<MsgSeg>) {
        // 普通文本模式：可见文本 = 已完成文本行 + 末尾未完成行
        val tail = pending.toString()
        val combined = when {
            tail.isEmpty() -> textBuf.toString()
            textBuf.isEmpty() -> tail
            else -> textBuf.toString() + "\n" + tail
        }
        if (combined.isEmpty()) return
        val tmp = mutableListOf<MsgSeg>()
        if (segmentableText(combined) && splitTextWithTables(combined, tmp)) list.addAll(tmp)
        else list.add(MsgSeg.TextSeg(combined))
    }
}

/** AI 扁平气泡「思考过程」折叠块——供 decomposed 的 chrome 单元格与内联渲染复用，保证行为/样式一致。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AiReasoningBlock(
    reasoningText: String,
    msgId: Long,
    expandedReasoningMsgId: Long,
    selectable: Boolean,
    onReasoningToggled: (Boolean) -> Unit,
    onBubbleLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** 流式中的消息:最后一段思考仍在生成,按钮显示「思考中」;其余段与已完成消息显示「思考过程」 */
    streaming: Boolean = false
) {
    // 多段思考：reasoningText 内可能用哨兵拼接了多段，逐段渲染为独立折叠块（思考1/2/3…）
    val segments = AiChatManager.splitReasoningSegments(reasoningText)
    if (segments.isEmpty()) return
    val segmentCount = segments.size
    val single = segmentCount == 1
    val firstAutoExpanded = remember(msgId, expandedReasoningMsgId) { msgId == expandedReasoningMsgId }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEachIndexed { index, seg ->
            val tag = if (streaming && index == segmentCount - 1) "思考中" else "思考过程"
            val autoExpand = single && firstAutoExpanded
            var reasoningExpanded by remember(msgId, index, segmentCount) { mutableStateOf(autoExpand) }
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF3F4F6))
                    .combinedClickable(
                        onClick = {
                            reasoningExpanded = !reasoningExpanded
                            onReasoningToggled(reasoningExpanded)
                        },
                        onLongClick = { if (!selectable) onBubbleLongPress() }
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    if (reasoningExpanded) "$tag ▾" else "$tag ▸",
                    fontSize = 11.sp, color = Color(0xFF6B7280)
                )
            }
            if (reasoningExpanded) {
                Text(
                    seg,
                    fontSize = 12.sp, lineHeight = 18.sp,
                    color = Color(0xFF6B7280),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    modifier = Modifier
                        .padding(top = 4.dp, bottom = 4.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (!selectable) onBubbleLongPress() }
                        )
                )
            }
        }
    }
}

/**
 * AI 正文的「时间轴交错」渲染：把消息 text 中内联嵌的思考段（REASON_INLINE_OPEN/CLOSE 哨兵）与
 * 工具调用状态段（TOOL_INLINE_OPEN/CLOSE 哨兵）按其发生顺序与正文交错输出——
 * 思考/工具状态出现在它前面的正文之后，而不是一股脑堆到顶部或末尾。
 * 若 text 中没有内联哨兵（旧消息/未开启思考），则回退为旧的「思考块置顶 + 正文」布局。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AiInterleavedContent(
    text: String,
    reasoningText: String,
    msgId: Long,
    expandedReasoningMsgId: Long,
    selectable: Boolean,
    onReasoningToggled: (Boolean) -> Unit,
    onBubbleLongPress: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    defaultColor: Color,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    onBubbleTap: () -> Unit,
    onAtMentionClick: (String) -> Unit,
    isGroup: Boolean,
    groupMemberNames: Set<String>,
    onBackToTop: () -> Unit,
    streamKey: Long
) {
    val blocks = AiChatManager.splitInlineBlocksV2(text)
    val hasSentinel = blocks.any { it !is AiChatManager.InlineBlock.Text }
    if (!hasSentinel) {
        // 回退：旧的「思考置顶 + 正文」布局
        AiReasoningBlock(
            reasoningText = reasoningText,
            msgId = msgId,
            expandedReasoningMsgId = expandedReasoningMsgId,
            selectable = selectable,
            onReasoningToggled = onReasoningToggled,
            onBubbleLongPress = onBubbleLongPress,
            streaming = msgId == expandedReasoningMsgId
        )
        if (reasoningText.isNotEmpty()) Spacer(Modifier.height(4.dp))
        MessageTextWithCode(
            text = text,
            fontSize = fontSize,
            defaultColor = defaultColor,
            lineHeight = lineHeight,
            selectable = selectable,
            onBubbleTap = onBubbleTap,
            onBubbleLongPress = onBubbleLongPress,
            onAtMentionClick = onAtMentionClick,
            isGroup = isGroup,
            groupMemberNames = groupMemberNames,
            onBackToTop = onBackToTop,
            markdown = true,
            streamKey = streamKey
        )
        return
    }
    // 交错渲染：正文段、思考段、工具状态段按原始顺序排列
    var thinkingIndex = 0
    // 已内联进正文的思考块数量：只有「最新那一块」自动展开（与旧置顶折叠块体验一致）
    val thinkingCount = blocks.count { it is AiChatManager.InlineBlock.Thinking }
    // 正在生成中的消息，其本轮思考还没被内联进正文（内联发生在该轮结束时）。
    // 正文一旦出现哨兵，旧的置顶折叠块就不再渲染，于是多轮任务里「思考看到一半突然没了」。
    // 这里把 reasoningText 中「尚未内联的那一段」在气泡末尾实时渲染出来，补齐这段空窗。
    val liveSegments = AiChatManager.splitReasoningSegments(reasoningText)
    val liveThinking = if (liveSegments.size > thinkingCount) {
        liveSegments.drop(thinkingCount).joinToString("\n\n")
    } else ""
    Column {
        blocks.forEach { block ->
            when (block) {
                is AiChatManager.InlineBlock.Text -> {
                    MessageTextWithCode(
                        text = block.content,
                        fontSize = fontSize,
                        defaultColor = defaultColor,
                        lineHeight = lineHeight,
                        selectable = selectable,
                        onBubbleTap = onBubbleTap,
                        onBubbleLongPress = onBubbleLongPress,
                        onAtMentionClick = onAtMentionClick,
                        isGroup = isGroup,
                        groupMemberNames = groupMemberNames,
                        onBackToTop = onBackToTop,
                        markdown = true,
                        streamKey = streamKey
                    )
                }
                is AiChatManager.InlineBlock.Thinking -> {
                    thinkingIndex++
                    InlineThinkingChip(
                        reasoning = block.content,
                        thinkingIndex = thinkingIndex,
                        msgId = msgId + thinkingIndex,
                        selectable = selectable,
                        onReasoningToggled = onReasoningToggled,
                        onBubbleLongPress = onBubbleLongPress,
                        // 生成中的消息：最新那块思考默认展开，避免「上一轮思考在切换瞬间被收起来」
                        autoExpand = msgId == expandedReasoningMsgId && thinkingIndex == thinkingCount
                    )
                    Spacer(Modifier.height(4.dp))
                }
                is AiChatManager.InlineBlock.Tool -> {
                    ToolStatusInline(tool = block.tool, state = block.state, record = block.record)
                    Spacer(Modifier.height(4.dp))
                }
                is AiChatManager.InlineBlock.AskRecord -> {
                    AskRecordBlock(content = block.content, selectable = selectable, onBubbleLongPress = onBubbleLongPress)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        // 尚在思考中（未内联）的那一段：实时展示在末尾，生成中就自动展开，让用户一直看得到进度
        if (liveThinking.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            InlineThinkingChip(
                reasoning = liveThinking,
                thinkingIndex = thinkingCount + 1,
                msgId = msgId + 100_000,
                selectable = selectable,
                onReasoningToggled = onReasoningToggled,
                onBubbleLongPress = onBubbleLongPress,
                autoExpand = msgId == expandedReasoningMsgId,
                live = true
            )
        }
    }
}

/**
 * 「回答内容」折叠块:ask_user 提问结束后随消息持久化的问答记录。
 * 与思考过程同一视觉语言(灰底标签 + 灰色斜体小字),但**默认收起**——不管在不在
 * 最新消息里都不自动展开,用户点标签才展开看 AI 问了什么、自己答了什么。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AskRecordBlock(content: String, selectable: Boolean, onBubbleLongPress: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFF3F4F6))
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = { if (!selectable) onBubbleLongPress() }
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                if (expanded) "回答内容 ▾" else "回答内容 ▸",
                fontSize = 11.sp, color = Color(0xFF6B7280)
            )
        }
        if (expanded) {
            Text(
                content,
                fontSize = 12.sp, lineHeight = 18.sp,
                color = Color(0xFF6B7280),
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { if (!selectable) onBubbleLongPress() }
                    )
            )
        }
    }
}

/** 内联工具调用状态行（随正文位置出现，state=0 正在调用(蓝) / 1 调用成功(绿) / 2 调用失败(红)）。
 *  ask_user 完成时 record 携带「问/答」明细:状态行显示「向用户提问」,点击展开/收起问答内容。 */
@Composable
private fun ToolStatusInline(tool: String, state: Int, record: String = "") {
    // 多 Agent 元工具:专属文案 + 实时状态(验收轮数 / 并行完成进度)
    val isReview = tool == "agent_review"
    val isFork = tool == "agent_fork"
    val isAgent = isReview || isFork
    val liveStatus by if (isAgent && state == 0) {
        (if (isFork) AiChatManager.AgentForkBus.live else AiChatManager.AgentReviewBus.live).collectAsState()
    } else remember { mutableStateOf<String?>(null) }
    // ask_user 进行中且仍有挂起请求:状态行可点击,重新展开被收起的提问面板(带着上次作答草稿);
    // 面板被收起时右侧追加「继续回答」徽标,提示用户可以点回来
    val askState by if (tool == "ask_user" && state == 0) {
        AiChatManager.AskUserBus.pending.collectAsState()
    } else remember { mutableStateOf<AiChatManager.AskUserBus.Request?>(null) }
    val askHidden by if (tool == "ask_user" && state == 0) {
        AiChatManager.AskUserBus.hidden.collectAsState()
    } else remember { mutableStateOf(true) }
    val askCanResume = askState != null && askHidden
    // ask_user 完成且带问答记录:状态行变「向用户提问」,点击展开明细
    val askDone = tool == "ask_user" && state == 1 && record.isNotBlank()
    var askExpanded by remember(record) { mutableStateOf(false) }
    val text = when {
        isFork && state == 0 -> "并行派发中,多个子执行 Agent 同时干活…"
        isFork && state == 1 -> "并行子任务全部完成"
        isFork -> "并行派发失败"
        isReview && state == 0 -> "验收 Agent 接手中…"
        isReview && state == 1 -> "验收 Agent 核验完成"
        isReview -> "验收 Agent 核验失败"
        tool == "ask_user" && state == 0 -> "正在向用户提问"
        tool == "ask_user" && state == 1 -> if (askDone) "已向用户提问 " + if (askExpanded) "▾" else "▸" else "已向用户提问"
        tool == "ask_user" -> "提问未完成"
        state == 0 -> "正在调用 $tool…"
        state == 1 -> "调用成功 $tool"
        else -> "调用失败 $tool"
    }
    val stateColor = when (state) {
        0 -> Color(0xFF2F6FED)   // 蓝：正在调用
        1 -> Color(0xFF16A34A)   // 绿：调用成功
        else -> Color(0xFFDC2626) // 红：调用失败
    }
    androidx.compose.foundation.layout.Column {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                .then(
                    if (askState != null || askDone) Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        if (askState != null) AiChatManager.AskUserBus.reopen() else askExpanded = !askExpanded
                    } else Modifier
                )
        ) {
            Text(
                text = text,
                color = stateColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            if (askCanResume) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x142F6FED))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("继续回答", fontSize = 11.sp, color = Color(0xFF2F6FED), fontWeight = FontWeight.Medium)
                }
            }
            if (isReview && state == 0) {
                // 跳过审查:点击后验收 Agent 在当前请求结束后立即中止,执行者会收到"用户跳过了验收"并如实告知
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x14DC2626))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { AiChatManager.requestReviewSkip() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("跳过审查", fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium)
                }
            }
        }
        if (askDone && askExpanded) {
            Text(
                text = record,
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
            )
        }
        if (isAgent && state == 0 && !liveStatus.isNullOrBlank()) {
            Text(
                text = liveStatus!!,
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

/** 单个内联思考折叠块（用于时间轴交错布局中分段出现） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InlineThinkingChip(
    reasoning: String,
    thinkingIndex: Int,
    msgId: Long,
    selectable: Boolean,
    onReasoningToggled: (Boolean) -> Unit,
    onBubbleLongPress: () -> Unit,
    autoExpand: Boolean = false,
    /** live=true:该段思考仍在生成中(尚未随本轮结束内联),按钮显示「思考中」;完成后变「思考过程」 */
    live: Boolean = false
) {
    val tag = if (live) "思考中" else "思考过程"
    // 注意：key 只用 msgId，不要用 reasoning——流式阶段思考内容每 30ms 变一次，
    // 拿内容当 key 会把用户刚展开的折叠块反复重置回收起（表现为「思考看一半自己没了」）。
    var reasoningExpanded by remember(msgId) { mutableStateOf(autoExpand) }
    Column {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFF3F4F6))
                .combinedClickable(
                    onClick = {
                        reasoningExpanded = !reasoningExpanded
                        onReasoningToggled(reasoningExpanded)
                    },
                    onLongClick = { if (!selectable) onBubbleLongPress() }
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                if (reasoningExpanded) "$tag ▾" else "$tag ▸",
                fontSize = 11.sp, color = Color(0xFF6B7280)
            )
        }
        if (reasoningExpanded) {
            Text(
                reasoning,
                fontSize = 12.sp, lineHeight = 18.sp,
                color = Color(0xFF6B7280),
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 4.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { if (!selectable) onBubbleLongPress() }
                    )
            )
        }
    }
}
@Composable
private fun MessageTextWithCode(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    defaultColor: Color,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    selectable: Boolean,
    onBubbleTap: () -> Unit,
    onBubbleLongPress: () -> Unit,
    onAtMentionClick: (String) -> Unit,
    isGroup: Boolean,
    groupMemberNames: Set<String>,
    onBackToTop: () -> Unit = {},
    markdown: Boolean = false,
    streamKey: Long = 0
) {
    // AI 对话(markdown)走流式增量解析：只扫描每次新增的尾部并复用已定稿段，代码块无须闭合围栏即实时渲染，
    // 避免对大输出每 token 都全量重扫整段文本（正是流式期间退化为纯文本 + 明显卡顿的根因）。
    // 非 AI 私信/群聊(markdown=false)保持原有「整段一次解析」行为，不受影响。
    val segs: List<MsgSeg>? = if (markdown) {
        val parser = remember(streamKey) { StreamingMarkdownParser() }
        parser.advance(text)
    } else {
        remember(text) { parseMsgSegments(text) }
    }
    // 无代码块 → 与原先一致的 UrlText 渲染
    if (segs == null) {
        AiTextSegment(
            text = text,
            fontSize = fontSize,
            defaultColor = defaultColor,
            lineHeight = lineHeight,
            selectable = selectable,
            onBubbleTap = onBubbleTap,
            onBubbleLongPress = onBubbleLongPress,
            onAtMentionClick = onAtMentionClick,
            isGroup = isGroup,
            groupMemberNames = groupMemberNames,
            markdown = markdown
        )
        return
    }
    Column {
        for (seg in segs) {
            when (seg) {
                is MsgSeg.TextSeg -> {
                    AiTextSegment(
                        text = seg.text,
                        fontSize = fontSize,
                        defaultColor = defaultColor,
                        lineHeight = lineHeight,
                        selectable = selectable,
                        onBubbleTap = onBubbleTap,
                        onBubbleLongPress = onBubbleLongPress,
                        onAtMentionClick = onAtMentionClick,
                        isGroup = isGroup,
                        groupMemberNames = groupMemberNames,
                        markdown = markdown
                    )
                }
                is MsgSeg.CodeSeg -> {
                    Spacer(Modifier.height(4.dp))
                    CodeBlockView(seg.language, seg.code, onBackToTop)
                    Spacer(Modifier.height(4.dp))
                }
                is MsgSeg.TableSeg -> {
                    Spacer(Modifier.height(4.dp))
                    PipeTableView(seg)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(text: String, isMine: Boolean, fromUserId: Long, myAvatar: android.graphics.Bitmap?, displayName: String, showSenderName: Boolean = false, groupConvId: Long = 0, currentUserId: Long = 0, onAvatarClick: (Long, String) -> Unit = { _, _ -> }, onBubbleTap: () -> Unit = {}, onBubbleLongPress: () -> Unit = {}, selectable: Boolean = false, replyToText: String = "", replyToSender: String = "", replyToId: Long = 0, replyIsRevoked: Boolean = false, isHighlighted: Boolean = false, onCapsuleClick: (Long) -> Unit = {}, onAvatarLongPress: (Long, String) -> Unit = { _, _ -> }, onAvatarDoubleTap: (Long, String) -> Unit = { _, _ -> }, onAtMentionClick: (String) -> Unit = {}, isGroup: Boolean = false, groupMemberNames: Set<String> = emptySet(), loadMedia: Boolean = true, mediaType: String = "", mediaUrl: String = "", isUploading: Boolean = false, uploadProgress: Float = 0f, uploadStage: String = "", uploadRatio: Float = 0f, uploadFileName: String = "", onImageClick: (String) -> Unit = {}, onFileClick: (String, String) -> Unit = { _, _ -> }, onFileDownload: (String, String) -> Unit = { _, _ -> }, flashDuration: Int = 0, reasoningText: String = "", msgId: Long = 0, destroyedFlashIds: Set<Long> = emptySet(), onFlashDestroy: (Long) -> Unit = {}, onOpenPostDetail: (Long) -> Unit = {},
    onOpenGroupDetail: (Long) -> Unit = {}, onReasoningToggled: (Boolean) -> Unit = {}, expandedReasoningMsgId: Long = 0,
    thinkingSeconds: Long = 0, totalSeconds: Long = 0,
    aiFlatBubble: Boolean = false,
    manualStopped: Boolean = false,
    aiWaiting: Boolean = false,
    aiProgressHint: String = "",
    onContinueClick: (() -> Unit)? = null,
    onCodeBackToTop: () -> Unit = {},
    onRespondGroupInvite: ((InviteGroupCardData) -> Unit)? = null,
    /** AI 本次会话的文件改动记录(JSON 字符串,空表示无改动):非空时强制渲染「查看所有改动」按钮 */
    aiFileChanges: String = "",
    /** 本条消息累计调用的工具次数(用于底部信息行展示) */
    toolCallCount: Int = 0,
    /** 点击「查看所有改动」回调:null 时不渲染按钮 */
    onViewChanges: (() -> Unit)? = null,
    /** 点击「调用 N 个工具」回调:null 或无记录时不渲染按钮 */
    onToolCalls: (() -> Unit)? = null) {
    val avatarSize = 42.dp
    // ── 分享文件卡片下载流程：点击 → 确认对话框 → SAF 选择保存位置 → 复制 → Toast ──
    // 保持卡片渲染不变，仅替换点击后的打开行为为"下载保存"。
    val shareFileCtx = LocalContext.current
    var pendingShareDownload by remember { mutableStateOf<String?>(null) }
    val shareDownloadLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val srcPath = pendingShareDownload
        pendingShareDownload = null
        if (uri == null || srcPath == null) return@rememberLauncherForActivityResult
        val src = java.io.File(srcPath)
        if (!src.exists()) {
            android.widget.Toast.makeText(shareFileCtx, "文件不存在或已被移除", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        try {
            shareFileCtx.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            android.widget.Toast.makeText(shareFileCtx, "已保存到 ${src.name}", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(shareFileCtx, "保存失败：${e.message ?: "未知错误"}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // 确认下载对话框：确定后调起系统文件管理器选择保存位置/文件名
    pendingShareDownload?.let { dlPath ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingShareDownload = null },
            title = { androidx.compose.material3.Text("下载文件") },
            text = { androidx.compose.material3.Text("是否下载该文件？\n${java.io.File(dlPath).name}") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingShareDownload = null
                    shareDownloadLauncher.launch(java.io.File(dlPath).name)
                }) { androidx.compose.material3.Text("确定") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingShareDownload = null }) {
                    androidx.compose.material3.Text("取消")
                }
            }
        )
    }
    // 媒体 URL 以 MediaStore 为权威单一来源：优先用按 msgId 记录的可达 URL，避免重载时丢失导致空气泡
    val effectiveMediaUrl = if (msgId > 0) (MediaStore.get(msgId)?.url ?: mediaUrl) else mediaUrl
    @Suppress("NAME_SHADOWING")
    val mediaUrl = effectiveMediaUrl
    // 高亮闪烁动画（浅蓝色闪3次）
    val highlightAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
            highlightAnim.snapTo(0f)
            kotlinx.coroutines.delay(50)
            repeat(3) {
                highlightAnim.animateTo(0.4f, androidx.compose.animation.core.tween(200))
                highlightAnim.animateTo(0f, androidx.compose.animation.core.tween(200))
            }
        }
    }

    // 缩短长按判定时长（默认 400ms → 200ms），让气泡/头像长按更容易触发。
    // 用 remember 缓存包装对象，避免每条消息每次重组都新建匿名 ViewConfiguration。
    val customViewConfig = LocalViewConfiguration.current
    val chatViewConfig = remember(customViewConfig) {
        object : ViewConfiguration by customViewConfig {
            override val longPressTimeoutMillis: Long = 200L
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides chatViewConfig) {
    if (isMine) {
        // ── 自己的消息：头像在右，气泡在左 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            // 气泡区（含名字 + 气泡，整体向上偏移，仅限群聊）
            Box(Modifier.widthIn(max = 260.dp)) {
                Column(horizontalAlignment = Alignment.End,
                    modifier = if (groupConvId < 0) Modifier.offset(y = (-10).dp) else Modifier) {
                    // 自己的群聊消息也显示名字（靠右），徽章在名字左边
                    if (showSenderName && displayName.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = displayName,
                                fontSize = 11.sp,
                                color = Color(0xFF6B7280),
                                maxLines = 1,
                                modifier = Modifier.padding(bottom = 0.dp)
                            )
                        }
                    }
                    if (mediaType == "image" || mediaType == "jpg" || mediaType == "png" || mediaType == "gif" || mediaType == "jpeg") {
                        if (isUploading) {
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF374151)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        progress = { uploadProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.size(36.dp),
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        trackColor = Color(0x4DFFFFFF)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "${(uploadProgress * 100).toInt()}%",
                                        fontSize = 13.sp, color = Color.White,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        } else if (flashDuration > 0 && msgId !in destroyedFlashIds) {
                            var showFlashViewer by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1F2937))
                                    .combinedClickable(
                                        onClick = { showFlashViewer = true },
                                        onLongClick = { onBubbleLongPress() },
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    FlashIcon(iconSize = 32.dp)
                                    Spacer(Modifier.height(4.dp))
                                    Text("闪照", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                                    Text("点击查看", fontSize = 10.sp, color = Color(0xFF6B7280))
                                }
                            }
                            if (showFlashViewer) {
                                FlashViewerDialog(
                                    imageUrl = mediaUrl, durationSec = flashDuration,
                                    onDestroy = { onFlashDestroy(msgId) },
                                    onDismiss = { showFlashViewer = false }
                                )
                            }
                        } else if (flashDuration > 0) {
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF374151)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("图片已销毁", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                            }
                        } else {
                        MediaImageView(
                            url = mediaUrl,
                            isMine = isMine,
                            loadMedia = loadMedia,
                            highlightAnim = highlightAnim,
                            onImageClick
                            = onImageClick,
                            onBubbleLongPress = onBubbleLongPress
                        )
                        }
                        } else if (mediaType == "video" || mediaType == "mp4") {
                        if (isUploading) {
                            val ratio = if (uploadRatio > 0f) uploadRatio.coerceIn(0.5f, 2.4f) else (16f / 9f)
                            val compressing = uploadStage == "compressing"
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(ratio)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1A1A1A)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        progress = { uploadProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.size(36.dp),
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        trackColor = Color(0x4DFFFFFF)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "${(uploadProgress * 100).toInt()}%",
                                        fontSize = 13.sp, color = Color.White,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(if (compressing) "压缩中..." else "上传中...", fontSize = 11.sp, color = Color(0xCCFFFFFF))
                                }
                            }
                        } else {
                        // 视频消息：不带气泡框，显示缩略图（点击应用内播放）
                        Box(
                            modifier = Modifier
                                .widthIn(max = 200.dp).heightIn(max = 280.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = {
                                        if (mediaUrl.isNotEmpty()) onImageClick(mediaUrl) else onBubbleTap()
                                    },
                                    onLongClick = { onBubbleLongPress() },
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth().heightIn(max = 280.dp)
                                    .background(Color(0xFF1F2937))
                                    .clip(RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (loadMedia) VideoThumbnailView(videoUrl = mediaUrl, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
                                else Box(Modifier.fillMaxWidth().height(120.dp).background(Color(0xFF1F2937).copy(alpha = 0.3f)), contentAlignment = Alignment.Center) { Text("视频", fontSize = 13.sp, color = Color(0xFF9CA3AF)) }
                            }
                        }
                        }
                    } else if (isUploading && mediaType == "file") {
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { onBubbleLongPress() },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                        ) {
                            ChatFileUploadCard(
                                fileName = uploadFileName,
                                progress = uploadProgress,
                                stage = uploadStage
                            )
                        }
                    } else if ((mediaUrl.isNotEmpty() && text.isEmpty() && mediaType != "voice" && mediaType != "transfer") || mediaType == "file") {
                        val fileUrl = mediaUrl
                        val fileName = fileUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "[文件]" }
                        val fileExt = fileName.substringAfterLast('.', "").lowercase()
                        // 诊断：文件卡片渲染决策点，用于定位「会话预览见[文件]但气泡内无卡片」的数据
                        android.util.Log.d("AIFileCard", "render msgId=$msgId mediaType=$mediaType url='$mediaUrl' textLen=${text.length}")
                        ChatFileCard(
                            url = fileUrl,
                            fileName = fileName,
                            ext = fileExt,
                            onClick = { onFileClick(fileUrl, fileName) },
                            onDownload = { onFileDownload(fileUrl, fileName) },
                            onBubbleLongPress = onBubbleLongPress,
                            showBorder = true
                        )
                    } else if (com.aurora.chat.ui.chat.isLocationCardMessage(text)) {
                        val locCtx = androidx.compose.ui.platform.LocalContext.current
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    val loc = com.aurora.chat.ui.chat.parseLocationCard(text)
                                    if (loc != null && loc.lat != 0.0 && loc.lng != 0.0) {
                                        try {
                                            val geoUri = android.net.Uri.parse("geo:${loc.lat},${loc.lng}?q=${loc.lat},${loc.lng}")
                                            val mapIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, geoUri)
                                            locCtx.startActivity(android.content.Intent.createChooser(mapIntent, "选择导航应用"))
                                        } catch (_: Exception) {
                                            android.widget.Toast.makeText(locCtx, "没有可用的导航应用", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onLongClick = { onBubbleLongPress() },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                        ) {
                            com.aurora.chat.ui.chat.LocationCard(
                                text = text
                            )
                        }
                    } else if (com.aurora.chat.ui.chat.isShareCardMessage(text)) {
                        com.aurora.chat.ui.chat.ShareCard(
                            text = text,
                            isMine = true,
                            onPostClick = onOpenPostDetail,
                            onGroupClick = onOpenGroupDetail,
                            onFileClick = { fp -> pendingShareDownload = fp },
                            onBubbleLongPress = onBubbleLongPress
                        )
                    } else if (isPrivacyRequestCard(text) || isPrivacyResponseCard(text)) {
                        PrivacyCard(text = text, isMine = true)
                    } else if (isInviteGroupCard(text)) {
                        InviteGroupCard(text = text, isMine = true, onRespondClick = onRespondGroupInvite, onBubbleLongPress = onBubbleLongPress)
                    } else {
                        // 自己消息的文本气泡（绿色背景）
                        Box(
                            modifier = Modifier
                                .widthIn(max = 260.dp).heightIn(min = avatarSize).wrapContentHeight()
                                .clip(RoundedCornerShape(12.dp)).background(Color(0xFF95EC69))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .then(if (highlightAnim.value > 0.01f) Modifier.drawWithContent {
                                    drawContent()
                                    drawRect(Color(0xFFBFDBFE).copy(alpha = highlightAnim.value), size = size)
                                } else Modifier),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (selectable) {
                                SelectionContainer {
                                    Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xFF000000))
                                }
                            } else {
                                // 长文本折叠：超过 6 行显示"展开"按钮
                                var isExpanded by remember { mutableStateOf(false) }
                                val maxCollapsedLines = 6
                                Column {
                                    UrlText(
                                        text = text,
                                        fontSize = 15.sp,
                                        defaultColor = Color(0xFF000000),
                                        lineHeight = 22.sp,
                                        selectable = false,
                                        clickable = true,
                                        onClick = { if (!selectable) onBubbleTap() },
                                        onLongPress = { if (!selectable) onBubbleLongPress() },
                                        onAtMentionClick = onAtMentionClick,
                                        isGroup = isGroup,
                                        groupMemberNames = groupMemberNames,
                                        maxLines = if (isExpanded) Int.MAX_VALUE else maxCollapsedLines
                                    )
                                    // 如果文本被截断，显示展开按钮
                                    if (text.length > 120 && !isExpanded) {
                                        Text(
                                            "展开",
                                            fontSize = 13.sp,
                                            color = Color(0xFF576B95),
                                            modifier = Modifier.clickable { isExpanded = true }
                                                .padding(top = 2.dp)
                                        )
                                    }
                                    if (isExpanded && text.length > 120) {
                                        Text(
                                            "收起",
                                            fontSize = 13.sp,
                                            color = Color(0xFF576B95),
                                            modifier = Modifier.clickable { isExpanded = false }
                                                .padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 引用胶囊（可点击跳到原文）
                    if (replyToText.isNotEmpty() || replyIsRevoked) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 260.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFE5E7EB))
                                .clickable { if (replyToId > 0) onCapsuleClick(replyToId) }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (replyIsRevoked) "引用了一条已撤回消息" else "${replyToSender}:${if (replyToText.isNotEmpty()) replyToText else "[消息]"}",
                                fontSize = 10.sp, color = Color(0xFF6B7280),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            // 头像（无波纹）
            val localView = androidx.compose.ui.platform.LocalView.current
            val shakeOff = remember { androidx.compose.animation.core.Animatable(0f) }
            val shakeScope = rememberCoroutineScope()
            val myMod = Modifier
                .size(avatarSize)
                .clip(RoundedCornerShape(10.dp))
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    rotationZ = shakeOff.value
                }
                .pointerInput(fromUserId) {
                    detectTapGestures(
                        onTap = { onAvatarClick(fromUserId, "我") },
                        onDoubleTap = {
                            localView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                            shakeScope.launch {
                                shakeOff.animateTo(8f, tween(50))
                                shakeOff.animateTo(-8f, tween(50))
                                shakeOff.animateTo(5f, tween(40))
                                shakeOff.animateTo(-5f, tween(40))
                                shakeOff.animateTo(0f, tween(40))
                            }
                            onAvatarDoubleTap(fromUserId, "我")
                        },
                        onLongPress = {
                            localView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            onAvatarLongPress(fromUserId, "我")
                        }
                    )
                }
            // 右侧头像：根据 fromUserId 决定显示谁的头像（reverseHistory 翻过后可能不是"我"）
            val rightAvatarMod = Modifier
                .size(avatarSize)
                .clip(RoundedCornerShape(10.dp))
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    rotationZ = shakeOff.value
                }
                .pointerInput(fromUserId) {
                    detectTapGestures(
                        onTap = { onAvatarClick(fromUserId, if (fromUserId == currentUserId) "我" else displayName) },
                        onDoubleTap = {
                            localView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                            shakeScope.launch {
                                shakeOff.animateTo(8f, tween(50))
                                shakeOff.animateTo(-8f, tween(50))
                                shakeOff.animateTo(5f, tween(40))
                                shakeOff.animateTo(-5f, tween(40))
                                shakeOff.animateTo(0f, tween(40))
                            }
                            onAvatarDoubleTap(fromUserId, if (fromUserId == currentUserId) "我" else displayName)
                        },
                        onLongPress = {
                            localView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            onAvatarLongPress(fromUserId, if (fromUserId == currentUserId) "我" else displayName)
                        }
                    )
                }
            when {
                fromUserId == currentUserId && aiFlatBubble -> {
                    // AI 对话内：我发消息不显示自己头像（与 AI 侧一致），其余逻辑不受影响
                }
                fromUserId == currentUserId -> {
                    if (myAvatar != null) {
                        Image(bitmap = myAvatar.asImageBitmap(), contentDescription = "我的头像",
                            modifier = rightAvatarMod, contentScale = ContentScale.Fit)
                    } else {
                        Image(painter = painterResource(R.drawable.ic_profile), contentDescription = "我的头像",
                            modifier = rightAvatarMod, contentScale = ContentScale.Fit)
                    }
                }
                fromUserId == AI_CHAT_ID -> {
                    val customAiAvatar = AiAvatarState.bitmap
                    if (customAiAvatar != null) {
                        Image(bitmap = customAiAvatar.asImageBitmap(), contentDescription = "AI",
                            modifier = rightAvatarMod, contentScale = ContentScale.Crop)
                    } else {
                        AiDefaultAvatar(modifier = rightAvatarMod)
                    }
                }
                else -> {
                    UserAvatar(userId = fromUserId, userName = displayName.ifEmpty { "?" },
                        size = avatarSize, modifier = rightAvatarMod)
                }
            }
        }
    } else {
        // AI 对话：不带头像、不带气泡框，正文直接铺满纯白背景
        if (aiFlatBubble && fromUserId == AI_CHAT_ID) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .then(if (highlightAnim.value > 0.01f) Modifier.drawWithContent {
                        drawContent()
                        drawRect(Color(0xFFBFDBFE).copy(alpha = highlightAnim.value), size = size)
                    } else Modifier)
            ) {
                // 可折叠的思考过程（仅 AI 有）。思考段已由发送侧嵌入 text 内联哨兵，
                // 渲染时按时间先后与正文交错（而非一股脑堆在顶部）。空窗/媒体分支独立处理。
                if (aiWaiting) {
                    if (aiProgressHint.isNotBlank()) {
                        // 任务守护进度提示：任务进行中·第N轮 / 正在收尾交付（轻量 UI）
                        Text(
                            text = aiProgressHint,
                            fontSize = 12.sp,
                            color = Color(0xFF9CA3AF),
                            maxLines = 1
                        )
                    } else if (text.isBlank()) {
                        // 动态等待指示:点号轮流(. .. ...) + 右侧实时等待时长(秒)
                        val waitStart = remember { android.os.SystemClock.elapsedRealtime() }
                        var waitTick by remember { mutableStateOf(0) }
                        LaunchedEffect(Unit) {
                            while (true) { delay(500); waitTick++ }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "等待模型响应" + ".".repeat(waitTick % 3 + 1),
                                fontSize = 12.sp,
                                color = Color(0xFF9CA3AF),
                                maxLines = 1
                            )
                            // 秒数贴在文案右侧一小段距离,既不贴字也不顶到最右
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "${(android.os.SystemClock.elapsedRealtime() - waitStart) / 1000}s",
                                fontSize = 12.sp,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                    }
                } else if (mediaUrl.isNotEmpty() && (mediaType == "image" || mediaType == "jpg" || mediaType == "png" || mediaType == "gif" || mediaType == "jpeg")) {
                    // AI 生成的图片：复用已有的 ChatMediaImage 媒体管线渲染真实图片（非气泡容器，保持扁平样式）
                    MediaImageView(
                        url = mediaUrl,
                        isMine = isMine,
                        loadMedia = loadMedia,
                        highlightAnim = highlightAnim,
                        onImageClick = onImageClick,
                        onBubbleLongPress = onBubbleLongPress
                    )
                } else if (mediaUrl.isNotEmpty() && (mediaType == "video" || mediaType == "mp4")) {
                    // AI 生成的视频：复用已有缩略图渲染（点击应用内播放）
                    Box(
                        modifier = Modifier
                            .widthIn(max = 200.dp).heightIn(max = 280.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = { if (mediaUrl.isNotEmpty()) onImageClick(mediaUrl) else onBubbleTap() },
                                onLongClick = { onBubbleLongPress() },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth().heightIn(max = 280.dp)
                                .background(Color(0xFF1F2937))
                                .clip(RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (loadMedia) VideoThumbnailView(videoUrl = mediaUrl, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
                            else Box(Modifier.fillMaxWidth().height(120.dp).background(Color(0xFF1F2937).copy(alpha = 0.3f)), contentAlignment = Alignment.Center) { Text("视频", fontSize = 13.sp, color = Color(0xFF9CA3AF)) }
                        }
                    }
                } else if (mediaType == "file") {
                    // AI_GetFile 插入的本地文件卡片（file:// 本地路径）：直接复用 ChatFileCard 渲染。
                    // 此前该分支缺失，file 消息落入 AiInterleavedContent(空文本) 被吞掉，导致卡片不显示。
                    val fileUrl = mediaUrl
                    val fileName = fileUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "[文件]" }
                    val fileExt = fileName.substringAfterLast('.', "").lowercase()
                    ChatFileCard(
                        url = fileUrl,
                        fileName = fileName,
                        ext = fileExt,
                        onClick = { onFileClick(fileUrl, fileName) },
                        onDownload = { onFileDownload(fileUrl, fileName) },
                        onBubbleLongPress = onBubbleLongPress,
                        showBorder = true
                    )
                } else {
                    AiInterleavedContent(
                        text = text,
                        reasoningText = reasoningText,
                        msgId = msgId,
                        expandedReasoningMsgId = expandedReasoningMsgId,
                        selectable = selectable,
                        onReasoningToggled = onReasoningToggled,
                        onBubbleLongPress = onBubbleLongPress,
                        fontSize = 15.sp,
                        defaultColor = Color(0xFF1F2937),
                        lineHeight = 22.sp,
                        onBubbleTap = onBubbleTap,
                        onAtMentionClick = onAtMentionClick,
                        isGroup = isGroup,
                        groupMemberNames = groupMemberNames,
                        onBackToTop = onCodeBackToTop,
                        streamKey = msgId
                    )
                }
                // 「查看所有改动」按钮行:只要本条 AI 消息带文件改动记录就强制渲染(不由 AI 控制)。
                // 位置硬性要求:必须在「总时长」上方,不得跑到其下方。
                // 两个蓝色胶囊按钮(无外框卡片):查看所有改动 / 调用 N 个工具
                // 按钮行总开关:文件改动或工具调用任一存在即渲染;删除类任务可能只有工具计数
                // (文件改动被合并/补录后也会出现),绝不能让「调用 N 个工具」被文件改动为空牵连消失
                val hasViewChanges = aiFileChanges.isNotBlank() && onViewChanges != null
                val hasToolCalls = toolCallCount > 0 && onToolCalls != null
                if (hasViewChanges || hasToolCalls) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ── 查看 N 处改动:进「改动详情」全屏 ──
                        if (hasViewChanges) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x141E40AF))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onViewChanges?.invoke() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = null,
                                tint = Color(0xFF1E40AF),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("查看所有改动", fontSize = 11.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
                        }
                        }
                        // ── 调用 N 个工具:进「工具调用」全屏清单(工具名/成败/毫秒时间戳) ──
                        if (toolCallCount > 0 && onToolCalls != null) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x141E40AF))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { onToolCalls() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("调用 $toolCallCount 个工具", fontSize = 11.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                // 计时统计：总时长（从 AI 接收并开始响应到结束的连续时长,含工具执行与多轮等待;
                // 仅 >0 显示,右对齐,非可点击。思考时长已取消显示,字段保留仅供存储兼容）
                if (totalSeconds > 0L) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("总时长 $totalSeconds 秒", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                    }
                }
                // 手动停止：保留部分输出，并在其下方提示「已手动停止」+ 可点击「继续」
                if (manualStopped) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                        Text("已手动停止", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        if (onContinueClick != null) {
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "继续", fontSize = 11.sp, color = Color(0xFF2563EB), fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { onContinueClick() }
                            )
                        }
                    }
                }
            }
            return@CompositionLocalProvider
        }
            // 别人的群聊消息：头像在左，名字在头像右上，气泡在名字下方
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                val localView2 = androidx.compose.ui.platform.LocalView.current
                // 头像（可点击/长按查看用户资料/艾特）
                if (groupConvId < 0 && fromUserId == groupConvId) {
                    // 群系统消息头像：使用群默认头像
                    val internalGroupId = -(groupConvId + 1000)
                    GroupAvatar(
                        internalGroupId = internalGroupId,
                        groupName = displayName,
                        size = avatarSize,
                        modifier = Modifier
                            .size(avatarSize)
                            .pointerInput(fromUserId) {
                                detectTapGestures(
                                    onTap = { onAvatarClick(fromUserId, displayName) },
                                    onDoubleTap = { onAvatarDoubleTap(fromUserId, displayName) },
                                    onLongPress = {
                                        localView2.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                        onAvatarLongPress(fromUserId, displayName)
                                    }
                                )
                            }
                    )
                } else {
                    if (fromUserId == AI_CHAT_ID) {
                        // AI 对话：优先使用用户上传的自定义头像，否则使用内置 AI 默认头像；不可点击
                        val customAiAvatar = AiAvatarState.bitmap
                        if (customAiAvatar != null) {
                            Image(
                                bitmap = customAiAvatar.asImageBitmap(),
                                contentDescription = "AI",
                                modifier = Modifier.size(avatarSize).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            AiDefaultAvatar(modifier = Modifier.size(avatarSize).clip(RoundedCornerShape(10.dp)))
                        }
                    } else {
                    if (myAvatar != null && fromUserId == currentUserId) {
                        // 身份互换（reverseHistory）时，对方气泡实际翻成了自己的 ID，直接显示本地头像
                        Image(
                            bitmap = myAvatar.asImageBitmap(),
                            contentDescription = "我的头像",
                            modifier = Modifier
                                .size(avatarSize)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                    val shakeOff2 = remember { androidx.compose.animation.core.Animatable(0f) }
                    val shakeScope2 = rememberCoroutineScope()
                    UserAvatar(
                        userId = fromUserId,
                        userName = displayName.ifEmpty { "?" },
                        size = avatarSize,
                        modifier = Modifier
                            .size(avatarSize)
                            .graphicsLayer {
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                                rotationZ = shakeOff2.value
                            }
                            .pointerInput(fromUserId) {
                                detectTapGestures(
                                    onTap = { onAvatarClick(fromUserId, displayName) },
                                    onDoubleTap = {
                                        localView2.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                        shakeScope2.launch {
                                            shakeOff2.animateTo(8f, tween(50))
                                            shakeOff2.animateTo(-8f, tween(50))
                                            shakeOff2.animateTo(5f, tween(40))
                                            shakeOff2.animateTo(-5f, tween(40))
                                            shakeOff2.animateTo(0f, tween(40))
                                        }
                                        onAvatarDoubleTap(fromUserId, displayName)
                                    },
                                    onLongPress = {
                                        localView2.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                        onAvatarLongPress(fromUserId, displayName)
                                    }
                                )
                            }
                    )
                    }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                // 气泡区（名字在上方独占一行，气泡在下方，整体向上偏移，仅限群聊）
                Box(Modifier.widthIn(max = 260.dp)) {
                    Column(modifier = if (groupConvId < 0) Modifier.offset(y = (-10).dp) else Modifier) {
                        // 名字：在头像右上角，独占一行，群聊徽章在名字右边
                        if (showSenderName && displayName.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = displayName,
                                    fontSize = 11.sp,
                                    color = Color(0xFF6B7280),
                                    maxLines = 1,
                                    modifier = Modifier.padding(bottom = 0.dp)
                                )
                            }
                        }
                        if (mediaType == "image" || mediaType == "jpg" || mediaType == "png" || mediaType == "gif" || mediaType == "jpeg") {
                        if (isUploading) {
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF374151)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        progress = { uploadProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.size(36.dp),
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        trackColor = Color(0x4DFFFFFF)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "${(uploadProgress * 100).toInt()}%",
                                        fontSize = 13.sp, color = Color.White,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        } else if (flashDuration > 0 && msgId !in destroyedFlashIds) {
                            var showFlashViewer by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1F2937))
                                    .combinedClickable(
                                        onClick = { showFlashViewer = true },
                                        onLongClick = { onBubbleLongPress() },
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    FlashIcon(iconSize = 32.dp)
                                    Spacer(Modifier.height(4.dp))
                                    Text("闪照", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                                    Text("点击查看", fontSize = 10.sp, color = Color(0xFF6B7280))
                                }
                            }
                            if (showFlashViewer) {
                                FlashViewerDialog(
                                    imageUrl = mediaUrl, durationSec = flashDuration,
                                    onDestroy = { onFlashDestroy(msgId) },
                                    onDismiss = { showFlashViewer = false }
                                )
                            }
                        } else if (flashDuration > 0) {
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF374151)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("图片已销毁", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                            }
                        } else {
                        // 图片消息：不带气泡框，直接显示
                        MediaImageView(
                            url = mediaUrl,
                            isMine = isMine,
                            loadMedia = loadMedia,
                            highlightAnim = highlightAnim,
                            onImageClick = onImageClick,
                            onBubbleLongPress = onBubbleLongPress
                        )
                        }
                    } else if (mediaType == "video" || mediaType == "mp4") {
                        if (isUploading) {
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF374151)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        progress = { uploadProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.size(36.dp),
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        trackColor = Color(0x4DFFFFFF)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "${(uploadProgress * 100).toInt()}%",
                                        fontSize = 13.sp, color = Color.White,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text("上传中...", fontSize = 11.sp, color = Color(0xCCFFFFFF))
                                }
                            }
                        } else {
                        // 视频消息：不带气泡框，显示缩略图（点击应用内播放）
                        Box(
                            modifier = Modifier
                                .widthIn(max = 200.dp).heightIn(max = 280.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = {
                                        if (mediaUrl.isNotEmpty()) onImageClick(mediaUrl) else onBubbleTap()
                                    },
                                    onLongClick = { onBubbleLongPress() },
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth().heightIn(max = 280.dp)
                                    .background(Color(0xFF1F2937))
                                    .clip(RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (loadMedia) VideoThumbnailView(videoUrl = mediaUrl, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
                                else Box(Modifier.fillMaxWidth().height(120.dp).background(Color(0xFF1F2937).copy(alpha = 0.3f)), contentAlignment = Alignment.Center) { Text("视频", fontSize = 13.sp, color = Color(0xFF9CA3AF)) }
                            }
                        }
                        }
                    } else if (isUploading && mediaType == "file") {
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { onBubbleLongPress() },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                        ) {
                            ChatFileUploadCard(
                                fileName = uploadFileName,
                                progress = uploadProgress,
                                stage = uploadStage
                            )
                        }
                    } else if ((mediaUrl.isNotEmpty() && text.isEmpty() && mediaType != "voice" && mediaType != "transfer") || mediaType == "file") {
                        val fileUrl = mediaUrl
                        val fileName = fileUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "[文件]" }
                        val fileExt = fileName.substringAfterLast('.', "").lowercase()
                        // 诊断：文件卡片渲染决策点，用于定位「会话预览见[文件]但气泡内无卡片」的数据
                        android.util.Log.d("AIFileCard", "render msgId=$msgId mediaType=$mediaType url='$mediaUrl' textLen=${text.length}")
                        ChatFileCard(
                            url = fileUrl,
                            fileName = fileName,
                            ext = fileExt,
                            onClick = { onFileClick(fileUrl, fileName) },
                            onDownload = { onFileDownload(fileUrl, fileName) },
                            onBubbleLongPress = onBubbleLongPress,
                            showBorder = true
                        )
                    } else if (com.aurora.chat.ui.chat.isLocationCardMessage(text)) {
                        val locCtx = androidx.compose.ui.platform.LocalContext.current
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    val loc = com.aurora.chat.ui.chat.parseLocationCard(text)
                                    if (loc != null && loc.lat != 0.0 && loc.lng != 0.0) {
                                        try {
                                            val geoUri = android.net.Uri.parse("geo:${loc.lat},${loc.lng}?q=${loc.lat},${loc.lng}")
                                            val mapIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, geoUri)
                                            locCtx.startActivity(android.content.Intent.createChooser(mapIntent, "选择导航应用"))
                                        } catch (_: Exception) {
                                            android.widget.Toast.makeText(locCtx, "没有可用的导航应用", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onLongClick = { onBubbleLongPress() },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                        ) {
                            com.aurora.chat.ui.chat.LocationCard(
                                text = text
                            )
                        }
                    } else if (com.aurora.chat.ui.chat.isShareCardMessage(text)) {
                        com.aurora.chat.ui.chat.ShareCard(
                            text = text,
                            isMine = false,
                            onPostClick = onOpenPostDetail,
                            onGroupClick = onOpenGroupDetail,
                            onFileClick = { fp -> pendingShareDownload = fp },
                            onBubbleLongPress = onBubbleLongPress
                        )
                    } else if (isPrivacyRequestCard(text) || isPrivacyResponseCard(text)) {
                        PrivacyCard(text = text, isMine = false)
                    } else if (isInviteGroupCard(text)) {
                        InviteGroupCard(text = text, isMine = false, onRespondClick = onRespondGroupInvite, onBubbleLongPress = onBubbleLongPress)
                    } else {
                        // 对方消息的文本气泡（偏灰白背景）
                        Box(
                            modifier = Modifier
                                .widthIn(max = 260.dp).heightIn(min = avatarSize).wrapContentHeight()
                                .clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFFFFF))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .then(if (highlightAnim.value > 0.01f) Modifier.drawWithContent {
                                    drawContent()
                                    drawRect(Color(0xFFBFDBFE).copy(alpha = highlightAnim.value), size = size)
                                } else Modifier),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Column {
                                // 可折叠的思考过程区域（仅 AI 消息）：思考段已内联进 text，按时间与正文交错展示
                                AiInterleavedContent(
                                    text = text,
                                    reasoningText = reasoningText,
                                    msgId = msgId,
                                    expandedReasoningMsgId = expandedReasoningMsgId,
                                    selectable = selectable,
                                    onReasoningToggled = onReasoningToggled,
                                    onBubbleLongPress = onBubbleLongPress,
                                    fontSize = 15.sp,
                                    defaultColor = Color(0xFF1F2937),
                                    lineHeight = 22.sp,
                                    onBubbleTap = onBubbleTap,
                                    onAtMentionClick = onAtMentionClick,
                                    isGroup = isGroup,
                                    groupMemberNames = groupMemberNames,
                                    onBackToTop = onCodeBackToTop,
                                    streamKey = msgId
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // 引用胶囊（可点击跳到原文）
                    if (replyToText.isNotEmpty() || replyIsRevoked) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 260.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFE5E7EB))
                                .clickable { if (replyToId > 0) onCapsuleClick(replyToId) }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (replyIsRevoked) "引用了一条已撤回消息" else "${replyToSender}:${if (replyToText.isNotEmpty()) replyToText else "[消息]"}",
                                fontSize = 10.sp, color = Color(0xFF6B7280),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

