package com.aurora.chat.ui.chat

/**
 * 交付协议 v2 —— 交付总结的**唯一归一化层**（架构收口）。
 *
 * 背景（用户实测的两个顽疾）：
 *  ① 一张交付卡片里出现**两套字段**：模型在同一个哨兵块内写两遍、写多个哨兵块、
 *    或者块内写一套 + 块外又写一套 —— 旧解析器「每遇到字段头就新建一个字段」，
 *    于是同一张卡里出现两组「目标/已完成/产物/验证/遗留」。
 *  ② 卡片之外还在正文里**复述交付内容**（「产物：…」「遗留：…」直接裸露在卡片下方）。
 *    旧设计把块外正文原样渲染，契约也没有禁止块外复述。
 *
 * 本层用**确定性规则**根治，不依赖模型是否听话：
 *  · [parseFields]：按**规范标签**合并 —— 每种标签最多产出一个字段（同义标签也会归位）；
 *    清单条目按键去重；「遗留」里标了 `[x]` 的项归回「已完成」；两栏重复的条目只保留「已完成」那份。
 *    因此无论模型写几套，卡片上都只有一套。
 *  · [absorbBodyDelivery]：在块外正文里识别交付式段落，**吸收进卡片内容**并从正文删除。
 *    同一份信息不可能既在卡片里、又在正文里出现第二遍。
 *
 * 提示词（[AITaskCompletion.buildContractPrompt]）只负责降低触发概率，正确性由本层保证。
 */
internal object DeliveryProtocol {

    /** 卡片字段的规范顺序：无论模型按什么顺序写，最终都按这个顺序渲染 */
    val FIELD_ORDER: List<String> = listOf("目标", "已完成", "产物", "验证", "遗留", "限制", "备注", "说明")

    /**
     * 段分隔符：交付内容由"多个交付块 + 多个块外吸收段"拼成，用它在拼接时保留段边界，
     * 供 [parseFields] 做正确的跨段合并（清单取并集，文本与「遗留」以最后一段为准）。
     * 用 \u0002 而非换行，避免与正文/块内容里的空行混淆。
     */
    const val SECTION_SEP = "\u0002SEC\u0002"

    /** 契约强制的 5 个核心字段：块外清洗只认这些，避免误伤正文里正常的「备注：…」 */
    private val CORE_LABELS: Set<String> = setOf("目标", "已完成", "产物", "验证", "遗留")

    /** 同义字段名 → 规范标签（块内宽松识别：模型爱用「任务目标」「完成情况」「交付物」等变体） */
    private val ALIASES: Map<String, String> = mapOf(
        "任务目标" to "目标", "目标概述" to "目标", "用户目标" to "目标", "任务需求" to "目标",
        "需求" to "目标", "目的" to "目标", "任务" to "目标",
        "完成情况" to "已完成", "完成事项" to "已完成", "已完成事项" to "已完成", "已做" to "已完成",
        "完成内容" to "已完成", "已完成的工作" to "已完成", "完成的工作" to "已完成", "已完成项" to "已完成",
        "交付物" to "产物", "产出" to "产物", "产出物" to "产物", "产物清单" to "产物",
        "输出文件" to "产物", "生成物" to "产物", "交付清单" to "产物",
        "验证结果" to "验证", "校验" to "验证", "校验结果" to "验证", "自检" to "验证",
        "验证方式" to "验证", "确认" to "验证", "检查结果" to "验证",
        "遗留问题" to "遗留", "未完成" to "遗留", "未完成事项" to "遗留", "待办" to "遗留",
        "待办事项" to "遗留", "剩余" to "遗留", "后续" to "遗留", "待处理" to "遗留", "未尽事项" to "遗留",
        "限制条件" to "限制", "已知限制" to "限制", "约束" to "限制",
        "注意事项" to "备注", "补充" to "备注", "补充说明" to "备注",
        "详细说明" to "说明", "备注说明" to "说明"
    )

    /** 块外清洗**只认**这些高置信别名（普通正文里出现「需求：…」「任务：…」不该被当成交付段） */
    private val STRICT_ALIASES: Map<String, String> = mapOf(
        "交付物" to "产物", "产物清单" to "产物", "产出" to "产物", "输出文件" to "产物",
        "完成情况" to "已完成", "已完成事项" to "已完成", "完成事项" to "已完成",
        "验证结果" to "验证", "校验结果" to "验证", "自检结果" to "验证",
        "遗留问题" to "遗留", "未完成事项" to "遗留", "待办事项" to "遗留"
    )

