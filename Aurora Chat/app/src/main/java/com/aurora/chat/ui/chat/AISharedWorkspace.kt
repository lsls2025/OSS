package com.aurora.chat.ui.chat

/**
 * 并行 AI 的**共享写入状态表 + 路径写冲突拦截**。
 *
 * ## 它解决什么
 * `agent_fork` 派发的多个子执行 AI 是**真的在并发写同一个工作区**，但过去彼此完全不可见：
 * 只靠提示词约束「各写各的路径」（那是**约定**，不是**机制**）。一旦模型不听话写了同一个文件，
 * 就是**静默互相覆盖**——后写的赢，前面白干，而且没有任何人知道。
 *
 * 现在补上两件事（**只在有 ≥2 个并行 AI 时生效**，单 AI 流程行为完全不变）：
 *  ① **冲突拦截**：写类工具的目标路径若已被另一个同时在跑的 AI 占用，直接拒绝并回喂错误，
 *     让模型立刻知道"这个路径有人在动"，改为换路径或交回调度者串行处理——
 *     把**静默覆盖**变成**显式失败**。占用用 `putIfAbsent` 原子抢占，所以两个 AI 同时首写同一路径也只有一个能过。
 *  ② **写入感知**：每次工具返回时，把「其他并行 AI 已改动的文件」作为 `parallel_note` 附带回去。
 *     子 AI 由此能**感知彼此的写入**——这正是原先缺失的那块共享状态。
 *
 * ## 边界（不吹牛）
 * - 脚本类写入（`shell_exec` / `execute_python`）的目标路径**无法预先静态得出**，
 *   不在写锁覆盖范围内，仍由「改动清单」的快照 diff 在收尾时补齐。
 * - 生命周期是"一次 fork 派发"：所有子 AI 注销后状态自动清空，不会跨任务残留。
 */
object AISharedWorkspace {

    /** 正在并行运行的 AI：id -> 可读标签（仅 fork 子执行者登记） */
    private val active = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 路径(工作区相对路径) -> 占用它的 AI id：既是写锁，也是"谁改了什么"的共享状态 */
    private val writers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** AI id -> 它占用的路径集合（失败时用于精准释放，避免把路径锁死） */
    private val ownedPaths = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** 写类工具 -> 目标路径所在的参数名（用于冲突预检与占用登记） */
    private val WRITE_TOOLS: Map<String, List<String>> = mapOf(
        "write_file" to listOf("path"),
        "edit_file" to listOf("path"),
        "append_file" to listOf("path"),
        "delete_file" to listOf("path"),
        "delete_folder" to listOf("path"),
        "rename_file" to listOf("path", "new_path", "to"),
        "copy_file" to listOf("path", "dest", "to"),
        "apply_patch" to listOf("path"),
        "batch_replace" to listOf("path"),
        "create_folder" to listOf("path")
    )

    /** 幂等写工具：同一路径重复执行无害，因此不做冲突拒绝（但仍登记，供其他 AI 感知） */
    private val IDEMPOTENT_WRITE_TOOLS = setOf("create_folder")

    /** 是否处于并行执行：≥2 个 AI 同时在跑时才启用全部机制（单 AI 永不受影响） */
    val parallel: Boolean get() = active.size >= 2

    fun register(id: String, label: String) {
        active[id] = label
    }

    /**
     * 注销：**同时释放这个 AI 占用的所有路径**，最后一个 AI 退出时清空共享状态。
     *
     * 为什么必须在这里释放(而不是等"所有子 AI 都结束"才清):
     * 旧实现在 `active.isEmpty()` 时才 `writers.clear()`。而 `parallel` 只要还有 ≥2 个活着就成立,
     * 于是「已经干完活、先退出」的那个子 AI 的路径锁**仍然生效**,继续堵着还在跑的兄弟子任务 ——
     * 兄弟明明只是要写一个别人已经写完再也不会碰的文件,却被一句"路径冲突"弹回来,白烧好几轮。
     * 现在谁退出谁把自己的锁带走,锁的生命周期与占用者真正在跑的时长严格一致。
     */
    fun unregister(id: String) {
        active.remove(id)
        ownedPaths.remove(id)?.forEach { p -> if (writers[p] == id) writers.remove(p) }
        if (active.isEmpty()) {
            writers.clear()
            ownedPaths.clear()
        }
    }

