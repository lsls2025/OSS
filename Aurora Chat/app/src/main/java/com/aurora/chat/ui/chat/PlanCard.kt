package com.aurora.chat.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.theme.AuroraPrimary
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「构建」按钮发出的规范执行指令:AI 收到后按方案开工(与 CodeBuddy「确认后执行」机制一致)。
 * UI 按钮与 Plan 模式提示词共用同一句,保证两边机制严丝合缝。
 */
internal const val PLAN_BUILD_COMMAND = "接受计划并立即开始"

/** 方案卡片的单条流程步骤。status: pending 未开始 / doing 进行中 / done 已完成 */
data class PlanStep(val content: String, val status: String = "pending")

/** 方案卡片结构化数据(与 CodeBuddy 方案卡一致:标题 + 概述 + 关键改动点 + 流程步骤)。
 *  public（非 internal）：它要作为参数穿过 public 的 `MessageBubble`，internal 类型会编译报错。 */
data class PlanCardData(
    val title: String = "",
    val overview: String = "",
    val changePoints: List<String> = emptyList(),
    val steps: List<PlanStep> = emptyList(),
    val docPath: String = "", // ai_files 相对路径(用于「查看原文」)
    /** true = 对话尾部的「实时进度卡」：只保留流程步骤，不显示概述/关键改动点/查看原文/构建按钮。
     *  纯渲染期标记，不参与 JSON 序列化（由 preparePlanDisplay 按 PLAN_LIVE_TAG 置位）。 */
    val progressOnly: Boolean = false,
    /** true = 方案 JSON 还在输出中（尾部哨兵未出现）：卡片先出框架、内容边写边填。纯渲染期标记。 */
    val streaming: Boolean = false
)

/** 从 JSON 字符串解析方案卡片数据(与 CodeBuddy 方案卡一致:标题/概述/关键改动点/流程步骤)。 */
internal fun parsePlanJson(json: String): PlanCardData? = try {
    val o = JSONObject(json)
    val cps = mutableListOf<String>()
    o.optJSONArray("change_points")?.let { a -> for (i in 0 until a.length()) cps.add(a.optString(i, "")) }
    val sts = mutableListOf<PlanStep>()
    o.optJSONArray("steps")?.let { a ->
        for (i in 0 until a.length()) {
            val sObj = a.optJSONObject(i) ?: continue
            sts.add(PlanStep(sObj.optString("content", ""), sObj.optString("status", "pending").lowercase()))
        }
    }
    PlanCardData(
        title = o.optString("title", ""),
        overview = o.optString("overview", ""),
        changePoints = cps,
        steps = sts,
        docPath = o.optString("doc_path", "")
    )
} catch (_: Exception) { null }

/**
 * **流式部分解析**：方案 JSON 还在输出中（尾部哨兵尚未出现、JSON 不完整）时，尽量抽出已写完的字段，
 * 让卡片先出框架、内容边写边填，而不是整段 JSON 以纯文本外露。
 *
 * 为什么不用完整解析器：对不完整输入 `JSONObject(...)` 必然抛异常。这里逐个字段抓**已闭合**的字符串。
 */
internal fun parsePlanJsonPartial(raw: String): PlanCardData? {
    fun field(key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(raw)?.groupValues?.get(1)?.jsonUnescape()
    val title = field("title") ?: ""
    val overview = field("overview") ?: ""
    val docPath = field("doc_path") ?: ""
    // change_points：先切出该数组已输出的片段，再抽其中的字符串
    val cps = Regex("\"change_points\"\\s*:\\s*\\[([\\s\\S]*?)(?:\\]|\$)")
        .find(raw)?.groupValues?.get(1)
        ?.let { arr -> Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(arr).map { it.groupValues[1].jsonUnescape() }.toList() }
        ?: emptyList()
    // steps：抓 {"content":"...","status":"..."} 已完整的部分（status 还没写出来时按 pending）
    val stepsRaw = Regex("\"steps\"\\s*:\\s*\\[([\\s\\S]*)").find(raw)?.groupValues?.get(1) ?: ""
    val steps = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"(?:\\s*,\\s*\"status\"\\s*:\\s*\"([a-zA-Z]*)\")?")
        .findAll(stepsRaw)
        .map { m -> PlanStep(m.groupValues[1].jsonUnescape(), m.groupValues[2].ifBlank { "pending" }.lowercase()) }
        .toList()
    if (title.isBlank() && overview.isBlank() && steps.isEmpty() && cps.isEmpty()) return null
    return PlanCardData(title = title, overview = overview, changePoints = cps, steps = steps, docPath = docPath)
}

