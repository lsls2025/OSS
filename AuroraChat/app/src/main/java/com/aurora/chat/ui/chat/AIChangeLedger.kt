package com.aurora.chat.ui.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.aurora.chat.ErrorReporter

/**
 * 文件改动退回「事实源」：把单条 AI 回复 / 本轮任务携带的 ai_file_changes 精确退回，
 * 每条 FileChange 自带「改动前内容(oldText)」，逆序重放即可还原到本轮动手之前。
 *
 * 重要约束：只退【本条回复 / 本轮任务】的改动，绝聚合 / 退回整段对话的其它轮次
 * （避免「退整对话 → 把历次任务创建的文件全部删除 → 整目录被清空」这类事故）。
 * 基线快照落地到 filesDir 下独立目录，绝不进入工作区沙盒，供截断 / 二进制场景兜底。
 *
 * 设计要点：
 *  - renameTo 在 Android 上常静默失败，旧实现失败会留下新旧两个文件 → 改用 copyTo+delete 杜绝双留。
 *  - restore_file 记录的是 "restored" 且原先不写改动前内容 → 现补 oldText，并可逆。
 */

// ===== 共享退回事实源（从 AiChangesScreen.revertOneChange 上提，避免两处逻辑分叉）=====

/**
 * 退回【单条】改动的实际动作（唯一事实源）。
 * @param root 工作区根目录（ai_files）
 * @param change 单条 FileChange
 * @param baselineText 基线兜底文本（任务开始前该路径的内容）；oldText 为空/被截断时用于恢复，可空
 * @return (是否成功, 给用户看的提示文案)
 */
internal fun revertOneChangeAt(
    root: File,
    change: AiChatManager.FileChange,
    baselineText: String? = null
): Pair<Boolean, String> {
    val rootAbs = root.absolutePath.trimEnd('/', '\\') + File.separator
    val rel = change.path.trim().replace('\\', '/').trim('/')
    if (rel.isBlank() || rel.split('/').any { it == ".." }) return Pair(false, "路径非法")
    val f = File(root, rel)
    if (!f.absolutePath.startsWith(rootAbs)) return Pair(false, "路径越界")
    return try {
        when (change.op) {
            "created", "copied", "download" -> {
                if (f.exists()) f.delete()
                Pair(true, "已退回:删除新建的文件 ${change.path}")
            }
            "extract", "compress" -> {
                // 解压产物目录 / 压缩包：均为 AI 新生成的产物，退回=删除
                if (f.exists()) f.deleteRecursively()
                Pair(true, "已退回:删除生成产物 ${change.path}")
            }
            "modified" -> {
                val content = if (change.oldText.isNotEmpty()) change.oldText
                else baselineText ?: return Pair(false, "该文件修改前内容未被记录,无法恢复")
                f.parentFile?.mkdirs()
                f.writeText(content, Charsets.UTF_8)
                Pair(true, "已退回:恢复修改前内容 ${change.path}")
            }
            "deleted" -> {
                if (change.backup.isNotBlank()) {
                    val bk = File(change.backup)
                    if (!bk.exists() || !bk.isFile) return Pair(false, "备份已丢失")
                    f.parentFile?.mkdirs()
                    bk.copyTo(f, overwrite = true)
                } else if (change.oldText.isNotEmpty()) {
                    f.parentFile?.mkdirs()
                    f.writeText(change.oldText, Charsets.UTF_8)
                } else if (baselineText != null) {
                    f.parentFile?.mkdirs()
                    f.writeText(baselineText, Charsets.UTF_8)
                } else {
                    return Pair(false,
                        if (change.size > 0L) "该文件(二进制/过大)删除时未记录内容,无法恢复"
                        else "无可用内容,无法恢复")
                }
                Pair(true, "已退回:恢复被删除的文件 ${change.path}")
            }
            "renamed" -> {
                val oldRel = change.fromPath.trim().replace('\\', '/').trim('/')
                if (oldRel.isBlank() || oldRel.split('/').any { it == ".." }) return Pair(false, "原路径非法")
                val oldF = File(root, oldRel)
                if (!oldF.absolutePath.startsWith(rootAbs)) return Pair(false, "原路径越界")
                if (oldF.exists()) return Pair(false, "原路径已存在,无法退回重命名")
                if (f.exists()) {
                    // Android renameTo 常静默失败:改用复制+删除,确保新旧不双留(根治「半生不死」)
                    if (!f.renameTo(oldF)) {
                        f.copyTo(oldF, overwrite = true)
                        f.delete()
                    }
                } else {
                    val restore = if (change.oldText.isNotEmpty()) change.oldText else (baselineText ?: "")
                    oldF.parentFile?.mkdirs()
                    oldF.writeText(restore, Charsets.UTF_8)
                }
                Pair(true, "已退回:恢复原文件名 $oldRel")
            }
            "deleted_folder" -> {
                if (change.backup.isNotBlank()) {
                    val bk = File(change.backup)
                    if (!bk.isDirectory) return Pair(false, "备份已丢失")
                    f.parentFile?.mkdirs()
                    bk.copyRecursively(f, overwrite = true)
                    Pair(true, "已退回:恢复整个文件夹 ${change.path}")
                } else Pair(false, "文件夹删除时未备份,无法自动恢复,请手动重建")
            }
            "restored" -> {
                // restore_file 的逆操作:覆盖则还原覆盖前内容;原本不存在则删除恢复出来的文件
                if (change.oldText.isNotEmpty()) {
                    f.parentFile?.mkdirs()
                    f.writeText(change.oldText, Charsets.UTF_8)
                    Pair(true, "已退回:恢复被覆盖的文件 ${change.path}")
                } else if (f.exists()) {
                    f.delete()
                    Pair(true, "已退回:删除恢复出来的文件 ${change.path}")
                } else Pair(false, "该恢复操作无法退回")
            }
            else -> Pair(false, "该类型改动不支持退回（可在对应条目里单独处理）")
        }
    } catch (e: Exception) {
        ErrorReporter.warn("AIChangeRevert", "退回失败 path=${change.path} op=${change.op}: ${e.message}")
        Pair(false, "退回失败:${e.message}")
    }
}

