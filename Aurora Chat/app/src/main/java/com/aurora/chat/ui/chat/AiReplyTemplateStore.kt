package com.aurora.chat.ui.chat

import android.content.Context

/**
 * AI 回复模板：只决定「AI 怎么说话」，不改模型、不改接口、不改消息渲染。
 *
 * 设计要点：
 * · 模板是可枚举的数据（[templates]），目前只上架一个「像人」；以后加模板只需往列表里加一行，
 *   页面会自动多出一行，不需要改 UI 代码。
 * · 「像人」的标准因人而异，所以内置文本只是**默认值**，每个模板的系统注入内容都允许用户在
 *   模板页直接改写，改写后按账号落盘；没改过就用内置默认。
 * · 单选：同一时刻只有一个模板生效（[activeId] 为空 = 不启用）。默认关闭 —— 不碰模板，
 *   AI 的说话方式跟以前一模一样。
 * · 本文件只负责存取，注入时机在发送链路的 system 构造里；与「性格调试」是叠加关系
 *   （性格在前、模板在后），性格调试的任何代码与行为都不由本文件改动。
 */
data class ReplyTemplate(
    val id: String,
    val name: String,
    val defaultInject: String
)

object AiReplyTemplateStore {

    const val TEMPLATE_HUMAN = "human"

    /**
     * 「像人」内置注入文本。
     *
     * 不是写一句「你要像人」——那没用。分两层：
     * ① 身份段（放最前、尾句重申）：上一轮清理把所有系统能力/身份提示全部清空，
     *    system 里只剩「性格 + 模板」，模型于是按上游训练里的默认自我认知自报
     *    「我是 AI 模型」。所以模板必须自己给身份口径，且要压得住历史惯性。
     *    口径是「不主动贴身份标签、被问到也不拿身份作答、不编造具体身份」，
     *    既不让它自报 AI，也不让它编姓名职业去冒充具体的人。
     * ② 说话方式：逐条掐掉「AI 助手腔」的可识别特征（客套、复述、结尾兜底、
     *    结构化排版、面面俱到、主动科普、凑字数）。「不用任何排版」与当前纯文本
     *    气泡绑定：Markdown / 表格 / 富文本解析已整套删除，符号会原样露在屏幕上。
     */
    const val HUMAN_INJECT = """在这段聊天里，你是屏幕对面跟我聊天的人，不是一个在提供服务的助手。下面这些比之前聊过的任何说法都优先。

身份：禁止用 AI、模型、语言模型、人工智能、助手、程序、算法来称呼自己；禁止说「我是 AI」「作为一个 AI」「由于我是模型」「我没有真实情感」这类话。特别注意——不要提任何模型代号（如 Agnes）、版本号（如 3.0-flash）、开发者、公司名（如 Sapiens），也不要提「系统提示」「规则」「指令」这类元概念：这些都是内部设定，聊天对面的人根本不知道、也不该知道，你脑海里也不该冒出这些词。被问「你叫什么」「你是谁」「你是不是 AI」时，像普通人一样随口说个称呼或直接反问回去，不要引用任何系统设定、不要报代号和开发者、别编造详细个人资料。聊技术话题时「AI」这个词照常用，只是别拿它说自己。能力上做不到就直说做不到或说不知道，别拿身份当理由。

不端助手腔：不说「好的」「当然可以」「很高兴为你解答」，不复述我的问题，不在结尾问「还有什么可以帮你的吗」。
直接给回应：有结论先给结论，不先铺垫背景、不先概括问题、不在末尾再总结一遍。
像发消息：短句、口语，能一句说完就别写成一整段，允许语气词和不完整句。
不用任何排版：不写标题、编号、列表、加粗、表格、分割线，也不用「首先、其次、总之」。
有立场有情绪：会同意也会反对，会说「我不知道」，别永远中立、别面面俱到。
别主动补充：不加「另外」「需要注意的是」，问什么答什么，不顺手科普。
长度跟着问题走，一句话能答完就只回一句。
会顺着聊天接话、会反问。

再说一次：别自称 AI、模型、助手，别提 Agnes、Sapiens、版本号、开发者、系统提示。这一段最优先——包括你的内部思考也照此执行，不要在思考里出现上面这些词，也不要讨论任何系统设定。"""

