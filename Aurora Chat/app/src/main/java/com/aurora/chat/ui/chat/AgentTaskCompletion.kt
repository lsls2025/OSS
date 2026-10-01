package com.aurora.chat.ui.chat

import android.content.Context
import com.aurora.chat.ui.chat.AiChatManager.FileChange

/**
 * 交付契约与任务状态机的纯逻辑层。
 *
 * 把「Agent 能否结束」从「模型是否停止调用工具」改为「是否交付」，把「轮次预算」从硬墙改为软预算
 * （触发收尾而非终止）。被两套工具循环（ChatConversationScreen 内联循环 / AiChatManager.runAgentChatLoop）
 * 共用，从根本上避免两处行为漂移，也不让 AiChatManager（已 5934 行）继续膨胀。
 *
 * 设计约束（与现有哨兵协议共存）：
 *  - 交付块哨兵使用 \u0001 包裹，与思考 \u0001T\u0001 / 工具 \u0001W\u0001 / ask \u0001Q\u0001 同源风格；
 *  - stripToolSentinels / splitInlineBlocksV2 只识别上述三类哨兵，不会误删本交付哨兵；
 *  - 渲染层用 [stripDelivery] 清理标记，仅去除开合哨兵、保留内部可读文本。
 */
object AgentTaskCompletion {

    /** 交付块哨兵：模型交付的结构化总结包裹其中 */
    const val DELIVER_OPEN = "\u0001DELIVER\u0001"
    const val DELIVER_CLOSE = "\u0001/DELIVER\u0001"

    // ===== 各类循环分支的硬上限（防止死循环，总数可控）=====
    const val MAX_WRAP_UP_ROUNDS = 2   // 收尾轮上限（禁工具，仅纯文本交付）
    const val MAX_DELIVER_NUDGES = 2   // 防提前收工的补做次数
    const val MAX_CONTINUE_ROUNDS = 5  // finish_reason=length 自动续写次数(原 3 次:长交付/多段产物时易耗尽后被迫收尾)
    const val MAX_ROUND_RETRIES = 2    // 单轮可重试异常的重试次数
    const val MAX_FAIL_STRATEGY_ROUNDS = 2 // 连续工具全失败后,先给「换策略」补救机会的轮数(P1:失败≠立即收尾)
    const val MAX_WRAP_UP_TOOL_ROUNDS = 2 // 收尾阶段保留的工具补救额度(P1:收尾仍可少量补救,防死循环)

    /** 任务状态机阶段 */
    enum class AgentTaskPhase { NORMAL, WRAP_UP, DELIVERED }

    /**
     * 是否启用交付契约（仅「任务型执行模式」启用）：
     *  - 长任务 / 多 Agent / 自检 / 深度研究 / 规划先行 一律视为任务型；
     *  - 极速模式不启用（最快路径）；
     *  - 普通标准/craft 模式即使调了工具，也不启用——避免用户只是闲聊/问问题，
     *    AI 顺手查个文件就被当成「任务」、弹出「任务进行中」或强制交付总结。
     * usedTools 仅作兼容保留，不再作为触发条件（防止闲聊误触发）。
     */
    fun needsContract(ctx: Context, userId: Long, usedTools: Int = 0): Boolean {
        val modes = AiChatManager.getExecModes(ctx, userId)
        if (AiChatManager.EXEC_FAST in modes) return false
        return modes.any {
            it in setOf(
                AiChatManager.EXEC_LONG,
                AiChatManager.EXEC_MULTI_AGENT,
                AiChatManager.EXEC_SELFCHECK,
                AiChatManager.EXEC_RESEARCH,
                AiChatManager.EXEC_PLAN
            )
        }
    }

    /** 注入 system 提示的契约说明（追加在 buildAgentAwarenessPrompt 之后） */
    fun buildContractPrompt(ctx: Context, userId: Long): String = buildString {
        append("\n\n【交付契约】你是一个会「做事」的 Agent,不是只会聊天。当你在本次对话中调用过任何工具、或用户给了明确的可执行任务时,必须在彻底结束前交付一份结构化总结,用如下哨兵包裹:")
        append("\n$DELIVER_OPEN")
        append("\n- 目标:一句话说明用户在本次任务里要你做什么")
        append("\n- 已完成:用清单列出你实际完成的步骤/改动(含文件相对路径)")
        append("\n- 产物:交付物清单(文件名/路径;若文件卡片或媒体已发出请注明)")
        append("\n- 验证:你如何确认结果正确(重读文件/运行校验/自检结论)")
        append("\n- 遗留:未完成的项、遇到的限制、需要用户后续操作的提示")
        append("\n$DELIVER_CLOSE")
        append("\n未调用工具、纯对话答疑的场景无需交付块,直接正常作答即可。调用过工具却还没交付就想结束,会被系统判定为「提前收工」并强制你补做,请主动交付。")
    }