    /** 字段名尾巴上的噪声（「产物如下」「验证结果」），去掉后再归位 */
    private val NOISE_SUFFIX_RE = Regex("(如下|以下|清单|列表|内容|情况|结果|信息|详情|明细)$")

    /**
     * 字段头：容忍列表符号、编号、Markdown 加粗、括号注释、中英文冒号。
     * 只负责切出「标签」与「行内内容」，标签是否合法交给 [canonicalLabel] 判定。
     */
    private val HEADER_RE = Regex(
        """^\s*(?:[-•·*>+]\s*)?(?:\d{1,2}[.、)）]\s*)?(?:\*\*|__)?\s*([^:：\s*_|`]{1,14})\s*""" +
            """(?:[（(][^）)]{0,10}[）)])?\s*(?:\*\*|__)?\s*[:：]\s*(?:\*\*|__)?\s*(.*)$"""
    )

    /** 块外「交付总结」残标题（模型在块外又写一遍小标题） */
    private val SUMMARY_ECHO_RE = Regex("^[*#\\s]*交付总结[*#\\s]*[:：]?\\s*$")

    /** 列表项前缀（用于判断某行是不是上一个字段的续行） */
    private val LIST_LIKE_RE = Regex("""^\s*(?:[-•*·>+]|\d{1,2}[.、)）]|\[[xX~ !]\]|✅|✔|☐|□)\s*""")

    /** 字段头命中结果：label 为空表示「识别为交付式段落但无法归位」（只清正文、不进卡片） */
    internal data class HeaderMatch(
        val label: String?,
        val content: String,
        val echo: Boolean = false
    )

    /** 块外正文吸收结果：clean = 去掉交付段后的正文；extracted = 被吸收的交付段原文 */
    internal data class BodyAbsorb(val clean: String, val extracted: String)

    // ==================== 标签归一 ====================

    /**
     * 把模型写的字段名归一成规范标签。
     *
     * @param strict true = 只认规范标签 + 高置信别名（用于块外正文，避免误伤普通句子）；
     *               false = 宽松（用于块内，同义词/变体都尽量归位）。
     * @return 规范标签；无法归位返回 null。
     */
    fun normalizeLabel(raw: String, strict: Boolean = false): String? =
        canonicalLabel(raw, strict)

    private fun canonicalLabel(token: String, strict: Boolean): String? {
        var t = token.trim()
            .trim('*', '_', '#', ' ', '`', '【', '】', '[', ']', '-', '•', '·', '>')
            .replace(Regex("\\s+"), "")
        if (t.isEmpty() || t.length > 14) return null
        // 去掉「(必填)」「（可选）」这类注释
        t = t.replace(Regex("[（(][^）)]{0,10}[）)]"), "")
        FIELD_ORDER.firstOrNull { it == t }?.let { return it }
        if (strict) {
            STRICT_ALIASES[t]?.let { return it }
        } else {
            ALIASES[t]?.let { return it }
        }
        val core = t.replace(NOISE_SUFFIX_RE, "")
        if (core != t && core.isNotEmpty()) {
            FIELD_ORDER.firstOrNull { it == core }?.let { return it }
            if (strict) STRICT_ALIASES[core]?.let { return it } else ALIASES[core]?.let { return it }
        }
        // 兜底：整串里只含一个核心标签（「产物说明」「完成情况说明」）→ 归位
        val hits = CORE_LABELS.filter { t.contains(it) }
        if (hits.size == 1) return hits[0]
        return null
    }

    /**
     * 整行是否"恰好是一个字段名"（允许列表符号、加粗、标题井号、尾随冒号，但不允许任何其它内容）。
     * 用于识别「产物」「**遗留**」「## 验证」这类裸标签行；`已完成 A` 这种带内容的行返回 null。
     */
    private fun bareLabel(line: String): String? {
        var t = line.trim()
            .trim('*', '_', '#', ' ', '`', '【', '】', '[', ']', '-', '•', '·', '>', ':', '：')
            .replace(Regex("\\s+"), "")
        if (t.isEmpty() || t.length > 14) return null
        t = t.replace(NOISE_SUFFIX_RE, "")
        FIELD_ORDER.firstOrNull { it == t }?.let { return it }
        ALIASES[t]?.let { return it }
        return null
    }