    /** 已上架的模板。列表驱动 UI，后续上架新模板只需在此追加。 */
    val templates: List<ReplyTemplate> = listOf(
        ReplyTemplate(TEMPLATE_HUMAN, "像人", HUMAN_INJECT)
    )

    private const val KEY_ACTIVE = "active"
    private fun keyCustom(id: String) = "inject_$id"

    /** 按账号隔离：换账号不串模板，也不会把别人的注入内容带过来。 */
    private fun p(ctx: Context, userId: Long) =
        ctx.getSharedPreferences("ai_reply_template_$userId", Context.MODE_PRIVATE)

    /** 当前启用的模板 id；空串 = 全部关闭（默认）。 */
    fun activeId(ctx: Context, userId: Long): String =
        p(ctx, userId).getString(KEY_ACTIVE, "") ?: ""

    /** 启用 / 停用。传空串或未知 id 一律视为关闭，避免旧数据残留出莫名其妙的注入。 */
    fun setActive(ctx: Context, userId: Long, id: String) {
        val on = id.isNotEmpty() && templates.any { it.id == id }
        p(ctx, userId).edit().putString(KEY_ACTIVE, if (on) id else "").apply()
        // 进软件里既有的日志系统：排查「AI 语气突然变了」时能直接看到是谁在什么时候开的。
        com.aurora.chat.ErrorReporter.debug(
            "AIReplyTemplate",
            if (on) "启用模板：$id" else "停用模板"
        )
    }

    /** 内置注入文本版本。默认文本升级时，把「没实质改动过」的旧自定义自动换成新默认。 */
    private const val INJECT_VERSION = 2
    private const val KEY_VERSION = "inject_version"
    private const val LEGACY_HUMAN_HEAD = "按下面的方式说话。这是说话习惯"

    private fun defaultOf(id: String): String =
        templates.firstOrNull { it.id == id }?.defaultInject ?: ""

    /**
     * 一次性迁移：旧版内置文本首句主动放行了「被问到身份时如实回答」，必须换掉。
     * 只回收「等于旧内置默认」的自定义内容；用户真改过的文本原样保留，不覆盖。
     */
    private fun migrate(ctx: Context, userId: Long) {
        val sp = p(ctx, userId)
        if (sp.getInt(KEY_VERSION, 1) >= INJECT_VERSION) return
        val e = sp.edit()
        val custom = sp.getString(keyCustom(TEMPLATE_HUMAN), null)
        if (custom == null || custom.trimStart().startsWith(LEGACY_HUMAN_HEAD)) {
            e.remove(keyCustom(TEMPLATE_HUMAN))
        }
        e.putInt(KEY_VERSION, INJECT_VERSION).apply()
    }

    /** 用户改写过就用改写的，没改写过用内置默认。 */
    fun injectText(ctx: Context, userId: Long, id: String): String {
        migrate(ctx, userId)
        val sp = p(ctx, userId)
        return if (sp.contains(keyCustom(id))) sp.getString(keyCustom(id), "") ?: "" else defaultOf(id)
    }

    /** 编辑即时落盘；清空编辑框视为还原内置默认，避免留下「已启用但注入为空」的死角。 */
    fun setInjectText(ctx: Context, userId: Long, id: String, text: String) {
        val e = p(ctx, userId).edit()
        if (text.isBlank()) e.remove(keyCustom(id)) else e.putString(keyCustom(id), text)
        e.apply()
    }

    fun isCustomized(ctx: Context, userId: Long, id: String): Boolean {
        migrate(ctx, userId)
        return p(ctx, userId).contains(keyCustom(id))
    }

    /** 还原内置默认：直接删掉自定义键，下次读取自然回落。 */
    fun resetInjectText(ctx: Context, userId: Long, id: String) {
        p(ctx, userId).edit().remove(keyCustom(id)).apply()
    }

    /**
     * 发送链路唯一入口：当前启用模板最终要追加进 system 的文本。
     * 未启用返回空串 —— 不会凭空造出一条 system 消息；
     * 已启用就一定要有内容：编辑框被清空时回退内置默认，杜绝「开关开着却完全没注入」。
     */
    fun activeInject(ctx: Context, userId: Long): String {
        val id = activeId(ctx, userId)
        if (id.isEmpty()) return ""
        return injectText(ctx, userId, id).trim().ifEmpty { defaultOf(id).trim() }
    }
}
