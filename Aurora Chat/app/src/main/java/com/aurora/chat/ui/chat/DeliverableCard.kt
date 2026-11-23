package com.aurora.chat.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.components.ShimmerText

/**
 * 交付总结卡片数据：由 DELIVER 哨兵块解析而来。
 * [fields] 已由 [DeliveryProtocol.parseFields] 归一：每种标签最多一个，按规范顺序排列
 * （目标/已完成/产物/验证/遗留/限制/备注/说明）；
 * [products] 从「产物」字段中提取的路径清单（按路径去重，可点击定位）。
 */
internal data class DeliverableField(val label: String, val content: String)

/** 交付状态：由交付块实际内容动态推导（禁止写死「已完成」） */
internal enum class DeliverableStatus { COMPLETED, PARTIAL, FAILED }

/** 产物条目：path 为清理后的路径；op 为从产物行尾 (op) 后缀识别出的操作类型（可为空） */
internal data class ProductRef(val path: String, val op: String = "")

internal data class DeliverableCardData(
    val fields: List<DeliverableField>,
    val products: List<ProductRef>,
    val status: DeliverableStatus = DeliverableStatus.COMPLETED,
    /** true = 交付块**还没写完**（尾部哨兵未出现）：先立卡片框架、内容边写边填。
     *  纯渲染期标记：此时**不显示状态角标**（半截内容推导出的"已完成/未完成"必然失真）。 */
    val streaming: Boolean = false,
    /** 逐项完成度（由「已完成」+「遗留」两栏的 [x]/[~]/[ ]/[!] 标记解析）：角标显示 3/5、
     *  清单逐项着色都依赖它；没有任何带标记的条目时为空（走词表回退判定）。 */
    val progress: ProgressInfo = ProgressInfo.EMPTY
)

/** 产物行前缀清理：去掉行首列表符号与空白 */
internal fun String.trimListPrefix(): String =
    trim().trimStart('-', '•', '*', '·', '>', ' ', '\t', '　')

/** 产物行是否为真实路径/条目（排除「未检出」「无」「暂无」这类占位文案） */
private fun isRealProductLine(line: String): Boolean {
    val t = line.trimListPrefix()
    if (t.isEmpty()) return false
    val lower = t.lowercase()
    if (lower.startsWith("(未") || lower.startsWith("无") || lower.startsWith("未检出") ||
        lower.startsWith("暂无") || lower.startsWith("none") || lower.startsWith("n/a") ||
        lower.startsWith("待定") || lower.startsWith("略")) return false
    return true
}

/**
 * 解析 DELIVER 块内部文本为字段列表。
 *
 * 已收口到 [DeliveryProtocol.parseFields]（单一事实源）：
 * 同义标签归位、同名标签合并、条目去重、「遗留」里标完成的项归回「已完成」，
 * 因此返回值里**每种标签最多一个字段**，且按规范顺序排列。
 */
internal fun parseDeliverFields(inner: String): List<DeliverableField> =
    DeliveryProtocol.parseFields(inner)

/** 产物行尾的操作标记后缀，如 `a.txt (deleted)` / `b.md（已删除）`：识别后剥离并作为 op 保留 */
private val OP_SUFFIX_RE = Regex("""\s*[（(]([^（()）]*)[)）]\s*$""")

/**
 * 从「产物」字段内容中提取产物条目（按行、去前缀、去占位文案、去重保序）。
 * 识别行尾 `(op)` 操作标记并剥离，返回 [ProductRef]；无标记时 op 为空，由渲染层按真实改动记录兜底。
 */
internal fun extractProductLines(productsContent: String): List<ProductRef> {
    // 按**路径**去重（而不是按 (路径, op) 去重）：模型在两套交付里把同一个文件写了两遍、
    // 一次带 `(created)` 一次不带，旧实现会渲染成两枚一模一样的产物胶囊。
    val out = LinkedHashMap<String, ProductRef>()
    for (raw in productsContent.split("\n")) {
        val t = raw.trimListPrefix()
        if (!isRealProductLine(t)) continue
        var clean = t.trim()
        var op = ""
        val m = OP_SUFFIX_RE.find(clean)
        if (m != null) {
            val inner = m.groupValues[1].trim()
            if (inner.isNotBlank()) {
                op = inner.lowercase()
                clean = clean.substring(0, m.range.first).trim()
            }
        }
        clean = clean.trimEnd('，', '。', ',', '.', ';', '；', ')', ']', '}', '」', '』')
        if (clean.isEmpty()) continue
        val key = clean.replace(Regex("\\s+"), "").lowercase()
        val prev = out[key]
        // 同一路径只保留一条：优先保留带操作标记的那条（真实颜色/语义更准）
        if (prev == null || (prev.op.isEmpty() && op.isNotEmpty())) out[key] = ProductRef(clean, op)
    }
    return out.values.toList()
}

// ==================== 逐项完成度（结构化状态） ====================
// 「判定不准」的根因：过去只有模型的自由散文可猜，压根没有"任务由哪几项构成、每项什么状态"这份数据。
// 现在交付契约要求清单行首带 `[x] / [~] / [ ] / [!]` 标记，这里把它解析成**可计数的条目**：
// 完成度从"猜措辞"变成"数条目"，而且能把「哪一项没做完」直接标在那一行上。

/** 单个任务项的状态 */
internal enum class TaskItemMark { DONE, PARTIAL, TODO, FAILED }