/**
 * 清理「卡片消息」正文里的模型输出毛刺（**渲染期**处理，所以历史消息也会一起变干净）。
 *
 * 用户实际反馈到的两种现象：
 *  1) `[[/PLAN` 这种**残缺哨兵**外露 —— 模型把哨兵写断了/写重复了；
 *     配对解析只吃"第一对"哨兵，剩下的碎片会以纯文本外露（页面上就是一条消息里挂着 `[[/PLAN`）。
 *  2) 卡片块被剥离后，原地留下**孤立的标点行**（单独一行的 `，` 或 `,`）与**连续空行**
 *     —— 表现成"两段话中间隔了 3 个空行，第 3 个空行里还有个逗号"。
 *
 * ⚠️ **只在该消息确实含卡片时调用**（普通聊天文本原样不动，避免误伤）。
 */
internal fun tidyCardBodyText(s: String): String {
    if (s.isEmpty()) return s
    val cleaned = s
        .lines()
        // 丢弃"整行只有标点"的残留行（剥卡片时留下的孤立逗号等）。
        // 刻意不把 - * + > 等 Markdown 符号算进来，避免误删分隔线/列表符号。
        .filterNot { l ->
            val t = l.trim()
            t.isNotEmpty() && t.all { it in ",，.。;;、:：" }
        }
        .joinToString("\n")
    // 连续空行压到一个空行
    return cleaned.replace(Regex("\n{3,}"), "\n\n").trim()
}

/**
 * 去掉**方案卡片哨兵的残留**：多余/重复的完整卡片块（连 JSON 一起）+ 残缺碎片（如 `[[/PLAN`）。
 *
 * ⚠️⚠️ **只能在「方案卡已解析完毕」之后调用**（即 [preparePlanDisplay] 内部）。
 * 渲染管线是 `prepareDeliverableDisplay` → `preparePlanDisplay`（见 MessageBubble），
 * 交付卡解析器拿到的正文里，方案卡块**还是完好**的（下一步才解析它）。
 * 如果在那里就清哨兵，等于把方案卡整块删掉 —— 症状正是：
 * **所有方案卡 + 任务流程卡全部不渲染**（`planData` 恒为 null）。
 */
private val PLAN_BLOCK_RE = Regex("\\[\\[\\s*PLAN_CARD\\s*\\]\\][\\s\\S]*?\\[\\[\\s*/\\s*PLAN_CARD\\s*\\]\\]")
private val PLAN_FRAGMENT_RE = Regex("\\[\\[\\s*/?\\s*PLAN(_CARD)?\\s*\\]*")

internal fun stripPlanSentinelFragments(s: String): String =
    if (s.isEmpty()) s else s.replace(PLAN_BLOCK_RE, "").replace(PLAN_FRAGMENT_RE, "")

/** JSON 字符串常用转义还原（仅用于流式部分解析的展示） */
private fun String.jsonUnescape(): String = replace("\\n", "\n").replace("\\t", "\t")
    .replace("\\r", "").replace("\\\"", "\"").replace("\\\\", "\\")

/** 把方案卡片数据序列化为 JSON 字符串(与 [parsePlanJson] 对称),用于把步骤状态回写进消息 text 的 [[PLAN_CARD]] 块。 */
internal fun serializePlanJson(data: PlanCardData): String = JSONObject().apply {
    put("title", data.title)
    put("overview", data.overview)
    put("change_points", JSONArray().apply { data.changePoints.forEach { put(it) } })
    put("steps", JSONArray().apply {
        data.steps.forEach { s -> put(JSONObject().put("content", s.content).put("status", s.status)) }
    })
    put("doc_path", data.docPath)
}.toString()

/** 从消息 text 中解析方案卡片;若不含 [[PLAN_CARD]] 哨兵则返回 null。 */
internal fun parsePlanCard(text: String): PlanCardData? {
    val open = "[[PLAN_CARD]]"
    val close = "[[/PLAN_CARD]]"
    val s = text.indexOf(open)
    val e = text.indexOf(close)
    if (s < 0 || e < 0 || e <= s) return null
    return parsePlanJson(text.substring(s + open.length, e))
}

/**
 * 从 AI 消息文本中剥离 [[PLAN_CARD]] 方案块：解析为 [PlanCardData] 交给卡片渲染，
 * 正文不再外露哨兵；未成形则返回 null 且回退原文。与 prepareDeliverableDisplay 同构
 * (方案即「助手消息本身」内联成卡片，重开会话/刷新后由正文哨兵重新渲染——天然持久化)。
 */
