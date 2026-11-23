package com.aurora.chat.ui.chat

import android.content.Context

/**
 * AI 对话界面（UI）相关的用户偏好，全部与「当前状态」严格对齐：
 *
 *  · 每个默认值都等于改动前写死在代码里的行为 —— 用户什么都不改，界面和以前一模一样；
 *  · 用户动过的任何一项都单独落盘，不会影响其它项；
 *  · [reset] 一次性清空本文件全部键，回到出厂（=改动前）状态。
 *
 * 设备级（不按 userId 分）：这是「这台手机上 AI 长什么样」，换账号也该保持一致；
 * 也因此渲染层（MessageBubble / MessageBubbleItem）不需要把 userId 一路透传下来。
 */
object AIUiPrefs {

    /** 工具调用行停留默认时长：与旧代码写死的 1600ms 一致。 */
    const val DEFAULT_TOOL_ROW_MS = 1600

    /** 工具调用行「永不收回」的哨兵值。 */
    const val TOOL_ROW_NEVER = 0

    private fun p(ctx: Context) = ctx.getSharedPreferences("agent_ui", Context.MODE_PRIVATE)

    // ── 工具调用行 ──
    /** 停留时长（毫秒）。0 = 永不收回。 */
    fun toolRowMs(ctx: Context): Int = p(ctx).getInt("tool_row_ms", DEFAULT_TOOL_ROW_MS)

    fun setToolRowMs(ctx: Context, ms: Int) {
        p(ctx).edit().putInt("tool_row_ms", ms.coerceAtLeast(0)).apply()
    }

    /** 是否在对话流里显示工具调用行（关掉后完全不出现在正文，明细仍可在「工具调用」清单里看）。 */
    fun showToolRow(ctx: Context): Boolean = p(ctx).getBoolean("tool_row_show", true)

    fun setShowToolRow(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("tool_row_show", on).apply()
    }

    // ── 思考区 ──
    /** 思考块默认展开全部（默认关闭 = 折叠 6 行，与旧行为一致）。 */
    fun thinkingExpandAll(ctx: Context): Boolean = p(ctx).getBoolean("thinking_expand_all", false)

    fun setThinkingExpandAll(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("thinking_expand_all", on).apply()
    }

    // ── 显示设置（齿轮「设置」页，默认全关 = 保持清理后的干净普通气泡） ──
    /** 显示思考：开启时思考过程以独立气泡呈现，可收回/展开；关闭时思考阶段连占位气泡都不出现。 */
    fun showReasoning(ctx: Context): Boolean = p(ctx).getBoolean("show_reasoning", false)

    fun setShowReasoning(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("show_reasoning", on).apply()
    }

    /** 显示消耗：开启时在该条 AI 回复气泡下方显示本次输入 / 输出 / 总 Token。 */
    fun showUsage(ctx: Context): Boolean = p(ctx).getBoolean("show_usage", false)

    fun setShowUsage(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("show_usage", on).apply()
    }

    /** 整段显示：开启时正文等 AI 全部输出完才一次性上屏；关闭时开始输出就边生成边显示。 */
    fun showAfterComplete(ctx: Context): Boolean = p(ctx).getBoolean("show_after_complete", false)

    fun setShowAfterComplete(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("show_after_complete", on).apply()
    }

    // ── 消息区元素（默认全关 = 保持现状，不额外加任何东西） ──
    /** 助手头像 + 名称小行（AI 对话默认是无头像的纯正文）。 */
    fun showAiHeader(ctx: Context): Boolean = p(ctx).getBoolean("msg_ai_header", false)

    fun setShowAiHeader(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("msg_ai_header", on).apply()
    }

    fun showMsgTime(ctx: Context): Boolean = p(ctx).getBoolean("msg_time", false)

    fun setShowMsgTime(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("msg_time", on).apply()
    }

    fun showCopyBtn(ctx: Context): Boolean = p(ctx).getBoolean("msg_copy", false)

    fun setShowCopyBtn(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("msg_copy", on).apply()
    }

    fun showCharCount(ctx: Context): Boolean = p(ctx).getBoolean("msg_chars", false)

    fun setShowCharCount(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("msg_chars", on).apply()
    }

    // ── 固定工具调用与修改日志 ──
    /**
     * 固定工具调用与修改日志（默认开）。
     * 开启后：无论任务是否结束，AI 回复都常驻显示「调用 N 个工具 / 查看 N 处改动」；
     * 且流式期间每次工具调用 / 文件改动都实时写回消息并随 debounced 保存落盘，
     * 即使软件闪退，这些记录也已留在磁盘上（再叠加 AIJournal 的逐条实时日志，双重保险）。
     * 关闭后回到旧行为：只在该条消息收尾/中断时才带这些数据。
     */
    fun fixedToolChangeLog(ctx: Context): Boolean = p(ctx).getBoolean("fixed_tool_change_log", true)

    fun setFixedToolChangeLog(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("fixed_tool_change_log", on).apply()
    }

    // ── 字号 ──
    /** 0=小 1=中(默认) 2=大 */
    fun fontStep(ctx: Context): Int = p(ctx).getInt("font_step", 1).coerceIn(0, 2)

    fun setFontStep(ctx: Context, step: Int) {
        p(ctx).edit().putInt("font_step", step.coerceIn(0, 2)).apply()
    }

    /** 字号缩放比（用 LocalDensity.fontScale 传递，整块正文一起缩放，不会出现有的字号变了有的没变）。 */
    fun fontScale(ctx: Context): Float = when (fontStep(ctx)) {
        0 -> 0.90f
        2 -> 1.15f
        else -> 1f
    }

    // ── 一次性提示 ──
    /** 「Agent 功能已移至新软件」弹窗是否已展示过（首次进入 AI 对话弹一次）。 */
    fun agentMovedDialogShown(ctx: Context): Boolean =
        p(ctx).getBoolean("agent_moved_dialog_shown", false)

    fun setAgentMovedDialogShown(ctx: Context) {
        p(ctx).edit().putBoolean("agent_moved_dialog_shown", true).apply()
    }

    // ── 恢复初始 ──
    /** 清空全部界面偏好，回到改动前的原始状态。 */
    fun reset(ctx: Context) {
        p(ctx).edit().clear().apply()
    }
}