/** 带 Context 的挂起封装（供 UI 调用，内部切 IO 线程）。 */
internal suspend fun revertOneChange(
    ctx: Context,
    change: AiChatManager.FileChange,
    baselineText: String? = null
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    revertOneChangeAt(AiChatManager.agentRoot(ctx), change, baselineText)
}

/**
 * 退回【一组】文件改动——通常就是单条 AI 回复 / 本轮任务所携带的 ai_file_changes。
 * 与逐文件退回共用 revertOneChangeAt：按路径分组后【逆序】重放，把本轮涉及的文件
 * 精确还原到本轮动手之前；被截断 / 二进制的改动走 baselineText 兜底。
 *
 * 关键约束：只退这一组改动，**绝不影响其它回复 / 其它轮次**（杜绝「退整对话 → 整目录被清空」）。
 * @param changes 单条回复携带的改动列表（来自该消息的 ai_file_changes）
 * @param baselineTextFor 路径→任务前内容 查询（来自对话基线快照），可空
 * @return 每条路径是否都成功退回
 */
suspend fun revertFileChanges(
    ctx: Context,
    changes: List<AiChatManager.FileChange>,
    baselineTextFor: (String) -> String? = { null }
): Map<String, Boolean> = withContext(Dispatchers.IO) {
    val root = AiChatManager.agentRoot(ctx)
    val byPath = LinkedHashMap<String, MutableList<AiChatManager.FileChange>>()
    for (c in changes) {
        val np = c.path.removePrefix("./").trimStart('/').replace('\\', '/').trim('/')
        if (np.isBlank()) continue
        byPath.getOrPut(np) { mutableListOf() }.add(c)
    }
    val result = LinkedHashMap<String, Boolean>()
    for ((path, list) in byPath) {
        val bt = baselineTextFor(path)
        var ok = true
        // 逆序:从最后一条改动回溯到第一条,逐步还原到本轮任务前
        for (c in list.asReversed()) {
            if (!revertOneChangeAt(root, c, bt).first) ok = false
        }
        result[path] = ok
    }
    result
}

// ===== 对话级基线快照（任务开始前 ai_files 全量拍照，供二进制/截断场景兜底）=====

/**
 * 捕获对话基线快照：把当前 ai_files 整份拷贝到内部存储。
 * 仅在「该对话尚未捕获过」时执行（每对话一次，对话开始前状态），幂等。
 * @param baselineKey 对话维度键（调用方传 AiConversationSession.storageKey()）
 */
fun captureAIBaseline(ctx: Context, baselineKey: String) {
    val dir = baselineDir(ctx, baselineKey)
    val data = File(dir, "data")
    if (data.exists()) return
    val root = AiChatManager.agentRoot(ctx)
    if (!root.exists()) return
    runCatching { root.copyRecursively(data, overwrite = true) }
        .onSuccess { ErrorReporter.debug("AIChangeLedger", "已捕获对话基线快照 -> ${data.absolutePath}") }
        .onFailure { ErrorReporter.warn("AIChangeLedger", "捕获对话基线快照失败: ${it.message}") }
}

/** 查询某路径在对话基线中的「任务前内容」（不存在返回 null）。 */
fun baselineTextForPath(ctx: Context, baselineKey: String, rel: String): String? {
    val data = File(baselineDir(ctx, baselineKey), "data")
    val f = File(data, rel.trim().replace('\\', '/').trim('/'))
    if (!f.exists() || !f.isFile) return null
    return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
}

/** 清除对话基线快照（整对话退回成功后调用，使下一次任务重新捕获新鲜基线）。 */
fun clearAIBaseline(ctx: Context, baselineKey: String) {
    runCatching { baselineDir(ctx, baselineKey).deleteRecursively() }
}

private fun baselineDir(ctx: Context, baselineKey: String): File {
    val safe = baselineKey.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return File(ctx.filesDir, "agent_change_baselines/$safe").also { it.mkdirs() }
}
