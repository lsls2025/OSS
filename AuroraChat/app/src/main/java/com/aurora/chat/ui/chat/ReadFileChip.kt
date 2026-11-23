package com.aurora.chat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 读取文件的工具名集合：只有这些工具的 target / argsSummary 能解析出「文件名 + 行段」。
 * 未收录的工具一律返回 null，由调用方回退到原有纯文本状态行 —— 保证任何未知工具都不会被
 * 这个胶囊接管，也就不会出现「读了一半、剩下空白」之类的坏渲染。
 */
private val READ_FILE_TOOLS = setOf(
    "read_file", "read_files", "read_text_file", "archive_read_file"
)

/**
 * 读取状态胶囊的数据模型。
 *
 * @param fileName 只取文件名（不含目录），过长由 UI 单行省略
 * @param lineStart 起始行；null 表示这次调用没有行段信息（整文件读取 / 旧记录缺参数）
 * @param lineEnd 结束行；可为 null
 * @param path 完整路径（保留给后续「点击查看完整目标」用）
 */
internal data class ReadFileChipModel(
    val fileName: String,
    val lineStart: Int?,
    val lineEnd: Int?,
    val path: String
)

/**
 * 把一次 read_file 调用解析成胶囊模型。解析不出来返回 null（调用方回退纯文本）。
 *
 * 兜底原则（宁可退化，不许乱渲染）：
 *  · 工具名不在 [READ_FILE_TOOLS] → null
 *  · 拿不到文件路径 → null（绝不显示一个只有「读取」两个字的空胶囊）
 *  · 拿不到行段 → lineStart/lineEnd 为 null（胶囊只显示文件名，不硬凑行号）
 *  · offset/limit 只在两者都存在且 limit>0 时才换算行段；否则不猜
 */
internal fun parseReadFileChip(
    tool: String,
    target: String,
    argsSummary: String
): ReadFileChipModel? {
    if (tool !in READ_FILE_TOOLS) return null

    val rawPath = firstNonBlank(
        pickArg(argsSummary, "path", "file", "file_path", "filepath", "filename"),
        cleanTargetPath(target)
    ) ?: return null
    if (rawPath.isBlank()) return null

    val fileName = rawPath.substringAfterLast('/').substringAfterLast('\\').trim()
    if (fileName.isBlank()) return null

    val (start, end) = parseLineRange(argsSummary, target, rawPath)
    return ReadFileChipModel(fileName = fileName, lineStart = start, lineEnd = end, path = rawPath)
}