/** 任务项（状态标记 + 已剥掉标记的正文） */
internal data class TaskItem(val mark: TaskItemMark, val text: String)

/** 逐项完成度汇总 */
internal data class ProgressInfo(val items: List<TaskItem> = emptyList()) {
    val done: Int get() = items.count { it.mark == TaskItemMark.DONE }
    val partial: Int get() = items.count { it.mark == TaskItemMark.PARTIAL }
    val todo: Int get() = items.count { it.mark == TaskItemMark.TODO }
    val failed: Int get() = items.count { it.mark == TaskItemMark.FAILED }
    val total: Int get() = items.size
    companion object { val EMPTY = ProgressInfo() }
}

/** 清单行首的状态标记（容忍 `- [x]`、`• [ ]`、`✅`、`☐` 等写法）。协议层去重也要用，故 internal。 */
internal val MARK_PREFIX_RE = Regex(
    "^\\s*[-•*·>]?\\s*(\\[[xX~ !]\\]|✅|✔️?|√|❌|✖️?|✗|⚠️?|☐|□)\\s*"
)

/** 系统托管的占位行（"(无工具调用记录)" / "(未检出文件改动)"）：不是任务项，不参与计数 */
private fun isPlaceholderLine(t: String): Boolean {
    val s = t.trimListPrefix().trimStart('(', '（')
    return s.startsWith("无工具调用") || s.startsWith("未检出") || s.startsWith("无文件改动")
}

/**
 * 把一栏清单文本解析成任务项列表。
 *
 * @param defaultMark 该栏**没有标记**时的缺省状态：`已完成` 栏 → DONE（旧格式就是把做过的事列出来）；
 *   `遗留` 栏 → TODO（这一栏写的就是还没做完的事）。带标记的行以标记为准。
 */
internal fun parseTaskItems(content: String, defaultMark: TaskItemMark): List<TaskItem> {
    val out = mutableListOf<TaskItem>()
    for (raw in content.split("\n")) {
        val t = raw.trim()
        if (t.isEmpty()) continue
        if (isPlaceholderLine(t)) continue
        // 格式说明被抄成内容（「逐项列出…」这类）不是任务项：既不计数也不显示，
        // 否则清单里会凭空多出一个空心圆项，并把整卡压成「部分完成」。
        if (isInstructionEcho(t)) continue
        val m = MARK_PREFIX_RE.find(t)
        val rawMark = m?.groupValues?.get(1)?.trim()?.lowercase()
        val mark = when (rawMark) {
            "[x]", "✅", "√", "✔", "✔️" -> TaskItemMark.DONE
            "[~]" -> TaskItemMark.PARTIAL
            "[ ]", "☐", "□" -> TaskItemMark.TODO
            "[!]", "❌", "✖", "✖️", "✗", "⚠", "⚠️" -> TaskItemMark.FAILED
            else -> null
        }
        val body = (if (m != null) t.substring(m.range.last + 1) else t).trimListPrefix().trim()
        if (body.isEmpty() || isVoidText(body)) continue
        out.add(TaskItem(mark ?: defaultMark, body))
    }
    return out
}

/** 从交付字段收集逐项完成度：「已完成」栏 + 「遗留」栏（遗留为占位文案时不计） */
internal fun collectProgress(fields: List<DeliverableField>): ProgressInfo {
    val items = mutableListOf<TaskItem>()
    for (f in fields) {
        when (f.label) {
            "已完成" -> items += parseTaskItems(f.content, TaskItemMark.DONE)
            "遗留" -> if (!isVoidText(f.content)) items += parseTaskItems(f.content, TaskItemMark.TODO)
        }
    }
    return ProgressInfo(items)
}

/**
 * 客观执行证据 —— 来自**真实记录**而非模型措辞：
 * 工具调用成败（ChatMsg.ToolStageRecord.state）与文件改动（FileChange → productOps.size）。
 */
internal data class TaskEvidence(
    val toolTotal: Int = 0,
    val toolFailed: Int = 0,
    val changedFiles: Int = 0
) {
    val allToolsFailed: Boolean get() = toolTotal > 0 && toolFailed == toolTotal
    val hasOutput: Boolean get() = changedFiles > 0
    companion object { val EMPTY = TaskEvidence() }
}

/**
 * 用客观证据**修正**判定结果（只压不高，且**极窄**）。
 *
 * ⚠️ 判定主轴是「**用户交代的目标有没有达成**」，不是「过程是否全绿」（用户定规）：
 * 中间用奥特曼做的、中途失败过一次后重试成功的、换了别的方法做成的 —— 只要目标达成就是**已完成**。
 * 因此这里删掉了此前"只要出现过失败且无文件产出就降级"的规则：那条规则会把
 * 「下载任务成功、但下载不产生文件改动记录、且中途失败过一次」直接误降成"部分完成"。
 *
 * 唯一保留的兜底：本次记录里**所有工具调用都失败、且没有任何文件产出** —— 声称的"完成"不可信，
 * 保守压成"部分完成"。反向不修正（不把 FAILED 拔高成 COMPLETED）。
 */
internal fun applyTaskEvidence(base: DeliverableStatus, ev: TaskEvidence): DeliverableStatus {
    if (base != DeliverableStatus.COMPLETED) return base
    if (ev.allToolsFailed && !ev.hasOutput) return DeliverableStatus.PARTIAL
    return base
}