    /**
     * 判断一行是不是交付字段头。
     * @return null = 不是字段头（按正文/续行处理）；非空 = 命中（label 可能为 null，见 [HeaderMatch]）。
     */
    fun matchHeader(line: String, strict: Boolean): HeaderMatch? {
        val t = line.trim()
        if (t.isEmpty()) return null
        if (SUMMARY_ECHO_RE.matches(t)) return HeaderMatch(null, "", echo = true)
        // 「产物」「**遗留**」「## 验证」这种**裸标签行**（后面没有冒号、内容在下一行清单里）：
        // 用户实测 AI 会在块外单独写一行「产物」「遗留」再跟清单 —— 只靠冒号识别会漏掉它。
        // 只认"整行就是一个合法字段名"，像「已完成 A」这种带内容的行绝不误判。
        bareLabel(t)?.let { return HeaderMatch(it, "") }
        val m = HEADER_RE.find(t) ?: return null
        val token = m.groupValues[1]
        val content = m.groupValues[2].trim()
        canonicalLabel(token, strict)?.let { return HeaderMatch(it, content) }
        // 「产物和遗留：…」「目标已完成：…」这类把多个标签写在一行的，无法安全归位：
        // 标记为 echo（块外只清除、不进卡片；块内直接丢弃），避免污染任何一栏。
        val hits = CORE_LABELS.filter { token.contains(it) }
        if (hits.size >= 2) return HeaderMatch(null, content, echo = true)
        if (hits.size == 1 && token.length <= 14) return HeaderMatch(hits[0], content)
        return null
    }

    /** 常见「散文起句」：出现这些开头就不再算上一个字段的续行，避免把解释吞进卡片 */
    private val PROSE_LEAD_RE = Regex(
        "^(以上|这就|这是|如果|若|需要|建议|另外|此外|同时|因此|所以|总之|目前|现在|已经|后续|接下来|不过|但是|但|还有|请|可以|注意|说明)"
    )

    /** 产物续行只认"像路径/文件名"的行，避免把一句解释渲染成产物胶囊 */
    private val PATH_LIKE_RE = Regex("""[^\s，。；：]{1,200}\.(?:[A-Za-z0-9]{1,8})(?:\s*[（(][^）)]{0,12}[)）])?$""")

    /** 该行是否是上一个字段的续行（列表项 / 缩进 / 没有句末标点的短行） */
    private fun isContinuationLine(line: String, label: String?): Boolean {
        if (LIST_LIKE_RE.find(line) != null) return true
        if (line.startsWith("  ") || line.startsWith("\t") || line.startsWith("　")) return true
        val t = line.trim()
        if (t.isEmpty()) return false
        if (label == "产物") {
            // 产物栏最怕"把一句解释渲染成产物胶囊"，所以额外要求像路径/文件名、且排除散文起句
            if (PROSE_LEAD_RE.containsMatchIn(t)) return false
            return (PATH_LIKE_RE.containsMatchIn(t) || t.contains('/') || t.contains('\\')) &&
                !t.contains('。') && t.length <= 160
        }
        val end = t.trimEnd()
        return end.isNotEmpty() && end.last() !in "。！？.!?；;"
    }

    // ==================== 字段解析（合并 / 去重 / 归位） ====================

