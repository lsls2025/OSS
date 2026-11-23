package com.aurora.chat.ui.chat

import kotlin.coroutines.CoroutineContext

/**
 * 多 AI 的**角色定义与工具准入的唯一事实源**。
 *
 * ## 它解决什么
 * 三个多 AI 技能(agent_plan 规划 / agent_fork 并行派发 / agent_review 验收)此前**全部只有提示词约束**:
 * FORK_WORKER_PROMPT 写着「禁止调用 agent_plan / agent_review / agent_fork 这些元工具」,
 * REVIEWER_SYSTEM_PROMPT 写着「任何写入/删除/执行类工具都会被直接拒绝」。
 * 而机制上,子 AI 拿到的仍是**全量 100+ 工具**(多 AI 模式就是 enhanced = 全量),
 * executeAITool 也没有任何「谁在调用」的判断 —— 于是子 AI 真的能:
 *  - 再派一次 fork(每层最多 4 个,指数爆炸,直接把任务拖死);
 *  - 自己发起 agent_review(与父层的验收互相覆盖单槽的验收状态总线);
 *  - 调 ask_user 抢占同一个问答槽、调 restore_backup 抢占同一个备份审批槽。
 * 这些一旦发生,现象就是「多 AI 走不通」,而且没有任何日志能解释。
 *
 * ## 做法(沿用本项目既有范式:PLAN_MODE_BLOCKED_TOOLS / SELF_GUARD_TOOLS 都在工具层硬拦截)
 *  - ① **工具清单按角色裁剪**:看不见就不会调,从源头消除「调用必被拒」的负向循环 —— 主手段;
 *  - ② **执行层准入拦截**:executeAITool 入口按「调用者角色 x 工具」判定 —— 兜底(模型仍可硬塞工具名);
 *  - ③ 两层都要有:只有拦截会空耗大量轮次,只有裁剪则挡不住模型硬塞。
 *
 * ## 边界(不吹牛)
 *  - 这里只管**工具准入**,不管「写了哪个文件」(那是 AISharedWorkspace 的活);
 *  - 角色载体是协程上下文 AIPrincipal,只对「本进程内发起的 agent 子循环」有效;
 *  - 拒绝文案必须带可执行的替代建议,否则模型只会原地反复硬试。
 */
object AIRoles {

    /** 执行绪内的 AI 角色。ROOT 是用户那条主线;其余是被它派出去的子 AI。 */
    enum class AIRole { ROOT, PLANNER, WORKER, REVIEWER }

    /** 准入判定结果:拒绝时必须带回「下一步该怎么做」,否则模型只会反复硬试同一个工具。 */
    sealed class Admission {
        object Allow : Admission()
        data class Deny(val reason: String, val suggestion: String) : Admission()

        /** 转成喂回模型的工具结果 JSON(与 toolErr 同构:ok = false);Allow 返回 null */
        fun toToolResult(): String? = when (this) {
            is Allow -> null
            is Deny -> org.json.JSONObject()
                .put("ok", false)
                .put("error", reason + " " + suggestion)
                .toString()
        }
    }

    /** 子 AI 相对主执行者的最大嵌套深度。目前子 AI 不能派子 AI,1 层即够。 */
    const val MAX_NON_ROOT_DEPTH = 1

    /**
     * 元工具:只有主执行者(ROOT)能用。
     * 子 AI 调用它们会造成递归派发与验收状态总线互相覆盖,必须在机制层封死。
     */
    val META_TOOLS: Set<String> = setOf(
        AiChatManager.TOOL_AGENT_PLAN,
        AiChatManager.TOOL_AGENT_REVIEW,
        AiChatManager.TOOL_AGENT_FORK
    )

    /**
     * 人机独占工具:依赖**全局单槽 UI 交互**(问答面板 / 恢复审批弹窗),同一时刻只能有一个发起者。
     * 并行子 AI 抢占这些槽会让先到者被覆盖或直接失败,因此只允许主执行者发起。
     */
    val HUMAN_EXCLUSIVE_TOOLS: Set<String> = setOf(
        AiChatManager.TOOL_ASK_USER,
        AiChatManager.TOOL_RESTORE_BACKUP
    )

    /**
     * 只读规划者 / 验收者的工具白名单(两者共用同一套)。
     *
     * 与 REVIEWER_SYSTEM_PROMPT 里那串名单必须同源:过去两处分开写 —— schema 侧全量下发、
     * 门控侧只放行 19 个,模型调了「看得见却被拒」的写工具,连续失败后提前收场产出半截文本,
     * 最终判 UNVERIFIED(这正是用户实测的「验收必失败」链路之一)。
     */
    val SUB_AGENT_READ_TOOLS: Set<String> = setOf(
        "list_files", "read_file", "stat_file", "find_files", "search_in_files", "search_web",
        "fetch_webpage", "query_environment", "json_process", "regex_test", "hash_digest",
        "encode_convert", "timestamp_convert", "ping_host", "process_list", "list_my_contacts",
        "list_my_groups", "phone_screen", "capture_screen"
    )