// 「无实质内容」判定（isVoidText）与「遗留」越界闲聊清洗（sanitizeLeftoverContent）
// 已上移到 DeliveryProtocol.kt —— 交付语义只允许有一个事实源，解析与渲染共用同一套判定。

/** 硬失败信号：任务确实没做成。判定前会先做否定清洗（见 AITaskCompletion.stripFailureNegations） */
private val HARD_FAIL_ZH = listOf("失败", "未能", "无法完成", "无法执行", "做不到", "出错", "报错", "中止", "被迫中断", "异常退出")
private val HARD_FAIL_EN = Regex("""\b(failed|failure|error|aborted)\b""", RegexOption.IGNORE_CASE)

/** 部分性措辞：做了一部分，但这**不等于**失败 */
private val PARTIAL_HINT_ZH = listOf("部分", "尚未", "未完成", "仅完成", "只完成", "还没", "待完成", "待处理", "留待", "剩余")

/** 明确"全都做完了"的措辞：出现它就压过"部分"这类模糊词，避免「已全部完成（部分文件另有备份）」被误判 */
private val ALL_DONE_ZH = listOf(
    "全部完成", "全部做完", "全部搞定", "全部实现", "全部通过", "全部处理完", "全部落实",
    "均已完成", "均已通过", "已全部完成", "所有步骤均", "所有项均", "全部已实现"
)

/**
 * 交付状态推导。**两条路，结构化优先**：
 *
 * ⓪ **结构化路径（首选）**：交付清单里带状态标记（[x] / [~] / [ ] / [!]）时，直接**数条目**——
 *    [x] 全为完成且无待办 → 已完成；有失败且一项都没完成 → 未完成；其余 → 部分完成。
 *    这条路同时给出了「哪部分没完成」的数据（逐项标记会渲染到卡片上），不再靠猜措辞。
 *
 * ① **词表回退路径**（旧消息 / 弱模型不守格式时启用，按下列顺序判定，先命中先返回）：
 *    ① 所有栏都空 → 未完成；
 *    ② 「目标 / 已完成 / 验证」出现**硬失败**信号 → 未完成
 *       （判定前先做**否定清洗**：「未发现失败项」「无任何问题」「均正常」不会被误判为失败）；
 *    ③ 「遗留」栏有实质内容（非「无 / 暂无 / 不涉及…」占位）→ **部分完成**
 *       —— 「遗留」栏**不参与**硬失败判定：它本来就是写"还剩什么没做"的地方，
 *       「遗留：第三项未完成」属于正常的部分完成，不该判成整体失败；
 *    ④ 「目标 / 已完成 / 验证」出现部分性措辞（部分 / 尚未 / 仅完成…）→ 部分完成
 *       （若同栏另有明确"全部完成"表述，则不因这些模糊词降级）；
 *    ⑤ 其余 → 已完成。
 *
 * 最后再由 [applyTaskEvidence] 用**真实工具/文件记录**做一次保守修正（只压不高）。
 */
internal fun deriveDeliverableStatus(
    fields: List<DeliverableField>,
    progress: ProgressInfo = ProgressInfo.EMPTY
): DeliverableStatus {
    // ① 结构化优先：清单带状态标记时，**数条目**远比"猜措辞"可靠 —— 这也是「哪部分没完成」的数据来源。
    //    标记口径（与交付契约一致）：[x] 完全达成、[~] 只做了一部分、[ ] 还没做、[!] 失败。
    if (progress.total > 0) {
        // 有失败项且没有任何一项做完 → 整体就是没做成，不是"部分完成"
        if (progress.failed > 0 && progress.done == 0) return DeliverableStatus.FAILED
        // 全部标记为完成、且没有待办/部分/失败 → 已完成
        if (progress.todo == 0 && progress.partial == 0 && progress.failed == 0) return DeliverableStatus.COMPLETED
        // 其余（有 [~] / [ ] / 部分失败）→ 部分完成，且界面能逐项指出是哪些
        return DeliverableStatus.PARTIAL
    }
    // ② 无标记（旧消息 / 弱模型不守格式）→ 回退到原有词表判定，保证不退化
    return deriveFromProse(fields)
}

/** 词表兜底判定（无状态标记时的旧路径，行为保持原样） */
private fun deriveFromProse(fields: List<DeliverableField>): DeliverableStatus {
    var hasRealContent = false
    var hardFail = false
    var hasOpenItem = false
    var hasPartialHint = false
    for (f in fields) {
        // 判定前先剔掉"模型把格式说明抄成内容"的行：那些是**提示词的说明**，
        // 不是任务进展，写着「一部分」「没有就写无」这类措辞会把整卡误判成部分完成。
        val raw = f.content.lines()
            .filterNot { isInstructionEcho(it) }
            .joinToString("\n").trim()
        if (raw.isEmpty()) continue
        hasRealContent = true
        // 统一定否清洗：把「未发现失败 / 无问题 / 均正常」这类否定短语摘掉后再找失败信号
        val cleaned = AITaskCompletion.stripFailureNegations(raw)
        val lower = cleaned.lowercase()
        when (f.label) {
            "遗留" -> if (!isVoidText(raw)) hasOpenItem = true
            "目标", "已完成", "验证" -> {
                if (HARD_FAIL_ZH.any { lower.contains(it) } || HARD_FAIL_EN.containsMatchIn(cleaned)) hardFail = true
                if (ALL_DONE_ZH.none { lower.contains(it) } && PARTIAL_HINT_ZH.any { lower.contains(it) }) hasPartialHint = true
            }
        }
    }
    if (!hasRealContent) return DeliverableStatus.FAILED
    if (hardFail) return DeliverableStatus.FAILED
    if (hasOpenItem) return DeliverableStatus.PARTIAL
    if (hasPartialHint) return DeliverableStatus.PARTIAL
    return DeliverableStatus.COMPLETED
}

