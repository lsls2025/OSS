package com.aurora.chat.ui.chat

import android.content.Context
import com.aurora.chat.ui.chat.AiChatManager.FileChange

/**
 * 交付契约与任务状态机的纯逻辑层。
 *
 * 把「AI 能否结束」从「模型是否停止调用工具」改为「是否交付」，把「轮次预算」从硬墙改为软预算
 * （触发收尾而非终止）。被两套工具循环（ChatConversationScreen 内联循环 / AiChatManager.runAIChatLoop）
 * 共用，从根本上避免两处行为漂移，也不让 AiChatManager（已 5934 行）继续膨胀。
 *
 * 设计约束（与现有哨兵协议共存）：
 *  - 交付块哨兵使用 \u0001 包裹，与思考 \u0001T\u0001 / 工具 \u0001W\u0001 / ask \u0001Q\u0001 同源风格；
 *  - stripToolSentinels / splitInlineBlocksV2 只识别上述三类哨兵，不会误删本交付哨兵；
 *  - 渲染层用 [stripDelivery] 清理标记，仅去除开合哨兵、保留内部可读文本。
 */
object AITaskCompletion {

    /** 交付块哨兵：模型交付的结构化总结包裹其中 */
    const val DELIVER_OPEN = "\u0001DELIVER\u0001"
    const val DELIVER_CLOSE = "\u0001/DELIVER\u0001"

    // ===== 各类循环分支的硬上限（防止死循环，总数可控）=====
    const val MAX_WRAP_UP_ROUNDS = 2   // 收尾轮上限（禁工具，仅纯文本交付）
    const val MAX_DELIVER_NUDGES = 2   // 工作已完成但缺交付总结时的补交付次数
    const val MAX_CONTINUE_WORK_NUDGES = 10 // 工作未完成时强制续做的次数(每次续做仍受工具轮次预算约束)
    const val MAX_CONTINUE_ROUNDS = 5  // finish_reason=length 自动续写次数(原 3 次:长交付/多段产物时易耗尽后被迫收尾)
    /** 「请示式收尾」被驳回后强制继续的次数上限（模型反复问「要不要我继续」时的确定性刹车） */
    const val MAX_PERMISSION_NUDGES = 3
    const val MAX_ROUND_RETRIES = 2    // 单轮可重试异常的重试次数
    const val MAX_EMPTY_ROUNDS = 3      // 静默空轮(上游空返回/抖动)的有界重试次数,耗尽则明确告知而非静默结束
    const val MAX_FAIL_STRATEGY_ROUNDS = 2 // 连续工具全失败后,先给「换策略」补救机会的轮数(P1:失败≠立即收尾)
    const val MAX_WRAP_UP_TOOL_ROUNDS = 2 // 收尾阶段保留的工具补救额度(P1:收尾仍可少量补救,防死循环)

    // ===== 长任务中断优化(P2:分批 / 过度探索 / 上下文溢出 / 产物核对)=====
    /** 单批工具调用数量上限:任务规模大时按批执行,每批完成后自动衔接下一批(不等待用户) */
    const val BATCH_TOOL_CALLS = 5
    /** 连续只读探索轮数上限:达到后判定「过度探索」,停止重试并降级输出(原 5 轮;真实实验反馈 5 轮过宽,收紧为 3 轮) */
    const val MAX_OVER_EXPLORE_ROUNDS = 3
    /** 上下文溢出后「裁剪历史再重试」的次数上限(裁剪后仍失败则不再原地重试) */
    const val MAX_CONTEXT_RETRIES = 1
    /** 同类工具连续失败次数阈值:达到后提前注入换策略指令(不等整轮全败) */
    const val MAX_SAME_TOOL_FAIL_STREAK = 3