    /**
     * 按「角色 + 嵌套深度」判定工具准入。
     *
     * @param depth 嵌套深度:ROOT 为 0,它的直接子 AI 为 1,以此类推。
     */
    fun admit(role: AIRole, depth: Int, tool: String): Admission {
        if (role == AIRole.ROOT) return Admission.Allow

        // ① 元工具:子 AI 一律禁止 —— 递归派发 / 嵌套验收都会把主任务拖垮。
        if (tool in META_TOOLS) {
            return Admission.Deny(
                reason = "「" + tool + "」是主执行者专用的编排工具,子 AI 不能调用。",
                suggestion = ""
            )
        }

        // ② 人机独占工具:单槽 UI 交互,并行下必然互相踩。
        if (tool in HUMAN_EXCLUSIVE_TOOLS) {
            return Admission.Deny(
                reason = "「" + tool + "」需要独占式人机交互,并行或嵌套执行时只能由主执行者发起。",
                suggestion = ""
            )
        }

        // ③ 只读角色(规划者 / 验收者):白名单外一律拒绝。
        if (role == AIRole.PLANNER || role == AIRole.REVIEWER) {
            val roleName = if (role == AIRole.PLANNER) "规划者" else "验收者"
            if (tool !in SUB_AGENT_READ_TOOLS) {
                return Admission.Deny(
                    reason = "你是" + roleName + ",只有只读权限,「" + tool + "」不予执行。",
                    suggestion = ""
                )
            }
            return Admission.Allow
        }

        // ④ 并行子执行者(WORKER):除上面两类之外放行(写权限本就是它的职责),
        //    路径维度的互斥由 AISharedWorkspace 负责,不在这一层重复判断。
        return if (depth > MAX_NON_ROOT_DEPTH) {
            Admission.Deny(
                reason = "嵌套深度已达上限(当前 " + depth + " 层),不允许继续执行工具。",
                suggestion = ""
            )
        } else {
            Admission.Allow
        }
    }

    /**
     * 按角色裁剪工具 schema:**看不见就不会调**。
     *
     * 为什么必须裁剪而不是只靠上面的准入拦截:模型「调用 ⇒ 被拒 ⇒ 再换一个还被拒」会白白烧掉好几轮,
     * 而嵌套子循环的轮次与墙钟预算本来就紧,白烧的轮次越多,越容易在还没出结论时就被软截止/硬超时收走。
     * 从清单里拿掉,模型就不会浪费轮次去试那些注定被拒的工具。
     *
     * @param base 完整的 tools JSONArray(每项为一个 function 描述对象)
     */
    fun toolSchemaFor(role: AIRole, base: org.json.JSONArray): org.json.JSONArray {
        if (role == AIRole.ROOT) return base
        val allowed: (String) -> Boolean = when (role) {
            AIRole.PLANNER, AIRole.REVIEWER -> { name -> name in SUB_AGENT_READ_TOOLS }
            AIRole.WORKER -> { name -> name !in META_TOOLS && name !in HUMAN_EXCLUSIVE_TOOLS }
            AIRole.ROOT -> { _ -> true }
        }
        val out = org.json.JSONArray()
        for (i in 0 until base.length()) {
            val item = base.optJSONObject(i) ?: continue
            val name = item.optJSONObject("function")?.optString("name", "").orEmpty()
            if (name.isBlank()) continue
            if (allowed(name)) out.put(item)
        }
        return out
    }
}

/**
 * 并行 / 嵌套 AI 的身份标识(协程上下文元素)。
 *
 * 为什么用协程上下文而不是参数或 ThreadLocal:
 *  - 参数:executeAITool 的调用链很长(原生 function calling / 文本协议 / 各种子循环嵌套),
 *    逐层加「我是谁」的参数侵入面太大,还容易在某条分支漏传;
 *  - ThreadLocal:协程会在 IO 线程池上跳线程,ThreadLocal 会串台(把 A 的身份带给 B)。
 *  协程上下文会随 withContext(Dispatchers.IO) 一起传递,天然跟着「当前这个 AI」走。
 *
 * 在旧版 AIIdentity 的基础上补了 role 与 depth —— 只有 id/label 无法做工具准入判定。
 */
class AIPrincipal(
    val id: String,
    val label: String,
    val role: AIRoles.AIRole,
    val depth: Int
) : kotlin.coroutines.AbstractCoroutineContextElement(AIPrincipal) {
    companion object Key : CoroutineContext.Key<AIPrincipal>

    /** 日志用的短描述 */
    fun describe(): String = "$label($role,depth=$depth)"
}