/**
 * 清掉模型把哨兵"抄成明文"留下的痕迹。
 *
 * 哨兵真身是带控制字符的 `\u0001DELIVER\u0001`，而模型在提示词里看到的只是"DELIVER"这几个字母
 * （控制字符不可见），于是它常在真正的哨兵块**前面**自己补一行纯文本 `DELIVER`（或 `/DELIVER`）。
 * 那行不在剥离范围内，会以原始文本外露——用户看到的就是「交付卡片上方孤零零一行 DELIVER」。
 * 这里把这类整行标记连同残留的控制字符一并清掉（只删"整行就是这个词"的行，不碰正文其它内容）。
 *
 * ⚠️ **只能用在"已不含真哨兵"的文本上**（例如交付块的解析产物、块外正文）：
 * 本函数会抹掉 `\u0001`，而真哨兵 `\u0001DELIVER\u0001` / `\u0001/DELIVER\u0001` 正是由它构成的，
 * 用在原文上会把真哨兵销毁、导致配对解析失败（症状：哨兵以 "DELIVER"/"/DELIVER" 纯文本外露）。
 */
/**
 * 零宽 / 格式字符：肉眼不可见，却会让所有依赖 `\s` 的标记正则**全部失效**。
 *
 * 用户截图实测：模型输出在哨兵词前后夹带了不可见字符，导致
 *  ① 卡片上方外漏一行明文 "DELIVER"（SOLE_RE 的 `\s*$` 匹配不到零宽字符）；
 *  ② 「遗留」栏出现「○ /DELIVER」假待办（TAIL_RE 的 `\s*[/／]` 匹配不到）。
 * 本正则只用于**已剥离真哨兵的解析产物**（正文 / 块内容），删掉它们对展示无损。
 */
private val ZERO_WIDTH_RE = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u2060\\u00AD]")

