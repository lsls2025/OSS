package com.aurora.chat.ui.chat

/**
 * 内联工具块 ↔ 持久化工具记录（[ChatMsg.ToolStageRecord]）的按序配对。
 *
 * 用途：正文里的内联工具状态块（`␁W␁ 工具名 ␂ 状态 ␂ 目标 ␂ 记录 ␂ 参数摘要 ␁/W␁` 协议）
 * 只有「工具名 / 状态 / 目标 / 参数摘要」，**没有代码正文**；而完整可复制的代码只存在于
 * 消息的 `aiToolStages` 记录里（[ChatMsg.ToolStageRecord.code]，随消息持久化）。
 * 让内联详情面板也能显示「代码胶囊」，就要把第 k 个内联工具块与第 k 条记录对上。
 *
 * 配对条件（全部满足才配对，否则返回空列表、由调用方降级为「无代码胶囊」）：
 *  · 数量一致 —— 多 AI 嵌套场景里，子执行的记录会 `addAll` 进列表而没有对应内联块，
 *    数量对不上时强行按序配对，会把 A 的代码显示到 B 的行上，宁可没有；
 *  · 逐个工具名一致 —— 双保险，防「数量巧合相同但内容错位」。
 *
 * 纯 Kotlin、无 Android 依赖，可直接跑 JVM 单测。
 *
 * @param toolNames 内联工具块按出现顺序的工具名
 * @param records 本条消息持久化的工具调用记录（按调用顺序）
 * @return 与 [toolNames] 等长同序的记录列表；无法配对时返回空列表
 */
internal fun matchToolStageRecords(
    toolNames: List<String>,
    records: List<ChatMsg.ToolStageRecord>
): List<ChatMsg.ToolStageRecord> {
    if (toolNames.isEmpty() || records.isEmpty()) return emptyList()
    // ① 完全一致（单气泡任务最常见的形态）
    if (toolNames.size == records.size) {
        if (toolNames.indices.all { toolNames[it] == records[it].tool }) return records
        return emptyList()
    }
    // ② 后缀配对：多轮任务里 aiToolStages 是**整轮累计**列表（前几轮记录也在），
    //    而本条气泡正文只有本轮的内联块 → 数量必定对不上。记录按调用顺序追加，
    //    本轮内联块一定对应列表末尾的 N 条，所以取后缀再逐名校验（名字不符仍放弃，防错位）。
    if (records.size > toolNames.size) {
        val tail = records.subList(records.size - toolNames.size, records.size)
        if (toolNames.indices.all { toolNames[it] == tail[it].tool }) return tail.toList()
    }
    return emptyList()
}