    /** 正文是否已含合规交付块：开合哨兵成对且内部非空；或遗漏哨兵但已出现强交付信号（避免无限 nudge）。 */
    fun hasDelivery(text: String): Boolean {
        val inner = if (text.contains(DELIVER_OPEN) && text.contains(DELIVER_CLOSE)) {
            text.substringAfter(DELIVER_OPEN).substringBefore(DELIVER_CLOSE).trim()
        } else ""
        // 完整哨兵交付块：内部有实质内容即算交付（结构由模板约束，模型按格式填写）
        if (inner.length >= 8) return true
        // 漏哨兵容错（已收紧）：必须同时具备「明确产物声明」+「路径/文件证据」+「已完成」三信号才算真交付。
        // 旧判定仅要求「已完成+验证」两个词，模型写两句空泛总结即可提前收工，导致任务没做完就断，已移除。
        val declaresProduct = text.contains("产物") || text.contains("已生成") || text.contains("已创建") ||
            text.contains("已保存") || text.contains("已写入") || text.contains("已导出")
        val hasPathEvidence = text.contains("ai_files") || text.contains("路径") || text.contains("工作区") ||
            text.contains(".kt") || text.contains(".md") || text.contains(".txt") || text.contains(".json") ||
            text.contains(".zip") || text.contains(".png") || text.contains(".jpg") || text.contains(".xml")
        return declaresProduct && hasPathEvidence && text.contains("已完成")
    }

    /** 剥离交付块哨兵（保留内部内容），供渲染层在落盘/展示时清理标记 */
    fun stripDelivery(text: String): String =
        text.replace(DELIVER_OPEN, "").replace(DELIVER_CLOSE, "").trim()

    /** 抽取交付块内部内容（含哨兵），用于 UI 单独渲染；无则返回空 */
    fun extractDelivery(text: String): String =
        if (text.contains(DELIVER_OPEN) && text.contains(DELIVER_CLOSE))
            text.substringAfter(DELIVER_OPEN).substringBefore(DELIVER_CLOSE).trim()
        else ""

    /** 轮次预算耗尽 → 收尾指令（收尾阶段保留少量工具补救额度，但以纯文本交付为主） */
    fun wrapUpInstruction(usedTools: Int, reason: String): String = buildString {
        append("【系统·收尾】本轮已达工具轮次预算上限($usedTools 轮)。请优先基于你已经完成的工作,用纯文本向用户交付最终结论:做了什么、产物清单(文件相对路径)、验证结果、以及遗留/限制。")
        if (reason.isNotBlank()) append(" 触发原因:$reason。")
        append(" 若之前没写交付块,现在务必用交付格式输出。若交付前确有关键补救步骤(如修正写错的路径、重跑一次校验)且工具额度未耗尽,可少量调用工具补救,但不要为了调用而调用。")
    }

    /** 连续工具全失败 ≥3 轮 → 换策略指令（保留工具，要求换方案而非重复同调用） */
    fun failStrategyInstruction(): String =
        "【系统·换策略】你已连续多轮调用工具全部失败,继续原样重试没有意义。请先反思失败原因(参数/路径/前置条件/环境限制),换一套方案继续:可以换参数、换工具、拆小步骤、先查环境再动手;不要重复完全相同的调用。若实在无法完成,再基于已完成工作向用户交付总结并说明限制。"

    /** 模型提前收工 → 补做指令（想结束但无交付块，且本轮无工具调用） */
    fun nudgeInstruction(): String =
        "【系统·强制补做】你本轮想要直接结束对话,但没有交付结构化的成果总结,这等同于任务没做完就跑路。请立即基于已完成的工作补齐交付(目标/已完成/产物/验证/遗留),不要再调用任何工具,直接输出交付块。若确实已全部完成,务必用交付格式说明。"

    /** 输出被截断 → 续写指令；tail 为已输出尾部片段，用于避免重复 */
    fun continueInstruction(tail: String): String = buildString {
        append("【系统·续写】你的上一次回复因输出长度限制被截断,尚未完整表达。请严格从断点继续,不要重复已经说过的内容,也不要重新开场,直接把剩下的内容写完。")
        if (tail.isNotBlank()) append("\n(已输出内容的末尾:…${tail.take(120)})")
    }

    /** 兜底：模型始终不交付时，由编排层据工具明细/文件改动合成交付块 */
    fun fallbackDeliveryBlock(
        toolCalls: List<ChatMsg.ToolStageRecord>,
        fileChanges: List<FileChange>
    ): String = buildString {
        append("\n$DELIVER_OPEN\n- 目标:(系统托管)本次为工具型任务\n")
        append("- 已完成:\n")
        if (toolCalls.isNotEmpty()) {
            toolCalls.groupBy { it.tool }.forEach { (tool, recs) ->
                val ok = recs.count { it.state == 1 }
                append("  · $tool ×${recs.size}(成功 $ok)\n")
            }
        } else append("  · (无工具调用记录)\n")
        append("- 产物:\n")
        if (fileChanges.isNotEmpty()) {
            fileChanges.forEach { append("  · ${it.path} (${it.op})\n") }
        } else append("  · (未检出文件改动)\n")
        append("- 验证:系统未收到模型的显式交付,以上为工具执行记录自动汇总。\n")
        append("- 遗留:请查看对话中的工具状态与文件卡片;如需进一步处理请继续发指令。\n")
        append("$DELIVER_CLOSE")
    }

    /**
     * 单轮错误是否可原地重试：
     *  - 致命（鉴权/余额/配置/参数/管理员暂停）→ false，立即终止并提示用户；
     *  - 其余（网络/超时/5xx/断流/空响应/未知）→ true，由重试次数上限兜底。
     */
    fun isRetriable(error: String): Boolean {
        val e = error.lowercase()
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