internal fun stripStrayDeliverMarkers(s: String): String {
    if (s.isEmpty()) return s
    // ⚠️ 绝对不要在这里 replace("\u0001", "")：正文里除了交付哨兵，还有工具状态哨兵
    // (\u0001W\u0001…\u0001/W\u0001) 与思考哨兵，它们同样由 \u0001 组成 —— 一删就会让渲染器
    // 认不出这些哨兵，把 "Wset_agent_mode0/W" 这种内部控制串当普通文本外露。
    // 本函数只做一件事：清掉模型把哨兵"抄成明文"的残留。三种形态都要覆盖：
    //   ① 整行就是标记（DELIVER / /DELIVER / DELIVER: / ／deliver，容忍行首列表符号与编号）；
    //   ② 行首裸标记（模型把 `DELIVER 目标: …` 挤在一行）；
    //   ③ 行尾残留（`…如需细读可再告诉我 /deliver`）。
    // 匹配前先归一化不可见字符（零宽字符 + 全角空格），否则上述正则会被它们全部绕过。
    val normalized = ZERO_WIDTH_RE.replace(s, "").replace('\u3000', ' ')
    val sb = StringBuilder()
    for (line in normalized.lines()) {
        if (STRAY_MARKER_SOLE_RE.matches(line)) continue
        var l = STRAY_MARKER_HEAD_RE.replaceFirst(line, "")
        l = STRAY_MARKER_TAIL_RE.replaceFirst(l, "")
        sb.append(l).append('\n')
    }
    return sb.toString()
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/**
 * ① 整行就是标记（容忍全角斜杠、尾随冒号，以及行首列表符号 / 编号 ——
 *   模型常把它当清单项写：「- /DELIVER」「• DELIVER」「1. /deliver」）。
 *   仍要求**整行**只有这个词，字段头（「遗留：/DELIVER」）不会误命中。
 */
private val STRAY_MARKER_SOLE_RE =
    Regex("^\\s*(?:[-•*·>]|\\d{1,2}[.、)）])?\\s*[/／]?\\s*[Dd][Ee][Ll][Ii][Vv][Ee][Rr]\\s*[:：]?\\s*$")

/** ② 行首裸标记（后面必须跟空白或冒号，绝不会误伤 deliverable 这类正常单词） */
private val STRAY_MARKER_HEAD_RE =
    Regex("^\\s*[Dd][Ee][Ll][Ii][Vv][Ee][Rr]\\s*(?:[:：]\\s*|\\s+)")

/** ③ 行尾带斜杠的标记（要求斜杠，避免误删"…交付 deliver"这类正常句尾） */
private val STRAY_MARKER_TAIL_RE =
    Regex("\\s*[/／]\\s*[Dd][Ee][Ll][Ii][Vv][Ee][Rr]\\s*[:：]?\\s*$")

/**
 * 将 AI 消息文本中的 DELIVER 交付块从正文中剥离：
 * 完整/部分块 → 解析为 [DeliverableCardData] 交给卡片渲染，正文不再出现任何哨兵与块内容；
 * 未成形（刚出现哨兵、内容不足）→ 仅剥离哨兵字符，避免 "DELIVER/DELIVER" 纯文本外露。
 *
 * @return (卡片数据, 供正文渲染的 bodyText)
 */
internal fun prepareDeliverableDisplay(text: String): Pair<DeliverableCardData?, String> {
    // 统一走**嵌套安全**的配对解析（与 hasDeliverBlock / hasDelivery / extractDelivery 同一事实源）：
    // 正文剔除**所有**交付块（含嵌套块内容）→ 绝不外漏；卡片只吃最外层块（已剔除嵌套块）→ 不会重复。
    val parsed = AITaskCompletion.parseDeliveryBlocks(text)
    if (parsed.closed || parsed.content.isNotBlank()) {
        val trimmed = parsed.content.trim()
        val fields = parseDeliverFields(trimmed)
        val products = fields.firstOrNull { it.label == "产物" }?.let { extractProductLines(it.content) } ?: emptyList()
        // 逐项完成度：「已完成」+「遗留」两栏的状态标记 → 角标计数 + 清单逐项着色
        val progress = collectProgress(fields)
        // 【流式框架】未闭合 = 模型正在写交付块：**立刻立框架**（哪怕一个字段都还没解析出来），
        // 让输出过程中看到的是"一张正在成形的交付卡"而不是一段纯文本；写完后自动切到完整解析与真实状态。
        if (!parsed.closed) {
            return DeliverableCardData(
                fields = fields,
                products = products,
                status = DeliverableStatus.COMPLETED,   // 流式期间不显示角标，这里只是占位
                streaming = true,
                progress = progress
            ) to tidyCardBodyText(parsed.bodyText)
        }
        if (trimmed.length < 8) return null to tidyCardBodyText(parsed.bodyText) // 内容太少，仅剥哨兵
        return DeliverableCardData(
            fields, products, deriveDeliverableStatus(fields, progress), progress = progress
        ) to tidyCardBodyText(parsed.bodyText)
    }
    // —— 宽容兜底：弱模型未输出 DELIVER 哨兵、仅以纯文本交付 ——
    // 用现有字段正则扫正文,命中足够字段(且含强交付信号)就照样渲染卡片,被识别的字段行从正文移除避免重复。
    val lenient = parseLenientDeliverable(text)
    return lenient.first to tidyCardBodyText(stripStrayDeliverMarkers(lenient.second))
}

/**
 * 无哨兵交付的宽容解析：弱模型不按 [[DELIVER]] 协议输出、只把交付内容写成纯文本行时,
 * 仍尽量渲染出交付卡片,使"模型不按规范输出"也能有可视化结果。
 *
 * 门禁(防误判普通聊天为交付):至少命中 2 个交付字段,且其中含「目标/已完成/产物」之一(强交付信号)。
 * 命中的字段行及其续行从正文剥离,其余正文照常显示。
 */
private fun parseLenientDeliverable(text: String): Pair<DeliverableCardData?, String> {
    val lines = text.split("\n")
    val innerLines = mutableListOf<String>()
    val innerIdx = mutableSetOf<Int>()
    val labels = mutableSetOf<String>()
    var inField = false
    for ((i, raw) in lines.withIndex()) {
        val m = DeliveryProtocol.matchHeader(raw, strict = false)
        if (m?.label != null) {
            inField = true
            innerLines.add(raw)
            innerIdx.add(i)
            labels.add(m.label)
        } else if (inField && raw.trim().isNotEmpty()) {
            // 字段头之后的续行(缩进清单/换行内容)归入交付块
            innerLines.add(raw)
            innerIdx.add(i)
        } else if (raw.trim().isEmpty()) {
            // 空行:结束当前字段的续行归属
            inField = false
        }
    }
    val strong = labels.any { it in setOf("目标", "已完成", "产物") }
    if (innerLines.isEmpty() || labels.size < 2 || !strong) return null to text
    val inner = innerLines.joinToString("\n")
    val fields = parseDeliverFields(inner)
    if (fields.isEmpty()) return null to text
    val products = fields.firstOrNull { it.label == "产物" }?.let { extractProductLines(it.content) } ?: emptyList()
    val body = lines.filterIndexed { i, _ -> i !in innerIdx }.joinToString("\n").trim()
    // 逐项完成度也要带上：否则角标只剩一个形容词（「部分完成」），看不出差几项
    val progress = collectProgress(fields)
    return DeliverableCardData(
        fields, products, deriveDeliverableStatus(fields, progress), progress = progress
    ) to body
}

/**
 * 大厂风格「交付总结」卡片：白底圆角细边框，分区展示 目标/已完成/产物/验证/遗留，
 * 产物路径渲染为可点击蓝色小胶囊（点击后由上层定位工作区）。
 */
@Composable
internal fun DeliverableCard(
    data: DeliverableCardData,
    onFileRefAction: ((String, Int) -> Unit)? = null,
    /** 真实文件改动记录（路径 → FileChange.op）：命中时以真实 op 覆盖产物行内后缀推断，保证颜色/语义真实 */
    productOps: Map<String, String> = emptyMap(),
    /**
     * 本条消息是否**仍在生成中**（msgId == 正在流式的那条消息 id）。
     *
     * ⚠️ 判定不能用 `data.streaming`：交付块哨兵一旦闭合它立刻变 false，于是"块已写完、整条回答还在
     * 继续输出"这段最常见的期间，卡片是完全静态的 —— 用户看到的正是"交付总结并没有在动"。
     * 传 true 时：整卡文字**一起闪烁**（同步）；消息收尾后自动回静态（已完成的不该继续闪）。
     */
    live: Boolean = false,
    /** 本条消息的**真实**工具调用记录（成功/失败）：状态判定的客观证据之一。
     *  以前判定只看模型写的散文，工具明明报错、卡片却显示"已完成"——现在由真实记录把它压回来。 */
    toolStages: List<ChatMsg.ToolStageRecord> = emptyList(),
    modifier: Modifier = Modifier
) {
    // 流式中即使字段还没解析出来也要把**框架**立起来（否则输出过程中只能看到一段裸文本）；
    // 只有"非流式且真的什么都没有"才整卡不渲染。
    if (!data.streaming && data.fields.isEmpty() && data.products.isEmpty()) return
    val animate = live || data.streaming
    // ── 整卡同步闪烁（取代此前的"扫光"）──
    // 卡片内所有文字共用这一份透明度动画：若让每处文字各自 rememberInfiniteTransition，它们会各跑各的、
    // 相位互相错开（后出现的字段起步更晚），闪起来就是一片杂乱明暗。算一份、显式下发到每处文字，
    // 整卡闪烁才是严格同步的。周期 700ms 往返（与 ShimmerText 的呼吸同频，1.4s 一个完整明暗循环）。
    val blinkAlpha: Float = if (animate) {
        val blinkTransition = rememberInfiniteTransition(label = "deliverBlink")
        val a by blinkTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "deliverBlinkAlpha"
        )
        a
    } else 1f
    // 状态角标：判定优先级 = 结构化清单计数 > 词表回退，最后被**真实执行记录**保守修正。
    // ⚠️ 流式（模型还在写）时半截内容推导出的状态必然失真（比如刚写完「遗留」就显示"部分完成"），
    // 所以流式期间改显示"正在汇总…"，收尾后自动换成真实状态。
    val progress = data.progress
    val (statusText, statusColor) = if (data.streaming) {
        "正在汇总…" to Color(0xFF94A3B8)
    } else {
        // 客观证据：工具真实成败 + 真实文件改动数（后者即产物操作映射的规模）。
        // 只统计**明确的成功/失败**（1/2）—— 旧数据里 state 可能是 -1（未知），把它算进"失败"会平白降级。
        val evidence = TaskEvidence(
            toolTotal = toolStages.count { it.state == 1 || it.state == 2 },
            toolFailed = toolStages.count { it.state == 2 },
            changedFiles = productOps.size
        )
        val finalStatus = applyTaskEvidence(data.status, evidence)
        val label = when (finalStatus) {
            DeliverableStatus.COMPLETED -> "已完成"
            DeliverableStatus.PARTIAL -> "部分完成"
            DeliverableStatus.FAILED -> "未完成"
        }
        val color = when (finalStatus) {
            DeliverableStatus.COMPLETED -> Color(0xFF10B981)
            DeliverableStatus.PARTIAL -> Color(0xFFD97706)
            DeliverableStatus.FAILED -> Color(0xFFDC2626)
        }
        // 有逐项清单时把完成度并进角标（"部分完成 3/5"）：一眼看出还差几项，
        // 而不是只给一个形容词、剩下全靠读正文猜。没有清单则维持原样。
        val text = if (progress.total > 0) "$label ${progress.done}/${progress.total}" else label
        text to color
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // ── 标题行：蓝色装饰条 + 交付总结 + 完成角标 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF1E40AF))
            )
            Spacer(Modifier.width(7.dp))
            // 生成中：标题「交付总结」整体闪烁（用整卡统一的那份节奏），一眼可辨"这份总结还在成形"。
            if (animate) {
                ShimmerText(
                    text = "交付总结",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1F2937),
                    highlightColor = Color(0xFF9DB6D8),
                    sweep = false,
                    blinkAlpha = blinkAlpha
                )
            } else {
                Text("交付总结", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
            }
            Spacer(Modifier.weight(1f))
            if (animate) {
                // 流式中（模型还在写交付内容）：角标文案跟着整卡一起闪烁，"正在汇总…"本身在动，
                // 一眼可辨"还在生成"，而不是看起来已经定稿的静态文字。
                ShimmerText(
                    text = statusText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                    highlightColor = Color(0xFFE2E8F0),
                    sweep = false,
                    blinkAlpha = blinkAlpha
                )
            } else {
                Text(statusText, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = statusColor)
            }
        }
        // ── 分隔线 ──
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF3F4F6)))
        Spacer(Modifier.height(8.dp))

        var first = true
        for (field in data.fields) {
            if (!first) Spacer(Modifier.height(10.dp))
            first = false
            when (field.label) {
                "产物" -> ProductField(field.content, data.products, productOps, onFileRefAction, live = animate, blinkAlpha = blinkAlpha)
                "已完成" -> ListField(field.label, field.content, live = animate, blinkAlpha = blinkAlpha, defaultMark = TaskItemMark.DONE)
                // 「遗留」以前是一坨多行文本，看不出"还剩哪几项"。改走清单渲染后 [ ] / [~] [!] 逐项着色，
                // 「部分完成到底是哪部分没做」直接可读。
                // ⚠️ 但**不能但凡有内容就套圆圈**：先把这一栏解析成任务项，一条真项都没有时按「无」简单展示
                // （模型常写「无 - 任务已完成」「无遗留项」这类"无 + 解释"，套上圆圈就成了空心圆待办）。
                "遗留" -> {
                    val leftoverItems = parseTaskItems(field.content, TaskItemMark.TODO)
                    if (leftoverItems.isEmpty()) {
                        SimpleField(field.label, "无", live = animate, blinkAlpha = blinkAlpha)
                    } else {
                        ListField(field.label, field.content, live = animate, blinkAlpha = blinkAlpha, defaultMark = TaskItemMark.TODO)
                    }
                }
                else -> SimpleField(field.label, field.content, live = animate, blinkAlpha = blinkAlpha)
            }
        }
    }
}