    /**
     * 把交付块内部文本解析成**唯一一套**字段。
     *
     * 与旧实现的区别：旧实现每遇到一个字段头就新增一个字段（同名标签会重复渲染、重复计数）；
     * 这里按规范标签合并，最终每种标签最多一个字段，且固定按 [FIELD_ORDER] 顺序返回。
     */
    fun parseFields(inner: String): List<DeliverableField> {
        // 入口先清「哨兵抄成明文」的残留与 Markdown 围栏残线（沿用旧实现的两层保护）
        val cleaned = stripStrayDeliverMarkers(inner)
            .lines()
            .filterNot { it.trim().startsWith("```") }
            .joinToString("\n")
            .trim()
        if (cleaned.isEmpty()) return emptyList()

        // **按段解析**：parseDeliveryBlocks 用 [SECTION_SEP] 把"每个交付块 / 每个块外吸收段"隔开。
        // 这样跨段的合并语义才正确：
        //  · 清单（已完成 / 产物）：并集 + 去重 —— 不丢任何产物；
        //  · 文本（目标 / 验证…）与「遗留」：以**最后一段**为准 —— 模型后写的结论覆盖之前的草稿，
        //    避免"上一轮写过的旧遗留"被并集带回来、把已完成的任务压成部分完成。
        val sections = cleaned.split(SECTION_SEP).map { it.trim() }.filter { it.isNotEmpty() }
        if (sections.isEmpty()) return emptyList()

        val textSlots = LinkedHashMap<String, String>()
        val doneBlocks = mutableListOf<List<String>>()
        val productBlocks = mutableListOf<List<String>>()
        var leftoverBlocks: List<String>? = null

        for (sec in sections) {
            val ps = parseSection(sec)
            for ((k, v) in ps.texts) textSlots[k] = v
            ps.lists["已完成"]?.let { doneBlocks.add(it) }
            ps.lists["产物"]?.let { productBlocks.add(it) }
            // 只有"出现过「遗留」字段"的段才覆盖：最后一段没有这一栏时，沿用最近的上一段
            ps.lists["遗留"]?.let { leftoverBlocks = it }
        }

        // ── 清单栏归一：合并多套 → 条目去重 → 「遗留」里标完成的项归回「已完成」 → 跨栏去重 ──
        // 「已完成」栏还要再过一道**纯过程动作**过滤（用户反馈：切换模式/保持会话/列出目录/
        // 搜索内容/读取文件这种中间动作被当成已完成写进交付总结，把真正的成果稀释掉了）。
        var done = dedupeTaskLines(doneBlocks.flatMap { flattenLines(it) })
            .filterNot { isPureProcessAction(it) }
        val leftoverAll = dedupeTaskLines(leftoverBlocks?.let { flattenLines(it) }.orEmpty())
        // 模型常把已经做完的项也写进「遗留」（还带着 [x]/✅）：那不是遗留，归回「已完成」
        val doneInLeftover = leftoverAll.filter { markOf(it) == TaskItemMark.DONE }
        val leftoverReal = leftoverAll.filter { markOf(it) != TaskItemMark.DONE }
        if (doneInLeftover.isNotEmpty()) done = dedupeTaskLines(done + doneInLeftover)
            .filterNot { isPureProcessAction(it) }

        val doneKeys = done.map { itemKey(it) }.filter { it.isNotEmpty() }
        // 两栏说同一件事：保留「已完成」那份（否则同一件事既算完成又算遗留，状态必然失真）
        val leftover = leftoverReal
            .filterNot { keyDuplicated(it, doneKeys) }
            // 遗留栏的越界闲聊（主动请缨/把球踢回用户）逐项清洗；清洗后为空 = 该项本来就不是遗留
            .map { sanitizeLeftoverContent(it) }
            .filter { it.isNotBlank() }
            .filterNot { isVoidText(it) }

        // 产物行同样按"忽略空白/大小写"去重：两套交付把同一个路径写两遍时，卡片里不留重复行
        val productsRaw = productBlocks.flatMap { flattenLines(it) }
            .distinctBy { it.lowercase().replace(Regex("\\s+"), "") }

        val result = ArrayList<DeliverableField>(FIELD_ORDER.size)
        for (label in FIELD_ORDER) {
            when (label) {
                "已完成" -> if (done.isNotEmpty()) {
                    result.add(DeliverableField(label, done.joinToString("\n")))
                }
                "产物" -> if (productsRaw.isNotEmpty()) {
                    result.add(DeliverableField(label, productsRaw.joinToString("\n")))
                }
                "遗留" -> if (leftoverBlocks != null) {
                    val content = if (leftover.isEmpty()) "无" else leftover.joinToString("\n")
                    result.add(DeliverableField(label, content))
                }
                else -> textSlots[label]?.let { result.add(DeliverableField(label, it)) }
            }
        }
        return result
    }

    /** 单段内的字段切分（不做跨段合并）：texts 后写覆盖先写；lists 按标签累积多份原文 */
    private data class ParsedSection(
        val texts: LinkedHashMap<String, String>,
        val lists: LinkedHashMap<String, MutableList<String>>
    )

    private fun parseSection(section: String): ParsedSection {
        val texts = LinkedHashMap<String, String>()
        val lists = LinkedHashMap<String, MutableList<String>>()
        var cur: String? = null
        var discarding = false
        val buf = StringBuilder()

        fun flush() {
            val label = cur ?: return
            val raw = buf.toString().trim()
            cur = null
            buf.setLength(0)
            if (raw.isEmpty()) return
            when (label) {
                "已完成", "产物", "遗留" -> lists.getOrPut(label) { mutableListOf() }.add(raw)
                else -> texts[label] = raw
            }
        }

        for (rawLine in section.split("\n")) {
            val m = matchHeader(rawLine, strict = false)
            when {
                m?.label != null -> {
                    flush()
                    discarding = false
                    cur = m.label
                    if (m.content.isNotEmpty()) buf.append(m.content)
                }
                // 无法归位的字段头（多标签拼在一起）：整段丢弃，绝不并入上一栏造成串味
                m != null -> {
                    flush()
                    discarding = true
                }
                discarding -> Unit
                cur != null -> {
                    val line = rawLine.trim()
                    if (line.isNotEmpty()) {
                        if (buf.isNotEmpty()) buf.append('\n')
                        buf.append(line)
                    }
                }
            }
        }
        flush()
        return ParsedSection(texts, lists)
    }