    // ===== 多 AI 子循环(规划 / 验收 / 并行派发)的预算 =====
    // 为什么必须单独一套:这些子循环是**嵌套**的,母循环那一套预算管不到它们。
    // maxRoundsOverride 只数「真正跑过工具的轮次」,而续写 / 上下文裁剪重试 / 可重试错误三条 continue
    // 都不计数;叠加 429 退避(单次最长 30s),名义 5 轮的验收实际能跑十几分钟,
    // 最后被外层 withTimeoutOrNull 从中间掐断 → 产出半截文本 → 判 UNVERIFIED(用户实测:验收必失败)。
    // 因此子循环统一配「墙钟软截止 + 活动轮上限 + 墙钟硬超时」三重:
    //  软截止先到 → 禁工具催结论,让循环**自然产出真实结论**;硬超时兜底 → 如实报超时,不再谎称格式问题。

    /** 验收子循环:墙钟软截止(到点禁工具催结论)。正常验收 1~2 分钟,2.5 分钟留足但不放纵。 */
    const val REVIEW_SOFT_DEADLINE_MS = 150_000L
    /** 验收子循环:全部迭代(含重试)的活动上限。5 个工具轮 + 各类重试的最坏情况。 */
    const val REVIEW_MAX_ACTIVITY_ROUNDS = 12
    /** 验收子循环:墙钟硬超时。到点直接掐断(软截止已给过产出机会)。 */
    const val REVIEW_TIMEOUT_MS = 300_000L

    /** 规划子循环:墙钟软截止。规划只读勘察,比验收更短。 */
    const val PLAN_SOFT_DEADLINE_MS = 120_000L
    /** 规划子循环:活动上限 */
    const val PLAN_MAX_ACTIVITY_ROUNDS = 10
    /** 规划子循环:墙钟硬超时 */
    const val PLAN_TIMEOUT_MS = 240_000L

    /** 单个并行子执行者的工具轮上限(不是整次派发) */
    const val FORK_WORKER_MAX_ROUNDS = 15
    /** 单个并行子执行者:墙钟软截止 */
    const val FORK_WORKER_SOFT_DEADLINE_MS = 240_000L
    /** 单个并行子执行者:全部迭代活动上限 */
    const val FORK_WORKER_MAX_ACTIVITY_ROUNDS = 25
    /** 单个并行子执行者:墙钟硬超时(到点强制该子任务失败,不影响其它兄弟子任务) */
    const val FORK_WORKER_TIMEOUT_MS = 480_000L

    /**
     * 到达软截止时注入给子循环的「催结论」指令。
     *
     * 为什么不直接等硬超时:硬超时会**从中间掐断**正在跑的流式请求,产出的是半截文本,
     * 既拿不到结论、又要让用户看到一句含糊的「不可判定」。软截止改为先禁工具、再催结论,
     * 循环仍能自然收束出真实结论 —— 这是把「验收必失败」扭回来的关键一步。
     */
    // 工具调用系统提示已清理：保留常量名以维持 AI 骨架，内容已清空。
    const val REVIEW_DEADLINE_INSTRUCTION = ""
    /** 同 [REVIEW_DEADLINE_INSTRUCTION],给规划子循环 */
    // 工具调用系统提示已清理：保留常量名以维持 AI 骨架，内容已清空。
    const val PLAN_DEADLINE_INSTRUCTION = ""
    /** 同 [REVIEW_DEADLINE_INSTRUCTION],给并行子执行者 */
    // 工具调用系统提示已清理：保留常量名以维持 AI 骨架，内容已清空。
    const val FORK_WORKER_DEADLINE_INSTRUCTION = ""
    /** 任务状态机阶段 */
    enum class AITaskPhase { NORMAL, WRAP_UP, DELIVERED }

    /** 系统提示与交付契约已清理；保留签名以维持 AI 骨架。 */
    fun needsContract(ctx: Context, userId: Long, usedTools: Int = 0): Boolean {
        if (AiChatManager.isFastMode(ctx, userId)) return false
        // Ask 模式只问不做：不存在「任务交付」这回事，一律不启用契约。
        // 不给这条排除的话，Ask 下模型仍会照猫画虎写出「目标/已完成/产物/验证/遗留」式交付总结
        // （用户实际遇到的就是这个：Ask 模式问一个问题，回答却带着整张交付清单）。
        if (AiChatManager.getAIMode(ctx, userId) == AiChatManager.MODE_ASK) return false
        // Plan 模式只负责「先规划」：本轮产出的**方案卡片本身就是交付物**，不需要再叠一份交付总结。
        // 不给这条排除的话，模型为了写方案文档调了 write_file → 契约被触发 → 同一条消息里会同时出现
        // 「交付总结卡」和「方案卡」（交付卡在前、方案卡在后，看起来就是"方案跑到交付总结下面去了"），
        // 而且那个交付块会让下面的 planAllDoneState 立刻为真 → 方案一生成就显示"全部已完成"。
        if (AiChatManager.getAIMode(ctx, userId) == AiChatManager.MODE_PLAN) return false
        if (usedTools > 0) return true
        return AiChatManager.EXEC_MULTI_AGENT in AiChatManager.getExecModes(ctx, userId)
    }