/** 生成中(live) → 跟随整卡节奏闪烁（**不开扫光**，用卡片下发的那份透明度）；已收尾 → 纯色静态。
 *  字段**标签**与**正文**共用这一处口径。 */
@Composable
private fun FieldText(
    text: String,
    live: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    color: Color,
    highlightColor: Color,
    modifier: Modifier = Modifier,
    /** 整卡统一闪烁透明度（由 DeliverableCard 计算下发，保证卡内所有文字同步） */
    blinkAlpha: Float = 1f
) {
    if (live) {
        ShimmerText(
            text = text,
            modifier = modifier,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            highlightColor = highlightColor,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            sweep = false,
            blinkAlpha = blinkAlpha
        )
    } else {
        Text(text, fontSize = fontSize, lineHeight = lineHeight, color = color, modifier = modifier)
    }
}

/** 简单字段：左标签 + 右侧多行内容；验证/遗留为占位文案时如实显示「无」 */
@Composable
private fun SimpleField(label: String, content: String, live: Boolean = false, blinkAlpha: Float = 1f) {
    val shown = if (label == "验证" || label == "遗留") {
        if (isVoidText(content)) "无" else content
    } else content
    Row(verticalAlignment = Alignment.Top) {
        FieldText(
            text = label,
            live = live,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Color(0xFF9CA3AF),
            highlightColor = Color(0xFFD6DEE9),
            modifier = Modifier.width(46.dp).padding(top = 1.dp),
            blinkAlpha = blinkAlpha
        )
        Spacer(Modifier.width(8.dp))
        FieldText(
            text = shown,
            live = live,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (label == "验证" || label == "遗留") Color(0xFF6B7280) else Color(0xFF374151),
            highlightColor = Color(0xFFA9BBD4),
            modifier = Modifier.weight(1f),
            blinkAlpha = blinkAlpha
        )
    }
}