    private fun flattenLines(blocks: List<String>?): List<String> =
        blocks.orEmpty().flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }

    /** 条目比较键：去标记 / 去列表符号 / 去空白与标点 / 小写 */
    private fun itemKey(line: String): String {
        var t = line.trim()
        t = MARK_PREFIX_RE.replace(t, "")
        t = t.trimListPrefix()
        t = t.replace(Regex("""^\d{1,2}[.、)）]\s*"""), "")
        t = t.replace(Regex("""\s+"""), "")
        t = t.replace(Regex("""[，。．,.;；:：!！?？、\-—_（）()\[\]【】「」『』]"""), "")
        return t.lowercase()
    }

    private fun markOf(line: String): TaskItemMark? {
        val m = MARK_PREFIX_RE.find(line) ?: return null
        return when (m.groupValues[1].trim().lowercase()) {
            "[x]", "✅", "√", "✔", "✔️" -> TaskItemMark.DONE
            "[~]" -> TaskItemMark.PARTIAL
            "[ ]", "☐", "□" -> TaskItemMark.TODO
            "[!]", "❌", "✖", "✖️", "✗", "⚠", "⚠️" -> TaskItemMark.FAILED
            else -> null
        }
    }

    private fun markRank(line: String): Int = when (markOf(line)) {
        TaskItemMark.DONE -> 3
        TaskItemMark.PARTIAL -> 2
        TaskItemMark.FAILED -> 1
        TaskItemMark.TODO -> 0
        null -> -1
    }

    /** 同一栏内条目去重：键相同、或两者足够长且互相包含（"完成 A 并验证" ≈ "完成 A"）视为重复 */
    private fun dedupeTaskLines(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        val keys = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val key = itemKey(line)
            if (key.isEmpty()) continue
            var dup = -1
            for (i in keys.indices) {
                if (sameItemKey(keys[i], key)) {
                    dup = i
                    break
                }
            }
            if (dup < 0) {
                keys.add(key)
                out.add(line)
            } else if (markRank(line) > markRank(out[dup])) {
                // 同一件事被写了两次、标记不一致（一次 [ ] 一次 [x]）：保留完成度更高的那份
                out[dup] = line
            }
        }
        return out
    }

    private fun keyDuplicated(line: String, doneKeys: List<String>): Boolean {
        val key = itemKey(line)
        if (key.isEmpty()) return false
        return doneKeys.any { sameItemKey(it, key) }
    }

    /**
     * 两个条目键是否指向同一件事。
     *
     * 只做**保守**的近似：完全相同，或较短者占较长者 80% 以上且被包含
     * （"完成 A" ≈ "完成 A 并验证"）。避免把「修改 a.kt」和「修改 a.kt 的注释格式」
     * 这种"同一对象、不同待办"误合并 —— 那会把真正没做的项悄悄吞掉。
     */
    private fun sameItemKey(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 8 || b.length < 8) return false
        val short = if (a.length <= b.length) a else b
        val long = if (a.length > b.length) a else b
        return long.contains(short) && short.length * 10 >= long.length * 8
    }

    // ==================== 块外正文吸收（交付内容只能出现在卡片里） ====================

    /**
     * 从块外正文中识别并**摘出**交付式段落（「产物：…」「遗留：…」及其清单续行）。
     *
     * @return clean = 剩余正文（用于渲染/复制/注入历史）；extracted = 摘出的交付段（并入卡片内容后可再归一）。
     */
    fun absorbBodyDelivery(body: String): BodyAbsorb {
        if (body.isEmpty()) return BodyAbsorb("", "")
        val clean = StringBuilder()
        val extra = StringBuilder()
        var capturing = false
        var stripOnly = false
        var extraLines = 0
        var captureLabel: String? = null

        fun startCapture(strip: Boolean, label: String?) {
            capturing = true
            stripOnly = strip
            extraLines = 0
            captureLabel = label
        }

        fun stopCapture() {
            capturing = false
            stripOnly = false
            extraLines = 0
            captureLabel = null
        }

        for (line in body.lines()) {
            val m = matchHeader(line, strict = true)
            val core = m?.label?.takeIf { it in CORE_LABELS }
            if (core != null) {
                startCapture(strip = false, label = core)
                if (extra.isNotEmpty()) extra.append('\n')
                extra.append(line.trim())
                continue
            }
            // 「产物和遗留：…」「交付总结」这类无法归位的残标题：连同续行一起清掉，只是不进卡片
            if (m != null && m.label == null && m.echo) {
                startCapture(strip = true, label = null)
                continue
            }
            if (capturing) {
                if (line.trim().isEmpty()) {
                    stopCapture()
                    clean.append('\n')
                    continue
                }
                if (isContinuationLine(line, captureLabel) && extraLines < 40) {
                    extraLines++
                    if (!stripOnly) {
                        if (extra.isNotEmpty()) extra.append('\n')
                        extra.append(line.trim())
                    }
                    continue
                }
                // 续行结束（普通句子）：保留在正文，避免把正常叙述吞进卡片
                stopCapture()
                clean.append(line).append('\n')
                continue
            }
            clean.append(line).append('\n')
        }
        return BodyAbsorb(
            clean = clean.toString().replace(Regex("\n{3,}"), "\n\n").trim(),
            extracted = extra.toString().trim()
        )
    }
}