/** 实时进度卡标记：贴在消息最前面，纯内部标记，渲染前剥离（避免以纯文本外露）。
 *  作用：方案执行中始终在对话**尾部**保留一张「当前进度」卡，用户不必往上翻历史才能看到状态。 */
internal const val PLAN_LIVE_TAG = "[[PLAN_LIVE]]"

internal fun preparePlanDisplay(text: String): Pair<PlanCardData?, String> {
    // 实时进度卡:先剥掉纯内部标记,再按普通方案卡处理
    val isLive = text.contains(PLAN_LIVE_TAG)
    val cleaned = if (isLive) text.replace(PLAN_LIVE_TAG, "") else text
    val open = "[[PLAN_CARD]]"
    val close = "[[/PLAN_CARD]]"
    val s = cleaned.indexOf(open)
    // 没有完整卡片、但正文里残留残缺哨兵碎片（模型把哨兵写断/写重）→ 也清掉，避免 "[[/PLAN" 外露
    if (s < 0) {
        // 本函数是方案哨兵的**最终消费者**，此处残留的哨兵一定是碎片，可以安全清除
        if (!cleaned.contains("[[PLAN") && !cleaned.contains("[[/PLAN")) return null to cleaned
        return null to tidyCardBodyText(stripPlanSentinelFragments(cleaned))
    }
    val after = cleaned.substring(s + open.length)
    val e = cleaned.indexOf(close, s)
    val parsed: PlanCardData?
    val bodyText: String
    if (e > s) {
        // 已闭合：完整 JSON 解析
        parsed = parsePlanJson(after.substring(0, e - (s + open.length)))
        bodyText = tidyCardBodyText(
            stripPlanSentinelFragments(cleaned.substring(0, s) + cleaned.substring(e + close.length))
        )
    } else {
        // ⚠️ 流式未闭合（尾部哨兵还没输出）：
        // 旧写法直接 return null → 整段方案 JSON 以**纯文本**外露，卡片要等尾哨兵出现才渲染。
        // 现在：只要头部哨兵后面紧跟 `{`（确认是卡片 JSON，避免把正文误吞进卡片），就先搭框架，
        // 用字段级容错把已写完的部分填进去，AI 边写边填；尾部哨兵出现后自动切到完整解析。
        if (!after.trimStart().startsWith("{")) return null to cleaned
        val jsonEnd = after.lastIndexOf('}')
        val jsonPart = if (jsonEnd >= 0) after.substring(0, jsonEnd + 1) else after
        val partial = parsePlanJsonPartial(jsonPart)?.copy(streaming = true) ?: return null to cleaned
        parsed = partial
        // 「最后一个 } 之前」算卡片、之后算正文：模型写完后继续输出正文时不会被吞进卡片
        val rest = if (jsonEnd >= 0) after.substring(jsonEnd + 1) else ""
        bodyText = tidyCardBodyText(stripPlanSentinelFragments(cleaned.substring(0, s) + rest))
    }
    // 尾部「实时进度卡」只保留**流程**：标题固定「任务流程」，丢掉概述/关键改动点/原文路径
    // （用户此刻要看的只是"进行到第几步"，不需要再重放一遍方案的说明与改动点）
    val data = if (isLive && parsed != null) parsed.copy(
        progressOnly = true,
        title = "任务流程",
        overview = "",
        changePoints = emptyList(),
        docPath = ""
    ) else parsed
    return data to bodyText
}

/**
 * 方案卡片:照抄 CodeBuddy 方案卡(白底圆角 + 左侧品牌蓝装饰条 + 标题/概述/关键改动点/流程步骤 + 底部操作区)。
 *
 * 布局要点(针对"挤在左边、右边留白、上下过长"的反馈):
 *  - 用满气泡可用宽度(fillMaxWidth),不再自行封顶 300.dp;
 *  - 头部右侧用「已完成/总步数」占满原先空白的右侧区域;
 *  - 分区间距由 10~14dp 收紧到 6dp、字号整体下调,避免竖向过长。
 */