/**
 * 任务项状态标记：✓ 已完成（绿）/ ◐ 只做了一部分（琥珀）/ ○ 还没做（灰）/ ✕ 失败（红）。
 * 用固定宽度的居中盒子盛放 —— 这几个字形宽度不一，直接当 Text 会让多行条目的正文左缘参差。
 */
@Composable
private fun TaskMarkGlyph(mark: TaskItemMark, modifier: Modifier = Modifier) {
    val color = when (mark) {
        TaskItemMark.DONE -> Color(0xFF10B981)
        TaskItemMark.PARTIAL -> Color(0xFFD97706)
        TaskItemMark.TODO -> Color(0xFF9CA3AF)
        TaskItemMark.FAILED -> Color(0xFFDC2626)
    }
    val glyph = when (mark) {
        TaskItemMark.DONE -> "✓"
        TaskItemMark.PARTIAL -> "◐"
        TaskItemMark.TODO -> "○"
        TaskItemMark.FAILED -> "✕"
    }
    Box(modifier.width(14.dp), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 11.sp, lineHeight = 18.sp, color = color)
    }
}

/**
 * 清单样式（「已完成」与「遗留」共用）：每行一个条目，行首按状态标记着色。
 * 这是"哪部分没完成"的可见答案 —— 逐项标记 + 角标计数（如「部分完成 3/5」）合起来读，
 * 用户不用再从模型的长句子里找线索。
 */
@Composable
private fun ListField(
    label: String,
    content: String,
    live: Boolean = false,
    blinkAlpha: Float = 1f,
    /** 该栏没有显式标记时的缺省状态：「已完成」栏 → DONE，「遗留」栏 → TODO */
    defaultMark: TaskItemMark = TaskItemMark.DONE
) {
    val items = remember(content, defaultMark) { parseTaskItems(content, defaultMark) }
    if (items.isEmpty()) return
    Row(verticalAlignment = Alignment.Top) {
        FieldText(
            text = label,
            live = live,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Color(0xFF9CA3AF),
            highlightColor = Color(0xFFD6DEE9),
            modifier = Modifier.width(46.dp).padding(top = 1.dp),
            blinkAlpha = blinkAlpha
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            for (item in items) {
                Row(verticalAlignment = Alignment.Top) {
                    TaskMarkGlyph(item.mark, Modifier.padding(end = 6.dp))
                    FieldText(
                        text = item.text,
                        live = live,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFF374151),
                        highlightColor = Color(0xFFA9BBD4),
                        modifier = Modifier.weight(1f),
                        blinkAlpha = blinkAlpha
                    )
                }
            }
        }
    }
}

/**
 * 估算产物胶囊宽度（胶囊内文字 11.sp，左右留白约 16.sp，圆点+间距约 12.sp）。
 * 按字符宽粗略估算：半角按 6.sp、全角按 10.8.sp，宁大勿小避免文本截断。
 * 不再设固定封顶：卡片内可用宽度确定，由布局层按剩余宽度 coerceAtMost 兜底，
 * 内容短就按内容宽展示，内容超长才占满整行并在内部横滑。
 */