// ==================== 「无实质内容」判定（遗留/验证等栏的占位文案） ====================

private val VOID_EXACT = setOf(
    "无", "暂无", "—", "-", "--", "none", "n/a", "na", "null", "无。", "暂无。", "没有", "无内容",
    "未提供", "不适用", "不涉及", "无其他", "无其它", "不需要", "无需"
)

/** 视为「无遗留」的前缀（注意显式排除「无法…」：那是失败表述，不是占位） */
private val VOID_PREFIX = listOf(
    "无遗留", "无明显", "无任何", "无其他", "无其它", "无后续", "无需", "不需要", "不涉及", "不适用",
    "暂无", "没有遗留", "没有其他", "没有其它", "没有需要", "无待办", "无额外"
)

/**
 * 「无实质内容」判定：遗留/验证等栏写「无」「暂无」「不涉及」时视为闭环，而不是当成有内容。
 * 放宽了前缀识别 —— 旧版只认四个固定前缀，「无其他问题」「无需后续操作」都会被误判成"还有遗留"。
 */
internal fun isVoidText(s: String): Boolean {
    val trimmed = s.trim().lowercase().trimEnd('。', '.', '！', '!', '，', ',', '）', ')', '】', ']')
    if (trimmed.isEmpty()) return true
    // 先剥掉行首的**状态标记与列表符号**再判占位：模型常写成「- 无」「☐ 无」「[ ] 无 - 任务已完成」。
    // 不剥的话这些「无」占位会被当成有内容 —— 遗留栏因此多出一个空心圆待办项，
    // 还会把整卡状态压成「部分完成」（用户实测截图就是这么来的）。
    val t = MARK_PREFIX_RE.replace(trimmed, "").trimListPrefix().trim()
    if (t.isEmpty()) return true
    if (t.startsWith("无法")) return false   // 「无法…」是失败表述，绝不能当占位
    if (t in VOID_EXACT) return true
    if (VOID_PREFIX.any { t.startsWith(it) }) return true
    // 「无（说明）」「无：说明」「无。说明」「无，说明」——「无」后直接接标点/括号，是模型给「无」补的废话解释，
    // 整体仍应判为「无」。否则这条会被当成一个待办项（○），遗留栏多出无意义的勾选态，还会把整卡状态压成「部分完成」。
    // 显式排除「无法…」（失败表述）；「无可用/无新增」这类带实义的描述不在此列，仍按原样展示。
    if (t.startsWith("无")) {
        val rest = t.substring(1)
        if (rest.isEmpty() || rest.first() in "（(：:，,.。、；;-—『「") return true
        // 「无 - 任务已完成」「无 — 无遗留」：模型把「无」和后面那句解释用破折号/空格隔开写的。
        // 语义与「无（说明）」完全一样，仍是占位。必须精确匹配「无 + 空白 + 破折号」，否则会误伤
        // 「无可用环境」这类带实义的描述（那不是占位，要照常展示）。
        if (Regex("^无\\s*[-—–/]+\\s*").containsMatchIn(t)) return true
    }
    return false
}

// ==================== 「已完成」栏的纯过程动作过滤 ====================

/**
 * 「已完成」栏里混进来的**纯过程动作**。
 *
 * 用户反馈：交付总结本应是「成果清单」，但模型把为了达成目标而做的中间动作也写成了已完成项 ——
 * 「切换模式」「保持会话」「列出目录」「搜索内容」「读取文件」「打开应用」。
 * 这些动作本身不产出任何用户可感知的东西，写进去只有两个坏处：
 *  ① 稀释真正的成果（15 项里 4 项是废话，`15/15 已完成` 就没有参考价值了）；
 *  ② 把计数和状态一起带虚高。
 * 而「提取核心技术栈数据」「识别最有用的信息点」「安全分析」「持久技术动态加载」这类才是成果。
 *
 * 判定刻意保守：必须**整体就是**一个过程动作才丢。条目里出现任何实质性成果词
 * （提取/分析/生成/修复/报告/部署/实现/识别/统计/编译/下载/转换/总结/优化/新增/上传…）
 * 就一律保留 —— 例如「读取 MainActivity.kt 并修复崩溃」是成果，不能被误伤。
 * 唯一例外是**指向具体文件**的读取/打开/查看（带扩展名，如「读取 MainActivity.kt」
 * 「打开 build.gradle.kts」）：路径再长也只是过程，没有成果词照样丢。
 */