    /** 注入 system 提示的契约说明（追加在 buildAIAwarenessPrompt 之后） */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun buildContractPrompt(ctx: Context, userId: Long): String = ""

    /**
     * 交付块解析结果（**嵌套安全 + 交付内容不外露**）。
     *
     * @param bodyText        剔除**所有**交付块之后的正文；块外被识别为交付式的段落（「产物：…」「遗留：…」）
     *                        也会被**摘走**（内容并入 [content]）。用于：消息正文渲染、注入模型上下文 ——
     *                        保证交付信息只在卡片里出现一次，正文绝不会冒出第二套。
     * @param bodyWithContent 正文 + **最外层块可读内容**（只去哨兵、不删内容）。
     *                        用于：复制到剪贴板等"要能读"的场景；块内容为空时会把摘走的交付段补回。
     * @param content         交付内容：**所有**最外层块内容按原始顺序拼接（重复字段由
     *                        [DeliveryProtocol.parseFields] 按标签合并去重），再并入块外摘走的交付段；
     *                        未闭合（流式中）时为"开哨兵之后的全部内容"。
     * @param closed          是否找到配对的闭合哨兵（false = 模型还在写 / 只写了半个）
     */
    data class DeliveryParse(
        val bodyText: String,
        val bodyWithContent: String,
        val content: String,
        val closed: Boolean
    )

    /** 找与 openIdx 处开哨兵**配对**的闭合哨兵（按嵌套深度计数配对）；找不到返回 -1 */
    private fun matchingClose(text: String, openIdx: Int): Int {
        var depth = 1
        var i = openIdx + DELIVER_OPEN.length
        while (i < text.length) {
            val o = text.indexOf(DELIVER_OPEN, i)
            val c = text.indexOf(DELIVER_CLOSE, i)
            if (o < 0 && c < 0) return -1
            if (o >= 0 && (c < 0 || o < c)) {
                depth++
                i = o + DELIVER_OPEN.length
            } else {
                depth--
                if (depth == 0) return c
                i = c + DELIVER_CLOSE.length
            }
        }
        return -1
    }

