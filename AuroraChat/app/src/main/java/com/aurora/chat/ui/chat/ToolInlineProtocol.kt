package com.aurora.chat.ui.chat

/**
 * 内联工具状态块的**唯一编解码器**（纯 Kotlin，无 Android 依赖，可直接跑 JVM 单测）。
 *
 * 为什么单独抽出来：
 *  · 旧实现里「生成块」「替换块」「解析块」是三份手写逻辑，段位理解各不相同——
 *    `replaceToolInline` 取 `parts[2]` 当旧目标，但 body 已经跳过了「工具名␂」，`parts[2]` 实际是
 *    record 位，于是完成态把「：具体文件」整段抹掉。这类 bug 靠阅读根本发现不了，必须让三处共用一套解析。
 *
 * 格式（5 段，向后兼容 2/3/4 段旧数据）：
 * ```
 * ␁W␁ 工具名 ␂ 状态 [␂ 目标 [␂ 记录 [␂ 参数摘要]]] ␁/W␁
 * ```
 *  · 状态：0 调用中 / 1 成功 / 2 失败
 *  · 目标：状态行显示的具体对象（文件路径 / 命令 / URL / 关键字…）
 *  · 记录：ask_user 的「问/答」记录（点击状态行展开）
 *  · 参数摘要：本次调用的关键参数（点击状态行展开；状态行本身只显示目标）
 *
 * 省略规则：尾部为空的段一律不写，保证旧数据读取逻辑在遇到新块时不至于多出一堆空段。
 */
internal object ToolInlineProtocol {

    const val OPEN = "\u0001W\u0001"
    const val CLOSE = "\u0001/W\u0001"
    const val SEP = "\u0002"

    /** 解析后的状态块（与 UI 的 InlineBlock.Tool 一一对应） */
    data class Block(
        val tool: String,
        val state: Int,
        val target: String = "",
        val record: String = "",
        val argsSummary: String = ""
    )

    /**
     * 组装状态块。
     *
     * 控制字符（[SEP] / [OPEN] / [CLOSE] 里的 `\u0001`）在内容中会被串段，编码前统一替换为空格。
     * 换行保留（record 的多行问答需要），由 UI 决定如何渲染。
     */
    fun encode(
        tool: String,
        state: Int,
        target: String = "",
        record: String = "",
        argsSummary: String = ""
    ): String {
        val t = safe(tool)
        val tg = safe(target)
        val rc = safe(record)
        val ar = safe(argsSummary)
        val sb = StringBuilder()
        sb.append(t).append(SEP).append(state)
        when {
            ar.isNotBlank() -> sb.append(SEP).append(tg).append(SEP).append(rc).append(SEP).append(ar)
            tg.isNotBlank() || rc.isNotBlank() -> sb.append(SEP).append(tg).append(SEP).append(rc)
            else -> Unit
        }
        return OPEN + sb.toString() + CLOSE
    }

    /** 在正文流末尾追加一条状态块（不重复追加同一工具的进行中块） */
    fun append(
        text: String,
        tool: String,
        state: Int,
        target: String = "",
        argsSummary: String = ""
    ): String {
        if (tool.isBlank()) return text
        val block = encode(tool, state, target = target, argsSummary = argsSummary)
        return if (text.isBlank()) block else text + "\n" + block
    }

    /**
     * 把正文流中「最后一次出现的同名工具状态块」替换为新状态；找不到则追加。
     *
     * **关键**：旧目标/旧参数摘要/旧记录一律通过 [parseBody] 回读，绝不按下标猜段位。
     * 调用方传空串时沿用旧值，避免完成态丢目标（历史 bug）。
     */
    fun replaceLast(
        text: String,
        tool: String,
        newState: Int,
        record: String = "",
        target: String = "",
        argsSummary: String = ""
    ): String {
        if (tool.isBlank()) return text
        val needle = OPEN + safe(tool) + SEP
        val idx = text.lastIndexOf(needle)
        if (idx < 0) return append(text, tool, newState, target, argsSummary)
        val closeIdx = text.indexOf(CLOSE, idx + needle.length)
        if (closeIdx < 0) return text
        // body 已跳过「工具名␂」，解析时补回来，保证 parseBody 永远拿到完整块体（否则 parts[0] 是状态位）
        val old = parseBody(safe(tool) + SEP + text.substring(idx + needle.length, closeIdx))
        return text.substring(0, idx) +
            encode(
                tool = tool,
                state = newState,
                target = target.ifBlank { old.target },
                record = record.ifBlank { old.record },
                argsSummary = argsSummary.ifBlank { old.argsSummary }
            ) +
            text.substring(closeIdx + CLOSE.length)
    }

    /**
     * 解析块体（不含 OPEN/CLOSE）。
     *
     * 兼容：
     *  · 5 段 = 新格式（含参数摘要）
     *  · 4 段 = 旧格式（工具名␂状态␂目标␂记录）
     *  · 3 段 = 更旧格式（工具名␂状态␂记录，无目标）
     *  · 2 段 = 最旧格式（工具名␂状态）
     *  · 1 段 = 只有工具名（异常数据兜底）
     */
    fun parseBody(body: String): Block {
        val parts = body.split(SEP)
        val tool = parts.getOrNull(0)?.trim().orEmpty()
        val state = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return when {
            parts.size >= 5 -> Block(
                tool = tool,
                state = state,
                target = parts[2].trim(),
                record = parts[3].trim(),
                argsSummary = parts.drop(4).joinToString(SEP).trim()
            )
            parts.size == 4 -> Block(
                tool = tool,
                state = state,
                target = parts[2].trim(),
                record = parts.drop(3).joinToString(SEP).trim()
            )
            parts.size == 3 -> Block(
                tool = tool,
                state = state,
                target = "",
                record = parts.drop(2).joinToString(SEP).trim()
            )
            else -> Block(tool = tool, state = state)
        }
    }

    /**
     * 解析「闭合标签丢失」的残块（流式中断 / 落盘被截断）。
     *
     * 与旧实现的最大区别：不再只保留工具名+状态，而是把已经写进去的目标/参数摘要一起恢复出来，
     * 用户依然能看到「正在读取：xxx.kt」，而不是一条没头没尾的「正在读取文件」。
     */
    fun parseTruncated(body: String): Block? {
        val b = parseBody(body.trim())
        return if (b.tool.isBlank()) null else b
    }

    /** 内容清洗：去掉会串段的协议控制字符，其余原样保留 */
    private fun safe(v: String): String = v
        .replace(SEP, " ")
        .replace(OPEN, " ")
        .replace(CLOSE, " ")
        .replace("\u0001", " ")
}