private val PROCESS_ACTION_RE = Regex(
    "切换.*模式|切换.*(设置|配置)|保持会话|保持.*连接|维持会话" +
        "|列出.*(目录|文件|文件夹|文件列表)|罗列.*(目录|文件)|遍历.*(目录|文件)" +
        "|搜索.*(文件|内容|关键字|关键词)|检索|查找.*(文件|目录)" +
        "|读取.*(文件|内容|目录)|读文件|读取了" +
        "|打开.*(应用|文件|目录)|查看.*(文件|目录|列表)|浏览.*(目录|文件|列表)"
)

/** 实质性成果词：出现任意一个就说明这不是纯过程动作，必须保留 */
private val SUBSTANTIVE_WORDS = listOf(
    "提取", "分析", "生成", "创建", "写入", "修复", "报告", "整理", "部署", "配置", "实现",
    "识别", "梳理", "统计", "编译", "下载", "转换", "输出", "总结", "拆包", "反编译",
    "改写", "优化", "新增", "删除", "上传", "截图", "验证", "定位", "采集", "解析",
    "排查", "重构", "上线", "归档", "备份", "恢复", "迁移", "接入", "适配", "评估"
)

/**
 * 「读取/打开/查看 + 具体文件名（带扩展名）」的快通道特征。
 * 例：读取 MainActivity.kt、打开 build.gradle.kts、查看 app/src/main/AndroidManifest.xml。
 * 中间允许最长 80 字符的路径/空白，长路径同样只是过程动作。
 */
private val FILE_REF_ACTION_RE = Regex("(读取|打开|查看|浏览).{0,80}\\.[A-Za-z0-9]{1,10}")

/**
 * 是不是「把纯过程动作当成了已完成项」。
 *
 * 三道闸门，任一不满足就放行（宁可漏过滤，不能误伤真正的成果）：
 *  ① 命中过程动作特征；
 *  ② 条目不长（>40 字通常是「做了 X 从而得到 Y」的复合成果描述）；
 *  ③ 不含任何实质性成果词。
 *
 * 另有一条独立快通道：条目主体就是「读取/打开/查看 + 具体文件名（带扩展名）」时，
 * 不受 40 字长度限制（长路径同样是过程，不是成果），但仍受「无成果词」保护。
 */
internal fun isPureProcessAction(t: String): Boolean {
    val s = t.trim().trimListPrefix()
    if (s.isEmpty()) return false
    val substantive = SUBSTANTIVE_WORDS.any { s.contains(it) }
    if (!substantive && FILE_REF_ACTION_RE.containsMatchIn(s)) return true
    if (s.length > 40) return false
    if (!PROCESS_ACTION_RE.containsMatchIn(s)) return false
    if (substantive) return false
    return true
}

// ==================== 「格式说明被抄成内容」判定 ====================

/**
 * 模型把**提示词里的格式说明**原样抄进了交付内容。
 *
 * 用户实测（截图）：任务全部完成，遗留栏却是
 *   ○ 无 - 任务已完成
 *   ○ 确实还没做成的项，没有就写「无」
 * 这两句正是「交付契约」里那条格式说明的两个片段 —— 模型把**说明**当成了**遗留项**填进去。
 * 后果有三：遗留栏多出两个空心圆待办、整卡角标被压成「部分完成」、正文里冒出莫名其妙的自述。
 *
 * 这里做确定性剔除：命中即整行丢弃（不参与逐项计数、不进遗留清单）。
 * 只认高度特异的措辞，且全部要求「说明句式」共现，正常的遗留项（如「需要用户提供 API Key 才能继续」）
 * 不会命中 —— 触发词里刻意不放「需要 / 如需 / 继续」这类真正遗留里常用的词。
 */
/** 单条命中即可判定的强特征：这些说法本身就是「在讲格式」，不可能出现在真实的任务项里 */
private val ECHO_STRONG = listOf(
    "没有就写", "没有则写", "没有就填", "没有则填",
    "格式说明", "格式要求", "字段说明",
    // 「已完成」栏的格式说明也会被抄进去（截图实测：「逐项清单,每项行首必须带状态标记 —— [x] 完全达成…」）
    "逐项清单", "完全达成", "行首必须带状态标记", "带状态标记"
)