    /** 删除字符串中**所有**交付块（连同块内内容），只保留块外文本；未闭合的开哨兵及其后内容也一并丢弃 */
    private fun removeAllBlocks(s: String): String {
        if (!s.contains(DELIVER_OPEN)) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val o = s.indexOf(DELIVER_OPEN, i)
            if (o < 0) {
                sb.append(s, i, s.length)
                break
            }
            sb.append(s, i, o)
            val c = matchingClose(s, o)
            i = if (c < 0) s.length else c + DELIVER_CLOSE.length
        }
        return sb.toString()
    }

    /**
     * **交付块解析的单一事实源**（嵌套安全）。
     *
     * 为什么必须收口：此前 `hasDeliverBlock` / `hasDelivery` / `extractDelivery` / 渲染层的
     * `prepareDeliverableDisplay` 各自用 `substringAfter(OPEN).substringBefore(CLOSE)`
     * ——也就是"第一个开哨兵 + 它后面第一个闭哨兵"。模型一旦**把交付块嵌套进另一个交付块**
     * （它会从历史里看到上一轮的块原文，被诱导复制粘贴），边界立刻错位：
     * 内层闭哨兵被当成外层闭合 → 外层剩余内容被当正文外漏、门禁判定失真。
     * 现在全部改走本函数：按嵌套深度**配对**，外层内容里剔除嵌套块，正文里剔除所有块。
     */
    fun parseDeliveryBlocks(raw: String): DeliveryParse {
        // ⚠️ 绝不能先对原文做 stripStrayDeliverMarkers：它会抹掉 \u0001，而真哨兵
        // (\u0001DELIVER\u0001 / \u0001/DELIVER\u0001) 正是由 \u0001 构成的 —— 先清洗等于
        // 把真哨兵销毁，配对永远失败、渲染层掉进宽容兜底，哨兵行就会以纯文本外露
        // （开哨兵露在卡片上方成 "DELIVER"，闭哨兵露在卡片下方成 "/DELIVER"）。
        // 明文残留只在**解析产物**上清理（见函数末尾）。
        val text = raw
        // parts：true = 最外层块内容；false = 块外正文段。按**原始出现顺序**保留，
        // 供正文清洗与交付内容拼接使用（顺序决定多套字段合并时"后写的覆盖先写的"）。
        val parts = ArrayList<Pair<Boolean, String>>()
        var closed = false
        var i = 0
        while (i < text.length) {
            val o = text.indexOf(DELIVER_OPEN, i)
            if (o < 0) {
                parts.add(false to text.substring(i))
                break
            }
            if (o > i) parts.add(false to text.substring(i, o))
            val c = matchingClose(text, o)
            if (c < 0) {
                // 未闭合（流式中）：开哨兵之后的全部内容视为交付块（通常位于正文末尾）
                parts.add(true to removeAllBlocks(text.substring(o + DELIVER_OPEN.length)))
                i = text.length
                break
            }
            closed = true
            // 外层块内容里剔除嵌套块（含其内容），避免同一份总结在卡片里重复。
            // 【多块合并】以前是"只留最后一块"：那会丢掉前面块里的独有产物，
            // 而重复字段由 DeliveryProtocol.parseFields 按标签合并去重解决 —— 拼接不再产生两套。
            parts.add(true to removeAllBlocks(text.substring(o + DELIVER_OPEN.length, c)))
            i = c + DELIVER_CLOSE.length
        }

        val hasBlock = parts.any { it.first }
        // 块外交付段吸收：只在**存在交付块**时启用；无块的纯文本交付仍交给渲染层宽容解析。
        // 这是"交付内容只能在卡片里"的确定性保证：块外的「产物：…」「遗留：…」会被摘进卡片内容，
        // 并从正文删除 —— 同一份信息不可能既在卡片里、又在正文里出现第二遍。
        val absorbed = parts.map { (isInner, seg) ->
            if (isInner || !hasBlock) null else DeliveryProtocol.absorbBodyDelivery(seg)
        }

        val bodySb = StringBuilder()
        val bodyKeepSb = StringBuilder()
        val contentSb = StringBuilder()
        var innerHasContent = false
        parts.forEachIndexed { idx, (isInner, seg) ->
            if (isInner) {
                if (seg.isNotBlank()) innerHasContent = true
                bodyKeepSb.append(seg)
                appendDeliveryContent(contentSb, seg)
            } else {
                val a = absorbed[idx]
                val cleanBody = a?.clean ?: seg
                bodySb.append(cleanBody)
                bodyKeepSb.append(cleanBody)
                appendDeliveryContent(contentSb, a?.extracted.orEmpty())
            }
        }
        // 复制/编辑场景（bodyWithContent）：块内容为空时把吸收到的交付段补回来，否则用户复制到的是空文本
        if (!innerHasContent) {
            val extras = parts.indices
                .filter { !parts[it].first }
                .mapNotNull { absorbed[it]?.extracted }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            if (extras.isNotBlank()) {
                if (bodyKeepSb.isNotEmpty()) bodyKeepSb.append('\n')
                bodyKeepSb.append(extras)
            }
        }

        return DeliveryParse(
            // 解析产物里已不含真哨兵，此时再清明文残留与孤立控制字符是安全的
            bodyText = stripStrayDeliverMarkers(bodySb.toString()),
            bodyWithContent = stripStrayDeliverMarkers(bodyKeepSb.toString()),
            content = stripStrayDeliverMarkers(contentSb.toString()),
            closed = closed
        )
    }

    /**
     * 按原始顺序把一段交付内容拼进缓冲（块内容与块外吸收段共用）。
     * 段边界用 [DeliveryProtocol.SECTION_SEP] 保留 —— 跨段合并时「清单取并集、文本与遗留以最后一段为准」
     * 依赖它，不能用普通换行抹平。
     */
    private fun appendDeliveryContent(sb: StringBuilder, piece: String) {
        if (piece.isBlank()) return
        if (sb.isNotEmpty()) sb.append(DeliveryProtocol.SECTION_SEP)
        sb.append(piece)
    }

    // ===== 失败类关键词的「否定清洗」：单一事实源 =====
    // 交付状态推导(deriveDeliverableStatus)与验收结论解析(parseReviewVerdict)共用本清单，
    // 保证两处判定口径一致；否则同一句话在两处会被读出不同结论。
    // 目的：把"**没**出问题"从"出问题"里摘出来 —— 例如
    // 「未发现失败项」「无任何问题」「不存在缺陷」「均正常」都不能被当成失败信号。
    // 注意：「无法完成」「未完成」这类**本身即失败/未完成**的表述**不在**清洗范围内（它们要保留）。
    private val FAIL_NEGATION_RES = listOf(
        Regex("未(?:发现|出现|存在|见|有|检测到|察觉)?(?:任何|明显|重大|相关)?(?:失败|问题|未完成|缺陷|出错|错误|异常|报错|不通过|不合格)"),
        Regex("无(?:任何|明显|重大|相关)?(?:失败|问题|未完成|缺陷|出错|错误|异常|报错|不通过|不合格)"),
        Regex("没有(?:任何|明显|重大|相关)?(?:失败|问题|未完成|缺陷|出错|错误|异常|报错|不通过|不合格)"),
        Regex("不存在(?:任何|明显|重大|相关)?(?:失败|问题|未完成|缺陷|出错|错误|异常|报错|不通过|不合格)"),
        Regex("(?:均|都|全部|一切)(?:正常|通过|成功|无异常|没问题|顺利)"),
        Regex("\\b(?:no|without|free of)\\s+(?:failures?|errors?|issues?|problems?)\\b", RegexOption.IGNORE_CASE)
    )

    /** 去掉文本中的否定短语（「未发现失败」「无问题」「无异常」等），供失败/通过判定前统一预处理 */
    fun stripFailureNegations(s: String): String {
        if (s.isEmpty()) return s
        var out = s
        for (re in FAIL_NEGATION_RES) out = re.replace(out, "")
        return out
    }

    /** 是否含完整的 DELIVER 哨兵块（开合哨兵成对且内部有实质内容）。严格哨兵判定，不包含漏哨兵容错。 */
    fun hasDeliverBlock(text: String): Boolean {
        val p = parseDeliveryBlocks(text)
        return p.closed && p.content.length >= 8
    }

    /** 正文是否已含合规交付块：开合哨兵成对且内部非空；或遗漏哨兵但已出现强交付信号（避免无限 nudge）。 */
    fun hasDelivery(text: String): Boolean {
        val p = parseDeliveryBlocks(text)
        // 完整哨兵交付块：内部有实质内容即算交付（结构由模板约束，模型按格式填写）
        if (p.closed && p.content.length >= 8) return true
        // 漏哨兵容错（已收紧）：必须同时具备「明确产物声明」+「路径/文件证据」+「已完成」三信号才算真交付。
        // 旧判定仅要求「已完成+验证」两个词，模型写两句空泛总结即可提前收工，导致任务没做完就断，已移除。
        val declaresProduct = text.contains("产物") || text.contains("已生成") || text.contains("已创建") ||
            text.contains("已保存") || text.contains("已写入") || text.contains("已导出")
        val hasPathEvidence = text.contains("ai_files") || text.contains("路径") || text.contains("工作区") ||
            text.contains(".kt") || text.contains(".md") || text.contains(".txt") || text.contains(".json") ||
            text.contains(".zip") || text.contains(".png") || text.contains(".jpg") || text.contains(".xml")
        return declaresProduct && hasPathEvidence && text.contains("已完成")
    }

    /**
     * 正文是否已含「可读总结」(编排层兜底判据):
     *  - 完整 DELIVER 块 → 直接成立;
     *  - 否则三类交付信号(产物声明 / 路径证据 / 完成确认)中至少两类成立 → 成立。
     * 与 hasDelivery 的区别:hasDelivery 要求三类信号全过(严格交付核对),
     * hasReadableSummary 只要求「用户能读到任务结论/产物信息」的最低可读门槛,用于兜底合成时机。
     */
    fun hasReadableSummary(text: String): Boolean {
        if (hasDeliverBlock(text)) return true
        var signals = 0
        val declaresProduct = text.contains("产物") || text.contains("已生成") || text.contains("已创建") ||
            text.contains("已保存") || text.contains("已写入") || text.contains("已导出")
        val hasPathEvidence = text.contains("ai_files") || text.contains("路径") || text.contains("工作区") ||
            text.contains(".kt") || text.contains(".md") || text.contains(".txt") || text.contains(".json") ||
            text.contains(".zip") || text.contains(".png") || text.contains(".jpg") || text.contains(".xml")
        val confirmsDone = text.contains("已完成") || text.contains("完成") || text.contains("成功")
        if (declaresProduct) signals++
        if (hasPathEvidence) signals++
        if (confirmsDone) signals++
        return signals >= 2
    }

    /** 剥离交付块哨兵（保留**最外层**内部内容），供渲染层/剪贴板清理标记。
     *  嵌套的内层块会被整体剔除（避免同一份总结重复出现），明文残留标记也会被清掉。 */
    fun stripDelivery(text: String): String = parseDeliveryBlocks(text).bodyWithContent

    /** 抽取交付块内容（嵌套安全），用于 UI 单独渲染；无则返回空 */
    fun extractDelivery(text: String): String = parseDeliveryBlocks(text).content

    /**
     * 收尾指令（收尾阶段保留少量工具补救额度，但以纯文本交付为主）。
     *
     * ⚠️【曾经的严重 bug】第一句以前无条件写「本轮已达工具轮次预算上限($usedTools 轮)」，
     * 但本函数有 5 个调用方，其中「连续 N 轮工具全部失败」「已给出可读总结但轮次逼近上限」
     * 与轮次预算耗尽毫无关系。后果：工具连失败 3 轮时，系统注入的却是
     * 「已达工具轮次预算上限(4 轮)」—— 模型据此认定"额度只有 4 轮"而提前收手，
     * 用户在思考链里看到的就是这句假话（用户实测：工具上限设了 300，AI 却说"已达上限 4 轮"）。
     * 现在第一句按 reason 如实描述，**绝不编造不存在的限制**；$usedTools 只是"已用轮数"，
     * 不再是会被误读成"上限"的数字。
     */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun wrapUpInstruction(usedTools: Int, reason: String): String = ""

    /** 连续工具全失败 ≥3 轮 → 换策略指令（保留工具，要求换方案而非重复同调用） */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun failStrategyInstruction(): String = ""
    /** 模型提前收工 → 补做指令。objectiveDone=false:工作未完成,强制继续执行;true:已完成,补交付总结 */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun nudgeInstruction(objectiveDone: Boolean): String = ""
    /** 工作已客观完成但缺交付总结 → 补交付指令（禁工具，直接输出交付块） */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun deliverOnlyInstruction(): String = ""
    /** 工作尚未完成(无成功产出证据) → 强制继续执行指令（保留工具，禁止现在交付） */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun continueWorkInstruction(): String = ""
    private val PERMISSION_ASK_RES = listOf(
        // 「要不要我继续 / 需要我现在开始吗 / 是否要我接着做」
        Regex("(要不要|需不需要|是否需要|需要我|是否要我|要我)[^。！？!?\\n]{0,14}(继续|接着|开始|动手|往下|下一步|处理下去|做下去|改下去|执行下去|做完)"),
        // 「我可以继续吗 / 能接着做吗 / 继续吗 / 开始吗」
        Regex("(可以|能|要不要|是否)?[^。！？!?\\n]{0,6}(继续|接着|开始|动手|往下做)[^。！？!?\\n]{0,6}(吗|么|嘛|不)[？?]?"),
        // 「是否继续 / 还要继续吗 / 要否接着」
        Regex("(是否|要否|还要|需要)[^。！？!?\\n]{0,4}(继续|接着|开始|动手)"),
        // 英文
        Regex("\\b(want me to|should i|shall i|would you like me to)\\s+(continue|proceed|start|keep going|go ahead)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(continue|proceed)\\s*\\?", RegexOption.IGNORE_CASE)
    )

    /**
     * 文本是否以「请示句」收尾 —— 问用户要不要让它继续/开始，而不是给出结果。
     *
     * 只看**尾部 200 字**：请示句必然落在回复末尾；正文中间转述用户原话（"你问我能不能继续"）不该被误判。
     * 注意：真正的破坏性操作确认应走 `ask_user` 工具（那是工具调用，不走本判定），所以这里封堵纯文本请示
     * 不会挡住"删什么/覆盖谁"这类必要的用户拍板。
     */
    fun asksForPermission(text: String): Boolean {
        if (text.isBlank()) return false
        val tail = text.takeLast(200)
        return PERMISSION_ASK_RES.any { it.containsMatchIn(tail) }
    }

    /** 请示式收尾 → 驳回并强制继续（系统替用户回答「继续」） */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun permissionDeniedInstruction(): String = ""
    /** 输出被截断 → 续写指令；tail 为已输出尾部片段，用于避免重复 */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun continueInstruction(tail: String): String = ""

    // ===== 长任务中断优化(P2:分批衔接 / 过度探索降级 / 上下文裁剪重试 / 产物核对)=====

    /** 批次衔接指令:一批工具调用完成后自动续接下一批(长任务分批执行的核心提示,注入 history) */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun batchContinuationInstruction(doneTools: Int): String = ""
    /** 过度探索降级指令:连续只读探索轮数达到上限(3 轮)无实质产出时,停止探索、强制转入写操作/基于已有信息输出降级版本 */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun overExploreInstruction(): String = ""
    /** 上下文溢出裁剪重试提示:注入 history 后模型基于压缩后的上下文继续 */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun contextOverflowRetryInstruction(): String = ""
    /** 产物交付核对指令:模型声称已交付但声称的产物路径实际不存在 */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun deliveryVerifyInstruction(missing: List<String>): String = ""

    /** 是否为上下文溢出类错误(不可原地重试,必须裁剪历史后再试) */
    fun isContextOverflowError(error: String): Boolean {
        val e = error.lowercase()
        return listOf(
            "context length", "context_length", "maximum context", "context window",
            "context is too long", "too many tokens", "token limit", "tokens exceeded",
            "exceeds the maximum", "input is too long", "input too long", "prompt is too long",
            "please reduce", "reduce the length", "max context",
            "上下文长度", "上下文超", "超出上下文", "上下文溢出", "上下文过长",
            "token 超", "超过最大", "输入过长", "内容过长", "请求过大", "请求过长"
        ).any { e.contains(it) }
    }

    /** 从交付文本中提取声称的产物路径(相对/绝对路径均可;去重保序)。 */
    fun extractDeliveryPaths(text: String): List<String> {
        val out = LinkedHashSet<String>()
        // 嵌套安全：优先用配对解析出的交付内容；没有块时按全文扫
        val parsed = parseDeliveryBlocks(text)
        val inner = if (parsed.content.isNotBlank()) parsed.content else text
        val re = Regex(
            """(?i)([A-Za-z]:[\\/][^\s，。,"'；;:]{1,260}\.(?:md|txt|json|kt|xml|yaml|yml|zip|7z|rar|tar|gz|png|jpg|jpeg|webp|gif|svg|docx|xlsx|xls|pptx|pdf|csv|py|sh|bat|ps1|html|htm|js|ts|css|mp4|mkv|mov|avi|webm|mp3|wav|flac|apk|db|sqlite|bin|exe|dll)|[^\s，。,"'；;:]{1,200}\.(?:md|txt|json|kt|xml|yaml|yml|zip|7z|rar|tar|gz|png|jpg|jpeg|webp|gif|svg|docx|xlsx|xls|pptx|pdf|csv|py|sh|bat|ps1|html|htm|js|ts|css|mp4|mkv|mov|avi|webm|mp3|wav|flac|apk|db|sqlite|bin|exe|dll))"""
        )
        for (m in re.findAll(inner)) {
            var p = m.value.trim().trimEnd('.', '，', '。', ',', ';', ':', ')', ']', '}', '"', '\'', '`')
            if (p.isNotEmpty() && !p.startsWith("http")) out.add(p)
        }
        return out.toList()
    }

    /** 路径是否为绝对路径(Windows 盘符或 Unix 根路径) */
    private fun isAbsolutePath(p: String): Boolean =
        p.startsWith("/") || Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(p)

    private fun resolveDeliveryPath(p: String, workspaceRoot: java.io.File): java.io.File =
        if (isAbsolutePath(p)) java.io.File(p) else java.io.File(workspaceRoot, p)

    /**
     * 交付校验(通用客观核对,恢复 P2 判定):声称的产物路径必须真实存在。
     *  - 含完整 DELIVER 块 → 优先采纳(结构化交付,hasDelivery 直接通过);
     *  - 无 DELIVER 块但 hasDelivery 三信号(产物声明+路径证据+已完成)全过 → 客观核对:
     *    无声称路径(纯总结型交付) → 通过;
     *    有声称路径且至少一个真实存在 → 通过;
     *    有声称路径但全部不存在 → 判为「伪交付」,返回 false,交由编排层兜底。
     *  - 不再强制要求 DELIVER 哨兵:交付判定以「客观证据」为准(工具覆盖 + 产物存在 + 可读总结),
     *    避免「任务实际完成、产物真实存在,却因缺 DELIVER 块被误判未交付而静默中断」。
     */
    fun verifyDelivery(
        text: String,
        workspaceRoot: java.io.File,
        pathResolver: (String) -> java.io.File? = { p -> resolveDeliveryPath(p, workspaceRoot) }
    ): Boolean {
        if (!hasDelivery(text)) return false
        val paths = extractDeliveryPaths(text)
        if (paths.isEmpty()) return true
        return paths.any { p ->
            try { (pathResolver(p) ?: return@any false).exists() } catch (_: Exception) { false }
        }
    }

    /** 交付中声称但实际不存在的产物路径(供核对指令展示) */
    fun missingDeliveryPaths(
        text: String,
        workspaceRoot: java.io.File,
        pathResolver: (String) -> java.io.File? = { p -> resolveDeliveryPath(p, workspaceRoot) }
    ): List<String> =
        extractDeliveryPaths(text).filter { p ->
            try { !(pathResolver(p) ?: return@filter true).exists() } catch (_: Exception) { true }
        }

    /** 兜底：模型始终不交付时，由编排层据工具明细/文件改动合成交付块 */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun fallbackDeliveryBlock( toolCalls: List<ChatMsg.ToolStageRecord>, fileChanges: List<FileChange> ): String = ""

    /**
     * 单轮错误是否可原地重试：
     *  - 致命（鉴权/余额/配置/参数/管理员暂停）→ false，立即终止并提示用户；
     *  - 上下文溢出类（context length / token 超限）→ false，不可原地重试
     *    （历史已超预算,原地重试只会再次失败;编排层会先裁剪 history 再按 MAX_CONTEXT_RETRIES 允许「裁剪后重试」）；
     *  - 其余（网络/超时/5xx/断流/空响应/未知）→ true，由重试次数上限兜底。
     */
    fun isRetriable(error: String): Boolean {
        val e = error.lowercase()
        // 上下文溢出:误判为可重试会导致「无限原地重试→必再失败」的死循环,必须归为不可原地重试
        if (isContextOverflowError(e)) return false
        val fatal = listOf(
            "管理员暂停", "余额不足", "token 不足", "token不足",
            "401", "402", "403", "未配置", "缺少", "invalid",
            "unauthorized", "forbidden", "api key", "invalid api",
            "鉴权", "没有权限", "没有配置", "请配置"
        )
        if (fatal.any { e.contains(it) }) return false
        return true
    }

    /** 是否为「输出被截断」而非「真空失败」：上游 finish_reason == length */
    fun isTruncationFinish(reason: String): Boolean = reason == "length"
}