private fun estimateProductCapsuleWidth(path: String): TextUnit {
    var w = 0f
    for (c in path) {
        w += if (c.code < 0x80) 6f else 10.8f
    }
    return (w + 28f).sp
}

/** 产物折叠阈值：超过这个数量才渲染「展开全部」按钮（一般任务 1~5 个产物，不该打断阅读） */
private const val PRODUCT_COLLAPSE_THRESHOLD = 6

/** 折叠状态下默认展示的产物条数 */
private const val PRODUCT_PREVIEW_COUNT = 5

/** 产物：可点击小胶囊。统一默认蓝色渲染；删除类点击由上层 onFileRefAction 识别后跳转改动详情定位 */
@Composable
private fun ProductField(
    content: String,
    products: List<ProductRef>,
    productOps: Map<String, String>,
    onFileRefAction: ((String, Int) -> Unit)?,
    /** 生成中：字段标签与「未识别成路径」的兜底文本跟随整卡节奏闪烁；胶囊本体（带底色的可点小药丸）
     *  保持静态 —— 药丸是交互控件不是流动文字，上面闪会显得脏。 */
    live: Boolean = false,
    blinkAlpha: Float = 1f
) {
    Row(verticalAlignment = Alignment.Top) {
        FieldText(
            text = "产物",
            live = live,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Color(0xFF9CA3AF),
            highlightColor = Color(0xFFD6DEE9),
            modifier = Modifier.width(46.dp).padding(top = 1.dp),
            blinkAlpha = blinkAlpha
        )
        Spacer(Modifier.width(8.dp))
        BoxWithConstraints(Modifier.weight(1f)) {
            // 卡片内产物列可用宽度是确定的：胶囊按内容估算，但最多占满整列，多出来的宽度留给右侧正常留白
            val maxCapsuleWidth = maxWidth
            // 产物过多时默认折叠：超过阈值才出现「展开全部」按钮；用户点开后看全部，再点收起。
            // 展开状态用无 key 的 remember：流式输出过程中产物数量持续增长，不能因为数量变化把用户点开的状态重置掉。
            var productsExpanded by remember { mutableStateOf(false) }
            val collapsible = products.size > PRODUCT_COLLAPSE_THRESHOLD
            val visibleProducts = if (collapsible && !productsExpanded) {
                products.take(PRODUCT_PREVIEW_COUNT)
            } else {
                products
            }
            // 只用 fillMaxWidth：fillMaxSize 会把高度撑满父约束，在 ChatImageExporter 的
            // verticalScroll(无界高度) 导出环境下高度为无限大，导致「多选保存含交付卡的消息」生成失败。
            Column(Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
            if (products.isNotEmpty()) {
                // 展开后「收起」放在列表顶部：67 个产物展开后会很长，不能让用户滚到底才能收起
                if (collapsible && productsExpanded) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { productsExpanded = false }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "收起",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1E40AF)
                        )
                    }
                }
                for (ref in visibleProducts) {
                    val path = ref.path
                    // 删除类产物：浅红底红字；修改/新建：默认蓝。op 来源优先级：真实 productOps 映射 > 行尾 (op) 后缀 > 默认修改
                    val op = productOps[path] ?: ref.op
                    val isDelete = op == "deleted" || op == "deleted_folder" || op.contains("删除")
                    val capsuleBg = if (isDelete) Color(0xFFFEE2E2) else Color(0xFFEFF6FF)
                    val capsuleFg = if (isDelete) Color(0xFFDC2626) else Color(0xFF1E40AF)
                    val dotColor = if (isDelete) Color(0xFFDC2626) else Color(0xFF1E40AF)
                    // 按内容估算宽度，最多占满产物列剩余宽度；超出部分在胶囊内部横向滑动
                    val capsuleWidth = with(LocalDensity.current) { estimateProductCapsuleWidth(path).toDp() }
                        .coerceAtMost(maxCapsuleWidth)
                    Row(
                        modifier = Modifier
                            .width(capsuleWidth)
                            .clip(RoundedCornerShape(6.dp))
                            .background(capsuleBg)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onFileRefAction?.invoke(path, 0) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 圆点（统一蓝色）
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(dotColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        // 超长路径：胶囊宽度封顶，内部文字横向滑动查看完整文件名
                        Text(
                            path,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = capsuleFg,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .horizontalScroll(remember { ScrollState(0) })
                        )
                    }
                }
                // 超过阈值才渲染：默认只露前几条，避免几十条产物把交付卡片撑成一屏
                if (collapsible && !productsExpanded) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { productsExpanded = !productsExpanded }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (productsExpanded) {
                                "收起"
                            } else {
                                "展开全部（共 ${products.size} 个，还有 ${products.size - PRODUCT_PREVIEW_COUNT} 个未显示）"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1E40AF)
                        )
                    }
                }
            } else {
                // 产物字段有内容但未识别出路径：以普通文本展示；若只是「无（…）」这类占位，直接收敛为「无」，
                // 不把模型的废话解释（「暂无新增文件」等）原样摊在卡片上（用户要求：无就显示「无」，别加废话）。
                val plain = content.trimListPrefix()
                val shown = if (isVoidText(plain)) "无" else plain
                FieldText(
                    text = shown,
                    live = live,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFF374151),
                    highlightColor = Color(0xFFA9BBD4),
                    blinkAlpha = blinkAlpha
                )
            }
            }
        }
    }
}