/** 弱特征：单独出现还可能是巧合，需与另一条共现才判定 */
private val ECHO_WEAK = listOf(
    "逐项列出", "逐条列出",
    "确实还没做成", "确实没做成", "确实没有做成",
    "本次任务范围内", "本次任务范围"
)

/**
 * 交付哨兵的**明文形式**被模型抄成了字段内容。
 *
 * 真哨兵是 `\u0001DELIVER\u0001` / `\u0001/DELIVER\u0001`，但模型在提示词里看到的只是 "DELIVER"
 * 这几个字母（控制字符不可见），于是它会把 `DELIVER` / `/DELIVER` 当普通内容写进某一栏 ——
 * 用户实测（截图）：「遗留」栏出现「○ /DELIVER」空心圆待办，并把整卡角标压成「部分完成 1/2」
 * （1 项已完成 + 1 项假待办）；开哨兵的明文还会在卡片上方外漏成孤零零一行 "DELIVER"。
 *
 * 判定不依赖具体字符集合（零宽字符、全角斜杠、圆圈符号、尾随标点全都吃掉）：剥掉列表符号 /
 * 状态标记后，把**所有非字母数字字符**删掉，整条只剩 "deliver" 这个词 → 判为回声，整行丢弃。
 * 真实遗留不会命中：「需要用户提供 API Key 才能继续」「/deliverable 文档还没写」删完符号仍剩别的内容。
 */
private fun isDeliverSentinelEcho(t: String): Boolean {
    val bare = MARK_PREFIX_RE.replace(t.trim(), "").trimListPrefix()
    val flat = bare.replace(Regex("""[^\p{L}\p{N}]"""), "").lowercase()
    return flat == "deliver"
}

/** 是不是「把格式说明抄成了内容」的一行 */
internal fun isInstructionEcho(t: String): Boolean {
    val s = t.trim()
    if (s.isEmpty()) return false
    if (isDeliverSentinelEcho(s)) return true
    if (ECHO_STRONG.any { s.contains(it) }) return true
    return ECHO_WEAK.count { s.contains(it) } >= 2
}

// ==================== 「遗留」栏的越界闲聊清洗 ====================

/**
 * 「遗留」栏里的**越界闲聊**清洗。
 *
 * 用户实测：遗留栏被写成「仓库无工程文件，纯属练习代码集，如需细读或运行可再告诉我」——
 * 那是模型的**观察 + 主动请缨**，既不是"本任务没做完的项"，也不是"遇到的限制"。
 * 遗留只该承载"范围内、确实还没做成的项"；其余一律剔除，否则整栏语义失真、状态跟着失真。
 *
 * ⚠️ 只盯**主动请缨/把球踢回用户**的特征（"…告诉我" / "要不要我" / "我可以继续帮你"），
 * **刻意不把「如需」「需要」当触发词** —— 那会误伤真正被阻塞的遗留
 * （如「需要用户提供 API Key 才能继续」：那是范围内没做成的项，必须留下）。
 */
private val LEFTOVER_CHATTER_RE = Regex(
    "(告诉|告知|联系|通知|叫)我|" +
        "要不要我|是否需要我|需不需要我|" +
        "我可以(?:再|继续|帮你|替你|顺手|随时)|" +
        "随时(?:告诉|告知|联系|找我|说|问)|" +
        "如果你愿意|不妨(?:再)?告诉我|" +
        "(?:接下来|后续|下一步)你可以"
)

/** 按中英文句末标点切句（保留标点），供 [sanitizeLeftoverContent] 逐句过滤 */
private val SENTENCE_SPLIT_RE = Regex("(?<=[。！？!?；;])")

internal fun sanitizeLeftoverContent(raw: String): String {
    val kept = mutableListOf<String>()
    for (line in raw.split("\n")) {
        val t = line.trim()
        if (t.isEmpty()) continue
        // 模型把提示词里的格式说明抄成了遗留项（「确实还没做成的项，没有就写「无」」）：整行丢弃。
        // 否则它会变成一个空心圆待办项，并把整卡状态压成「部分完成」。
        if (isInstructionEcho(t)) continue
        val sentences = t.split(SENTENCE_SPLIT_RE)
            .filter { it.isNotBlank() && !LEFTOVER_CHATTER_RE.containsMatchIn(it) }
        if (sentences.isEmpty()) continue          // 整行都是闲聊 → 丢弃
        val merged = sentences.joinToString("").trim()
        if (merged.isNotEmpty()) kept.add(merged)
    }
    return kept.joinToString("\n")
}