/** 读取状态胶囊：眼睛图标 + 「读取」+ 文件名 + 可选 Lx-y，固定单行。 */
@Composable
internal fun ReadFileChip(
    model: ReadFileChipModel,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val range = remember(model.lineStart, model.lineEnd) { formatReadRange(model.lineStart, model.lineEnd) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = Icons.Outlined.Visibility,
            contentDescription = "读取文件",
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "读取",
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Spacer(Modifier.width(4.dp))
        // 文件名占剩余宽度并可省略；行段固定跟在后面，不会被文件名挤掉。
        Text(
            text = model.fileName,
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (range.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = range,
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

/** L12200-12274 / L12200 / 空串。行段非法或缺失时返回空串，UI 不显示。 */
internal fun formatReadRange(start: Int?, end: Int?): String {
    val s = start?.takeIf { it > 0 } ?: return ""
    val e = end?.takeIf { it > 0 }
    return if (e == null || e == s) "L$s" else "L$s-$e"
}

// ───────────────────────── 解析辅助 ─────────────────────────

/**
 * 从 argsSummary（多行 key=value / key: value）里取第一个命中的参数值。
 * 会去掉包裹的引号；空值视为未命中，继续找下一个 key。
 */
private fun pickArg(args: String, vararg keys: String): String? {
    if (args.isBlank()) return null
    for (line in args.lineSequence()) {
        val t = line.trim()
        if (t.isEmpty()) continue
        val eq = t.indexOf('=')
        val colon = t.indexOf(':')
        val sep = when {
            eq > 0 && (colon < 0 || eq < colon) -> eq
            colon > 0 -> colon
            else -> -1
        }
        if (sep <= 0) continue
        val key = t.substring(0, sep).trim().lowercase()
        if (key !in keys) continue
        val value = t.substring(sep + 1).trim().trim('"', '\'', '“', '”')
        if (value.isNotEmpty()) return value
    }
    return null
}

/** 清掉目标里的行段后缀/后缀说明，只留路径主体（如 "a.kt L1-9" → "a.kt"）。 */
private fun cleanTargetPath(target: String): String? {
    var t = target.trim()
    if (t.isEmpty()) return null
    t = t.lineSequence().firstOrNull()?.trim().orEmpty()
    if (t.isEmpty()) return null
    // 去掉尾部的「 L123-456」；路径本身含 L 不会被误伤（要求 L 前有空格）
    t = t.replace(Regex("""\s+L\d{1,7}(?:\s*[-~]\s*\d{1,7})?\s*$"""), "")
    // 去掉「（共 N 行）」这类补充说明
    t = t.replace(Regex("""\s*[（(]\s*共\s*\d+\s*行\s*[）)]\s*$"""), "")
    return t.trim().ifBlank { null }
}

private val RANGE_RE = Regex("""(?:^|[\s(（])(?:L)?(\d{1,7})\s*[-~至]\s*(\d{1,7})""")

/**
 * 单数字行段（如 "a.kt L120"）：RANGE_RE 只认范围，单独行号靠它兜底。
 * 严格限定数字前是行首/空白/括号（避免把路径里的纯数字段误判成行号，如 "C:\dir\120"）。
 */
private val SINGLE_LINE_RE = Regex("""(?:^|[\s(（])(?:L)?(\d{1,7})\s*$""")

/**
 * 行段解析优先级：显式 start/end 参数 > offset+limit 换算 > 文本里的 Lx-y。
 * 全都拿不到时返回 (null, null)，胶囊退化为只显示文件名。
 */
private fun parseLineRange(args: String, target: String, path: String): Pair<Int?, Int?> {
    val explicitStart = firstInt(
        pickArg(args, "start_line", "line_start", "startline", "from_line", "from", "start")
    )
    val explicitEnd = firstInt(
        pickArg(args, "end_line", "line_end", "endline", "to_line", "to", "end")
    )
    if (explicitStart != null || explicitEnd != null) {
        val s = explicitStart ?: explicitEnd ?: return null to null
        return normalizeRange(s, explicitEnd ?: s)
    }

    val offset = firstInt(pickArg(args, "offset", "start_offset"))
    val limit = firstInt(pickArg(args, "limit", "max_lines", "lines", "count"))
    if (offset != null && limit != null && limit > 0) {
        // offset 按 0 基行偏移理解：offset=0 从第 1 行开始。
        val s = if (offset <= 0) 1 else offset + 1
        return normalizeRange(s, s + limit - 1)
    }

    val hit = RANGE_RE.find(target) ?: RANGE_RE.find(args) ?: RANGE_RE.find(path)
    if (hit != null) {
        val s = hit.groupValues[1].toIntOrNull()
        val e = hit.groupValues[2].toIntOrNull()
        if (s != null) return normalizeRange(s, e ?: s)
    }

    // 单数字行段兜底（target/args 里的 "L120" / "第120行"）：只认行首/空白/括号后的独立数字
    val single = SINGLE_LINE_RE.find(target) ?: SINGLE_LINE_RE.find(args)
    if (single != null) {
        val s = single.groupValues[1].toIntOrNull()
        if (s != null) return normalizeRange(s, s)
    }
    return null to null
}

private fun normalizeRange(start: Int, end: Int): Pair<Int?, Int?> {
    if (start <= 0) return null to null
    val s = start
    val e = if (end <= 0) s else end
    return if (s <= e) s to e else e to s
}

private fun firstInt(value: String?): Int? =
    value?.trim()?.let { Regex("""\d{1,7}""").find(it)?.value?.toIntOrNull() }

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()