@Composable
internal fun PlanCard(
    data: PlanCardData,
    /** 任务已整体完成时置 true:所有流程步骤勾选为已完成(对应 CodeBuddy 执行完毕后的勾选态) */
    allDone: Boolean = false,
    onOpenDoc: (() -> Unit)? = null,
    /** 点击「构建」:由上层发送一条规范的执行指令,驱动 AI 按方案开工(与 CodeBuddy 确认后执行一致) */
    onPlanBuild: (() -> Unit)? = null,
    /** 点击「✎ 编辑」:由上层弹出编辑框修改方案(增/删/改步骤)。**仅未开工时可用**，null=不提供该能力 */
    onPlanEdit: (() -> Unit)? = null
) {
    // 收起态对实时进度卡 = 「只显示前 PROGRESS_PREVIEW_STEPS 步」（不是整卡消失），所以默认收起即可
    var expanded by remember { mutableStateOf(false) }
    // 构建是否已触发:触发后按钮置灰禁用(用户已确认开工,无需重复点击)
    var built by remember { mutableStateOf(false) }
    // 主题色用品牌蓝(AuroraPrimary),与交付卡片等产品内既有卡片保持一致,不用紫色
    val brand = AuroraPrimary
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 左侧强调色装饰条：直接画在 Row 背景上，不占位也不依赖 fillMaxHeight。
                // fillMaxHeight 在 ChatImageExporter 的 verticalScroll(无界高度) 导出环境下
                // 会把内容高度撑到无限大，导致「多选保存含方案卡的消息」生成失败。
                .drawBehind {
                    drawRect(brand, topLeft = Offset.Zero, size = Size(4.dp.toPx(), size.height))
                }
        ) {
            Spacer(Modifier.width(4.dp))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth()) {
                // 头部:徽标 + 标题(占满) + 右侧步骤进度(吃满右边空白)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(5.dp)).background(brand.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(if (data.progressOnly) "进度" else "方案", fontSize = 10.sp, color = brand, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        data.title.ifEmpty { "方案" },
                        fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1F2330),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    if (data.steps.isNotEmpty()) {
                        val doneCnt = if (allDone) data.steps.size else data.steps.count { it.status == "done" }
                        Text(
                            "$doneCnt/${data.steps.size} 步",
                            fontSize = 11.sp,
                            color = if (allDone) Color(0xFF16A34A) else Color(0xFF94A3B8)
                        )
                    }
                }
                if (data.streaming) {
                    // 方案 JSON 还在输出中：先出框架，让用户立刻看到卡片而不是一段裸 JSON
                    Spacer(Modifier.height(4.dp))
                    Text("正在生成方案…", fontSize = 11.sp, color = Color(0xFF94A3B8))
                }
                // 逐条隐藏：**已去掉**。完成一步就把它从列表里摘掉，会让人看不出进度全貌
                // （用户的原话："他勾了就勾了，他干嘛要隐藏掉"）。现在**所有步骤始终显示**，
                // 已完成的只把方框变成绿色 ✓，条目本身保留。
                // 序号用**方案里的原始序号**（不是过滤后的下标）：与 AI 调 update_plan_step 时
                // 所说的「第 N 步」永远一致（不再因为隐藏而错位）。
                val numbered = data.steps.withIndex().map { (i, st) -> (i + 1) to st }

                /** 流程步骤区（展开/收起两态复用）。limit<0 = 全部；limit>0 = 只显示前 limit 步 */
                val stepsBlock: @Composable (Int) -> Unit = { limit ->
                    if (numbered.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        // 实时进度卡的标题已经是「任务流程」，这里不再重复一个「流程」小节标题
                        if (!data.progressOnly) SectionLabel("流程")
                        Spacer(Modifier.height(3.dp))
                        val shown = if (limit in 1 until numbered.size) numbered.take(limit) else numbered
                        shown.forEach { (no, step) ->
                            val st = if (allDone) "done" else step.status
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                                Box(
                                    Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                                        .background(stepColor(st, brand)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // 严格 lineHeight = 字号+1 + 文本居中：消除默认行高把符号压到框底部的问题
                                    // （项目内其它方形/圆形徽标里的 ✕/✓ 用的是同一手法）
                                    if (st == "doing") {
                                        // 「进行中」用**图形**而非字体符号：字体符号(◐)受基线/行高影响，
                                        // 既显得小、又总是偏下，调字号治不本。这里在方框里居中画白色实心圆点，
                                        // 位置由容器居中保证，尺寸精确可控。
                                        Box(
                                            Modifier.size(9.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(Color.White)
                                        )
                                    } else {
                                        Text(
                                            stepGlyph(st),
                                            fontSize = 10.sp,
                                            color = Color.White,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            style = androidx.compose.ui.text.TextStyle(lineHeight = 11.sp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(7.dp))
                                Text("$no. ${step.content}", fontSize = 12.sp, color = Color(0xFF1F2330),
                                    lineHeight = 17.sp, modifier = Modifier.weight(1f))
                            }
                        }
                        if (shown.size < numbered.size) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "…还有 ${numbered.size - shown.size} 步（点下方「展开全部」查看）",
                                fontSize = 11.sp, color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }

                if (data.progressOnly) {
                    // ⚠️ 实时进度卡**任何状态都必须显示流程**（收起 = 只显示前 PROGRESS_PREVIEW_STEPS 步）。
                    // 旧写法把它交给"收起分支"，而收起分支只渲染概述/关键改动点——这两个在进度卡里已被清空，
                    // 于是收起态只剩一个标题 → 用户看到的就是"任务一完成，这条任务流程直接消失了"。
                    stepsBlock(if (expanded) -1 else PROGRESS_PREVIEW_STEPS)
                } else if (expanded) {
                    // 展开态:概述 + 关键改动点 + 流程(步骤,逐条隐藏已完成项)
                    if (data.overview.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(data.overview, fontSize = 12.sp, color = Color(0xFF5B6172), lineHeight = 18.sp)
                    }
                    if (data.changePoints.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        SectionLabel("关键改动点")
                        Spacer(Modifier.height(2.dp))
                        data.changePoints.forEach { cp ->
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 1.dp)) {
                                Text("•", fontSize = 12.sp, color = brand, modifier = Modifier.padding(end = 5.dp))
                                Text(cp, fontSize = 12.sp, color = Color(0xFF5B6172), lineHeight = 17.sp,
                                    modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    stepsBlock(-1)
                } else {
                    // 收起态:只展示「概述(目标/关键改动点)」,不渲染流程;关键改动点过多时截断避免过长(对齐 2b)
                    if (data.overview.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(data.overview, fontSize = 12.sp, color = Color(0xFF5B6172), lineHeight = 18.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (data.changePoints.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        val shown = data.changePoints.take(3)
                        shown.forEach { cp ->
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 1.dp)) {
                                Text("•", fontSize = 12.sp, color = brand, modifier = Modifier.padding(end = 5.dp))
                                Text(cp, fontSize = 12.sp, color = Color(0xFF5B6172), lineHeight = 17.sp,
                                    modifier = Modifier.weight(1f))
                            }
                        }
                        if (data.changePoints.size > shown.size) {
                            Text("…共 ${data.changePoints.size} 条关键改动点", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }
                }
                // 底部操作区(始终展示):左「展开/收起」、右「查看原文」「构建」
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // 实时进度卡：收起 = 只显示前几步，所以标签用「展开全部」
                        when {
                            data.progressOnly && !expanded -> "展开全部 ▾"
                            expanded -> "收起 ▴"
                            else -> "展开 ▾"
                        },
                        fontSize = 12.sp, color = Color(0xFF94A3B8),
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                    // 「✎ 编辑」：**只在未开工时**出现 —— 一旦有步骤已完成 / 点过构建 / 正在流式生成，就不许再改
                    // （用户要求：方案能在构建前修改，但已完成或正在执行的方案不能改）。
                    val canEdit = onPlanEdit != null && !data.progressOnly && !data.streaming && !built && !allDone &&
                        data.steps.none { it.status == "done" || it.status == "doing" }
                    if (canEdit) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "✎ 编辑",
                            fontSize = 12.sp, color = brand, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { onPlanEdit?.invoke() }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (onOpenDoc != null && data.docPath.isNotEmpty() && !data.progressOnly) {
                        Text(
                            "查看原文 ›", fontSize = 12.sp, color = brand, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { onOpenDoc() }
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    // 实时进度卡不提供「构建」按钮(方案已在执行中)
                    if (onPlanBuild != null && !data.progressOnly) {
                        // 已点过构建 / 整体完成 / **确实有步骤已完成** → 禁用并置灰,防止重复开工。
                        // ⚠️ 这里刻意**不**把"有步骤处于 doing"当作"已开工":doing 可能来自模型在用户
                        // 点构建之前的误标,一旦据此禁用,按钮会变灰点不动(用户直接卡死,无法开工)。
                        // 只有出现真正完成的步骤(done)才视为执行确实开始 —— 也让历史里被误标 doing 的
                        // 方案卡片自动恢复可点。
                        val started = !allDone && data.steps.any { it.status == "done" }
                        val disabled = built || allDone || started
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (disabled) Color(0xFFCBD5E1) else brand)
                                .clickable(enabled = !disabled) { built = true; onPlanBuild?.invoke() }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text("构建", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 12.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium)
}

/** 实时进度卡默认展示的步骤条数（超出则提示「展开全部」） */
private const val PROGRESS_PREVIEW_STEPS = 5

private fun stepColor(status: String, brand: Color): Color = when (status) {
    "done" -> Color(0xFF16A34A)
    "doing" -> brand
    else -> Color(0xFFE5E7EB)
}

private fun stepGlyph(status: String): String = when (status) {
    "done" -> "✓"
    "doing" -> "◐"
    else -> ""
}