    /**
     * 路径归一化:**同一个文件只允许有一种写法**。
     *
     * 旧实现只做 `replace('\\','/')` + `removePrefix("./")`,于是:
     *  - `/a/b.txt`(模型带了个前导斜杠)与 `a/b.txt` 被当成两个不同 key → 该拦的没拦(静默覆盖);
     *  - `a//b.txt`(多余斜杠)同样绕过判定;
     *  - `a/b.txt/`(尾斜杠)与 `a/b.txt` 分裂成两个锁。
     * 归一化后四种写法落到同一个 key,冲突判定才真的成立。
     *
     * 刻意**不做大小写归一**:工作区在 ext4/f2fs 上是大小写敏感的,`A.txt` 与 `a.txt` 确实是两个文件,
     * 强行折叠会把"不同文件"误判成冲突,那是另一种错。
     */
    private fun normalizePath(raw: String): String? {
        var p = raw.trim().replace('\\', '/')
        if (p.isBlank()) return null
        // 折叠连续斜杠,并去掉结尾斜杠
        p = p.replace(Regex("/{2,}"), "/").removeSuffix("/")
        // 去掉前导 ./ 与 / (工作区相对路径没有前导斜杠的概念)
        while (true) {
            if (p.startsWith("./")) p = p.removePrefix("./")
            else if (p.startsWith("/")) p = p.removePrefix("/")
            else break
        }
        return p.ifBlank { null }
    }

    private fun targets(tool: String, argsJson: String): List<String> {
        val keys = WRITE_TOOLS[tool] ?: return emptyList()
        val o = try {
            org.json.JSONObject(argsJson.ifBlank { "{}" })
        } catch (_: Exception) {
            return emptyList()
        }
        return keys.mapNotNull { k ->
            normalizePath(o.optString(k, ""))
        }.distinct()
    }

    /**
     * 工具执行**前**的冲突预检 + 原子占用。返回非 null 表示应直接拒绝本次调用（内容即回喂给模型的错误）。
     * 只有 ≥2 个并行 AI 时才生效。
     */
    fun beginTool(tool: String, argsJson: String, agentId: String?): String? {
        if (agentId == null || !parallel) return null
        val idempotent = tool in IDEMPOTENT_WRITE_TOOLS
        for (p in targets(tool, argsJson)) {
            val owner = writers.putIfAbsent(p, agentId)
            if (owner != null && owner != agentId) {
                if (idempotent) continue
                val label = active[owner] ?: "另一个并行 AI"
                // 冲突提示必须给**可执行的下一步**,否则模型只会原样再试一次、再被拒,
                // 连撞 MAX_SAME_TOOL_FAIL_STREAK(3 次)才肯换思路,白烧 3 个工具轮次。
                return org.json.JSONObject().put("ok", false)
                    .put(
                        "error",
                        "路径冲突：$p 正被「$label」改动。" +
                            "请**二选一**(不要原样重试):" +
                            "① 把这个文件改到只属于你的路径下(如 $p 换成你的子任务前缀/${p.substringAfterLast('/')}) 后重写;" +
                            "② 放弃写这个文件,把「需要写入 $p 的内容」写进你的工作汇报交回调度者串行处理。" +
                            "绝不要直接覆盖别人的成果。"
                    )
                    .toString()
            }
            if (owner == null) {
                ownedPaths.computeIfAbsent(agentId) {
                    java.util.Collections.synchronizedSet(mutableSetOf())
                }.add(p)
            }
        }
        return null
    }

    /** 工具执行**后**：失败则释放刚占用的路径（否则一次失败的写入会把该路径永久锁死） */
    fun endTool(tool: String, argsJson: String, agentId: String?, result: String) {
        if (agentId == null) return
        val ok = try {
            org.json.JSONObject(result).optBoolean("ok")
        } catch (_: Exception) {
            false
        }
        if (ok) return
        for (p in targets(tool, argsJson)) {
            if (writers[p] == agentId) writers.remove(p)
            ownedPaths[agentId]?.remove(p)
        }
    }

    /**
     * 给工具结果附上「其他并行 AI 已改动的文件」，让子 AI 感知彼此的写入。
     * 以 JSON 字段 `parallel_note` 形式附加（不破坏原 JSON 结构，模型也能自然读到）。
     */
    fun decorate(result: String, agentId: String?): String {
        if (agentId == null || !parallel) return result
        // 先取快照再分组:并发写入时 ConcurrentHashMap 的 entrySet 迭代本身也可能被并发修改,
        // 这里必须避免在工具结果回喂这条热路径上抛 ConcurrentModificationException。
        val others = java.util.concurrent.ConcurrentHashMap(writers).entries.filter { it.value != agentId }
        if (others.isEmpty()) return result
        val grouped = others.groupBy({ it.value }, { it.key })
        val note = buildString {
            append("【并行共享状态】其他并行 AI 已改动的文件：")
            append(
                grouped.entries.joinToString("；") { (aid, paths) ->
                    "${active[aid] ?: "另一个 AI"}→${paths.sorted().joinToString("、")}"
                }
            )
            append("。请确保只改自己负责的路径，不要覆盖这些文件。")
        }
        return try {
            org.json.JSONObject(result).put("parallel_note", note).toString()
        } catch (_: Exception) {
            result
        }
    }
}
