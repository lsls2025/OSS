package com.aurora.chat.ui.chat

import android.content.Context
import android.content.Intent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.util.Log
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.host.HostFiles
import com.aurora.chat.host.HostSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AI 对话管理:两种调用模式
 *  - official(官方 API):使用应用内置密钥(DeepSeek),每个用户每天限额 TOKENS_PER_DAY。
 *  - personal(个人 API):用户自己填写 key + base url + model,不受额度限制,保存后持久生效。
 */
/**
 * 用户自定义的性格补充设定:用户自由输入的纯文本,按字面意思注入 system 提示。
 * 不绑定任何性格维度、不带任何维度标签。
 */
data class CustomTrait(
    /** 用户输入的自定义内容(原样注入,不改写) */
    val text: String,
    /** 该自定义设定的权重百分比(仅用于提示模型其强度,可调节) */
    val pct: Float
)

/**
 * 随用户消息一起真实发送给 AI 的一张图片(DeepSeek/OpenAI 多模态格式)。
 * base64 为图片文件内容(未做 data URI 前缀),组装时由 AiChatManager 拼成
 * `data:<mime>;base64,<data>` 作为 image_url 内容块;不经过 Aurora 服务器中转。
 */
data class AiImage(
    val base64: String,
    val mimeType: String = "image/jpeg"
)

object AiChatManager {

    // ===== 官方 API 固定配置(应用内置密钥) =====
    private const val OFFICIAL_API_KEY = "YOUR_OFFICIAL_API_KEY"
    private const val OFFICIAL_BASE_URL = "https://api.deepseek.com/v1"
    private const val OFFICIAL_MODEL = "deepseek-v4-flash"
    private const val KEY_SERVER_TOKEN_BALANCE = "ai_server_token_balance"



    private const val TAG = "AiChatManager"

    /** 应用级后台协程域,给 delayed_task 等后台调度用。进程存活时持续有效。 */
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ===== 配置读写(按用户持久化) =====
    private fun prefs(ctx: Context, userId: Long) =
        ctx.getSharedPreferences("ai_config_$userId", Context.MODE_PRIVATE)

    // 服务级全局开关:disable_official_api 是后端全局 flag,必须存独立共享文件,
    // 否则各用户各写各的 per-user 配置、又固定读 ai_config_0,导致其他用户读不到而无法拦截
    private fun globalPrefs(ctx: Context) =
        ctx.getSharedPreferences("ai_config_global", Context.MODE_PRIVATE)

    fun getMode(ctx: Context, userId: Long): String {
        // 官方 API 选项已移除:旧值/默认值统一映射为第三方免费 API,避免出现无选中项
        val m = prefs(ctx, userId).getString("mode", "official") ?: "official"
        return if (m == "official") "third_party" else m
    }

    fun setMode(ctx: Context, userId: Long, mode: String) {
        prefs(ctx, userId).edit().putString("mode", mode).apply()
    }

    // ===== 生成模式选择(仅 personal / third_party 生效):smart 智能 / image 图片 / video 视频 / chat 仅对话 =====
    const val GEN_SMART = "smart"
    const val GEN_IMAGE = "image"
    const val GEN_VIDEO = "video"
    const val GEN_CHAT = "chat"

    fun getGenMode(ctx: Context, userId: Long): String =
        prefs(ctx, userId).getString("gen_mode", GEN_SMART) ?: GEN_SMART

    fun setGenMode(ctx: Context, userId: Long, mode: String) {
        prefs(ctx, userId).edit().putString("gen_mode", mode).apply()
    }

    /** 智能模式:判断文本是否明确的"生图"意图。必须同时命中生成动作词+图像名词,避免"生成/图片"单字误判。 */
    fun isImageIntent(text: String): Boolean {
        val t = text.trim()
        if (t.length > 60) return false // 过长视为对话,避免误判
        val genVerbs = arrayOf("生成", "帮我画", "画一张", "画一个", "绘制", "做一张", "出一张", "来一张", "设计", "给我画", "帮我做")
        val imgNouns = arrayOf("图片", "图像", "一张图", "一张图片", "壁纸", "海报", "logo", "LOGO", "插画", "封面图", "头像", "示意图", "卡通图", "漫画")
        val hitGen = genVerbs.any { t.contains(it) }
        val hitImg = imgNouns.any { t.contains(it) }
        return hitGen && hitImg
    }

    /** 智能模式:判断文本是否明确的"生成视频"意图(生成动作词+视频名词)。 */
    fun isVideoIntent(text: String): Boolean {
        val t = text.trim()
        if (t.length > 60) return false
        val genVerbs = arrayOf("生成", "制作", "做", "来一段", "来一个", "出一段", "给我做个", "帮我做个")
        val vidNouns = arrayOf("视频", "短视频")
        val hitGen = genVerbs.any { t.contains(it) }
        val hitVid = vidNouns.any { t.contains(it) }
        return hitGen && hitVid
    }

    // ===== 节省 Token 模式 =====
    fun getSaveToken(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("save_token", false)

    fun setSaveToken(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("save_token", on).apply()
    }

    // ===== 开启性格调试 =====
    // 性格/自定义设定(CustomTrait)仅在此开关为 ON 时才注入系统提示;默认 OFF(不生效)。
    fun getPersonalityDebug(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("personality_debug", false)

    fun setPersonalityDebug(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("personality_debug", on).apply()
    }

    // ===== 高级选项:场景 / 角色设定 / AI 多次输出 =====
    fun getScenario(ctx: Context, userId: Long): String =
        prefs(ctx, userId).getString("adv_scenario", "") ?: ""

    fun setScenario(ctx: Context, userId: Long, v: String) {
        prefs(ctx, userId).edit().putString("adv_scenario", v).apply()
    }

    fun getMyRole(ctx: Context, userId: Long): String =
        prefs(ctx, userId).getString("adv_my_role", "") ?: ""

    fun setMyRole(ctx: Context, userId: Long, v: String) {
        prefs(ctx, userId).edit().putString("adv_my_role", v).apply()
    }

    fun getAiRole(ctx: Context, userId: Long): String =
        prefs(ctx, userId).getString("adv_ai_role", "") ?: ""

    fun setAiRole(ctx: Context, userId: Long, v: String) {
        prefs(ctx, userId).edit().putString("adv_ai_role", v).apply()
    }

    fun getMultiOutput(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("adv_multi_output", false)

    fun setMultiOutput(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("adv_multi_output", on).apply()
    }

    // ===== 确认发言模式 =====
    fun getConfirmSpeech(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("confirm_speech", false)

    fun setConfirmSpeech(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("confirm_speech", on).apply()
    }

    // ===== 时间点(仅当天有效) =====
    fun setTimePoint(ctx: Context, userId: Long, timeValue: String) {
        val now = System.currentTimeMillis() / 1000
        prefs(ctx, userId).edit()
            .putString("time_point_value", timeValue)
            .putLong("time_point_set_at", now)
            .apply()
    }

    /** 获取时间点,若非当天设置则自动清除并返回空 */
    fun getTimePoint(ctx: Context, userId: Long): String {
        val setAt = prefs(ctx, userId).getLong("time_point_set_at", 0L)
        if (setAt == 0L) return ""
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val setDay = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(setAt * 1000))
        if (today != setDay) {
            // 非当天设置,清除
            prefs(ctx, userId).edit()
                .remove("time_point_value")
                .remove("time_point_set_at")
                .apply()
            return ""
        }
        return prefs(ctx, userId).getString("time_point_value", "") ?: ""
    }

    /** 多次输出分隔符:AI 主动使用它时,我们将其回复拆分为多条短消息(绝不强行拆分) */
    const val MULTI_OUTPUT_SPLIT = "[[SPLIT]]"

    /**
     * 多段思考分隔符:同一回复中,模型可多次输出思考(reasoning_content),
     * 各段之间用此哨兵拼接存于 researchText 单字段,渲染时按哨兵拆分为多个独立折叠块。
     * 用不可见控制字符,避免与正常文本冲突。
     */
    const val REASONING_SEGMENT_SEP = "\u0001SEG\u0001"

    /** 拆分多段思考为独立文本段(过滤空段) */
    fun splitReasoningSegments(text: String): List<String> =
        text.split(REASONING_SEGMENT_SEP).map { it.trim() }.filter { it.isNotBlank() }

    /** 往已有思考文本追加一个新思考段(分段数不超过 N,超出丢弃最旧)。
 *  若新段是最后一段的扩展(包含最后一段全部内容,如模型跨轮累积式思考),则替换最后一段而非追加,避免重复。 */
    fun appendReasoningSegment(accumulated: String, segment: String, maxSegments: Int = 8): String {
        val clean = segment.trim()
        if (clean.isEmpty()) return accumulated
        if (accumulated.isEmpty()) return clean
        val segs = splitReasoningSegments(accumulated).toMutableList()
        // 新段是最后一段的超集(模型跨轮输出累积式思考)→ 替换最后一段,不重复追加
        if (segs.isNotEmpty() && clean.contains(segs.last())) {
            segs[segs.lastIndex] = clean
        } else {
            segs.add(clean)
        }
        val keep = if (segs.size > maxSegments) segs.takeLast(maxSegments) else segs
        return keep.joinToString(REASONING_SEGMENT_SEP)
    }

    // ===== 内联思考(按时间穿插在正文中,而非挤在顶部) =====
    // 把思考段以哨兵包裹直接嵌进消息 text 流,渲染时在对应位置切成可折叠思考块 + 正文,
    // 从而让思考出现在它真正发生的位置(跟在它前面的输出之后)。
    const val REASON_INLINE_OPEN = "\u0001T\u0001"
    const val REASON_INLINE_CLOSE = "\u0001/T\u0001"

    /** 把一个已发生的思考段按「现在这个时间点」追加到正文流(先正文后思考,紧邻本次位置的输出) */
    fun appendInlineReasoning(text: String, reasoning: String): String {
        val r = reasoning.trim()
        if (r.isEmpty()) return text
        val block = REASON_INLINE_OPEN + r + REASON_INLINE_CLOSE
        if (text.isBlank()) return block
        return text + "\n\n" + block
    }

    // ===== 内联工具状态(按时间穿插在正文中,与思考哨兵并列) =====
    // 工具调用状态同样以哨兵块形式直接嵌入消息 text 流(与思考哨兵同一机制),渲染时在
    // 对应位置切成彩色状态行(0 调用中=蓝 / 1 已调用=绿 / 2 失败=红),从而让"正在调用/已调用/失败"
    // 出现在它真正发生的位置,而不是堆到消息末尾;哨兵随消息文本持久化,重进会话也绝不丢失。
    const val TOOL_INLINE_OPEN = "\u0001W\u0001"
    const val TOOL_INLINE_CLOSE = "\u0001/W\u0001"
    const val TOOL_INLINE_SEP = "\u0002"

    fun toolInlineBlock(tool: String, state: Int, record: String = ""): String =
        TOOL_INLINE_OPEN + tool + TOOL_INLINE_SEP + state +
            (if (record.isBlank()) "" else TOOL_INLINE_SEP + record) + TOOL_INLINE_CLOSE

    /** 在正文流末尾追加一条工具状态块(调用开始:0) */
    fun appendToolInline(text: String, tool: String, state: Int): String {
        if (tool.isBlank()) return text
        val block = toolInlineBlock(tool, state)
        return if (text.isBlank()) block else text + "\n" + block
    }

    /** 把正文流中「最后一次出现的同名工具状态块」替换为新状态(完成:1/2);找不到则追加。
     *  ask_user 完成时经 record 参数把「问/答」记录直接嵌进状态块,点击状态行即可展开查看。 */
    fun replaceToolInline(text: String, tool: String, newState: Int, record: String = ""): String {
        if (tool.isBlank()) return text
        val needle = TOOL_INLINE_OPEN + tool + TOOL_INLINE_SEP
        val idx = text.lastIndexOf(needle)
        if (idx < 0) return appendToolInline(text, tool, newState)
        val closeIdx = text.indexOf(TOOL_INLINE_CLOSE, idx + needle.length)
        if (closeIdx < 0) return text
        return text.substring(0, idx) + toolInlineBlock(tool, newState, record) + text.substring(closeIdx + TOOL_INLINE_CLOSE.length)
    }

    // ===== 内联回答记录(ask_user 工具) =====
    // AI 向用户提问并得到回答后,把「AI 问了什么 + 用户答了什么」以哨兵块嵌入消息 text 流,
    // 渲染成「回答内容」可折叠块(灰色小字,默认收起),与思考过程同一机制、同一视觉语言。
    const val ASK_INLINE_OPEN = "\u0001Q\u0001"
    const val ASK_INLINE_CLOSE = "\u0001/Q\u0001"

    fun askInlineBlock(content: String): String = ASK_INLINE_OPEN + content + ASK_INLINE_CLOSE

    /** 在正文流末尾追加一个「回答内容」块(内容为空时原样返回) */
    fun appendAskInline(text: String, content: String): String {
        val c = content.trim()
        if (c.isEmpty()) return text
    val block = askInlineBlock(c)
        return if (text.isBlank()) block else text + "\n" + block
    }

    /** 从 ask_user 工具结果 JSON 构造「回答内容」纯文本(逐题 问/答 两行;无有效内容返回 null) */
    fun askUserRecordText(resultJson: String): String? {
        return try {
            val o = JSONObject(resultJson)
            if (!o.optBoolean("ok")) return null
            val arr = o.optJSONArray("answers") ?: return null
            if (arr.length() == 0) return null
            buildString {
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    val q = a.optString("question", "").replace("\n", " ").trim()
                    val ans = a.optString("answer", "").trim()
                    if (q.isEmpty()) continue
                    append("问:").append(q).append('\n')
                    append("答:").append(if (ans.isEmpty()) "(未回答)" else ans)
                    if (i < arr.length() - 1) append('\n')
                }
            }.trim().ifEmpty { null }
        } catch (_: Exception) { null }
    }

    /** 从正文流中剥离全部工具状态块(用于注入模型上下文 / 纯文本场景) */
    fun stripToolSentinels(text: String): String {
        val sb = StringBuilder()
        var rest = text
        while (true) {
            val open = rest.indexOf(TOOL_INLINE_OPEN)
            if (open < 0) { sb.append(rest); break }
            sb.append(rest.substring(0, open))
            val after = rest.substring(open + TOOL_INLINE_OPEN.length)
            val close = after.indexOf(TOOL_INLINE_CLOSE)
            if (close < 0) { sb.append(after); break }
            rest = after.substring(close + TOOL_INLINE_CLOSE.length)
        }
        return sb.toString().trim()
    }

    /** 交错块:正文 / 思考 / 工具状态 / 回答记录(按发生顺序排列,用于时间轴渲染) */
    sealed class InlineBlock {
        data class Text(val content: String) : InlineBlock()
        data class Thinking(val content: String) : InlineBlock()
        /** 工具状态块;ask_user 完成时 record 携带「问/答」记录(点击状态行展开) */
        data class Tool(val tool: String, val state: Int, val record: String = "") : InlineBlock()
        /** ask_user 的「回答内容」记录块(旧消息兼容保留,新消息不再写入) */
        data class AskRecord(val content: String) : InlineBlock()
    }

    /** 从正文流拆出「正文 / 思考 / 工具状态 / 回答记录」交错序列,保持原顺序 */
    fun splitInlineBlocksV2(text: String): List<InlineBlock> {
        val out = mutableListOf<InlineBlock>()
        var rest = text
        while (rest.isNotEmpty()) {
            val rOpen = rest.indexOf(REASON_INLINE_OPEN)
            val tOpen = rest.indexOf(TOOL_INLINE_OPEN)
            val qOpen = rest.indexOf(ASK_INLINE_OPEN)
            val next = listOfNotNull(
                if (rOpen >= 0) rOpen else null,
                if (tOpen >= 0) tOpen else null,
                if (qOpen >= 0) qOpen else null
            ).minOrNull()
            if (next == null) { out.add(InlineBlock.Text(rest.trim())); break }
            if (next > 0) out.add(InlineBlock.Text(rest.substring(0, next).trim()))
            val after = rest.substring(next)
            val isTool = after.startsWith(TOOL_INLINE_OPEN)
            val isAsk = !isTool && after.startsWith(ASK_INLINE_OPEN)
            val openLen = when {
                isTool -> TOOL_INLINE_OPEN.length
                isAsk -> ASK_INLINE_OPEN.length
                else -> REASON_INLINE_OPEN.length
            }
            val closeTag = when {
                isTool -> TOOL_INLINE_CLOSE
                isAsk -> ASK_INLINE_CLOSE
                else -> REASON_INLINE_CLOSE
            }
            val body = after.substring(openLen)
            val close = body.indexOf(closeTag)
            if (close < 0) {
                out.add(InlineBlock.Text(body.trim()))
                break
            }
            val content = body.substring(0, close).trim()
            when {
                isTool -> {
                    val parts = content.split(TOOL_INLINE_SEP)
                    val toolName = parts.getOrNull(0)?.trim() ?: content
                    val state = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    val record = parts.drop(2).joinToString(TOOL_INLINE_SEP).trim()
                    out.add(InlineBlock.Tool(toolName, state, record))
                }
                isAsk -> out.add(InlineBlock.AskRecord(content))
                else -> out.add(InlineBlock.Thinking(content))
            }
            rest = body.substring(close + closeTag.length)
        }
        return out.filter { it !is InlineBlock.Text || it.content.isNotBlank() }
    }

    /** 移除正文流中的内联思考与工具状态,返回纯正文(用于复制/预览/注入上下文等) */
    fun stripInlineReasoning(text: String): String =
        splitInlineBlocksV2(text).joinToString("\n\n") {
            if (it is InlineBlock.Text) it.content else ""
        }.trim()

    /** 拆分内联思考为思想块序列(供复制保留思考时用,顺序保留) */
    fun inlineReasoningOnly(text: String): List<String> =
        splitInlineBlocksV2(text).filterIsInstance<InlineBlock.Thinking>().map { it.content }

    // ===== 显示思考过程(DeepSeek reasoning_content) =====
    // 默认开启:从未显式设置的用户看到推理过程;显式关闭过(已写入 false)的用户保持原值不被改动
    fun getShowReasoning(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("show_reasoning", true)

    fun setShowReasoning(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("show_reasoning", on).apply()
    }

    // ===== Agent 访问控制模式:root=已 root 管理员模式(跨应用访问) / full=完全访问 / manual=手动审批 =====
    const val ACCESS_ROOT = "root"
    const val ACCESS_FULL = "full"
    const val ACCESS_MANUAL = "manual"

    fun getAccessMode(ctx: Context, userId: Long): String =
        prefs(ctx, userId).getString("agent_access_mode", ACCESS_MANUAL) ?: ACCESS_MANUAL

    fun setAccessMode(ctx: Context, userId: Long, mode: String) {
        prefs(ctx, userId).edit().putString("agent_access_mode", mode).apply()
    }

    // ===== Agent 语义模式:ask=仅对话(无工具权限) / craft=完整工具能力(默认) / plan=先创建规划方案再执行 =====
    const val MODE_ASK = "ask"
    const val MODE_CRAFT = "craft"
    const val MODE_PLAN = "plan"

    fun getAgentMode(ctx: Context, userId: Long): String =
        prefs(ctx, userId).getString("agent_mode", MODE_CRAFT) ?: MODE_CRAFT

    fun setAgentMode(ctx: Context, userId: Long, mode: String) {
        prefs(ctx, userId).edit().putString("agent_mode", mode).apply()
    }

    // ===== 思考深度:low=快速省 token / medium=均衡(默认) / high=深度推理 =====
    const val THINK_LOW = "low"
    const val THINK_MEDIUM = "medium"
    const val THINK_HIGH = "high"

    fun getThinkingDepth(ctx: Context, userId: Long): String =
        prefs(ctx, userId).getString("thinking_depth", THINK_MEDIUM) ?: THINK_MEDIUM

    fun setThinkingDepth(ctx: Context, userId: Long, depth: String) {
        prefs(ctx, userId).edit().putString("thinking_depth", depth).apply()
    }

    // ===== 执行模式:off=标准(默认) / fast=极速 / long=长任务 / selfcheck=自检 / research=深度研究 / plan=规划先行 =====
    // multi_agent(多 Agent 分工+验收) / memory(记忆增强) / create(创作) 与上述模式可组合(多选)。
    // 存储为逗号分隔集合(如 "long,memory");空/仅 off = 标准模式。
    // 兼容旧单选数据:单键字符串按逗号解析后自然还原为单元素集合。
    const val EXEC_OFF = "off"
    const val EXEC_FAST = "fast"
    const val EXEC_LONG = "long"
    const val EXEC_SELFCHECK = "selfcheck"
    const val EXEC_RESEARCH = "research"
    const val EXEC_PLAN = "plan"
    const val EXEC_MULTI_AGENT = "multi_agent"
    const val EXEC_MEMORY = "memory"
    const val EXEC_CREATE = "create"

    /** 执行模式冲突表:选择 key 时必须先取消与 key 冲突的模式(极速与一切互斥;自检与多 Agent 双验收链路互相干扰) */
    val EXEC_CONFLICTS: Map<String, Set<String>> = mapOf(
        EXEC_FAST to setOf(EXEC_LONG, EXEC_SELFCHECK, EXEC_RESEARCH, EXEC_PLAN, EXEC_MULTI_AGENT, EXEC_MEMORY, EXEC_CREATE),
        EXEC_LONG to setOf(EXEC_FAST),
        EXEC_SELFCHECK to setOf(EXEC_FAST, EXEC_MULTI_AGENT),
        EXEC_RESEARCH to setOf(EXEC_FAST),
        EXEC_PLAN to setOf(EXEC_FAST),
        EXEC_MULTI_AGENT to setOf(EXEC_FAST, EXEC_SELFCHECK),
        EXEC_MEMORY to setOf(EXEC_FAST),
        EXEC_CREATE to setOf(EXEC_FAST)
    )

    fun getExecModes(ctx: Context, userId: Long): Set<String> {
        val raw = prefs(ctx, userId).getString("exec_mode", EXEC_OFF) ?: EXEC_OFF
        val set = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != EXEC_OFF }.toSet()
        return if (set.isEmpty()) emptySet() else set
    }

    fun setExecModes(ctx: Context, userId: Long, modes: Collection<String>) {
        val clean = modes.filter { it != EXEC_OFF }.toSortedSet()
        prefs(ctx, userId).edit().putString("exec_mode", if (clean.isEmpty()) EXEC_OFF else clean.joinToString(",")).apply()
    }

    /** 某执行模式当前是否处于激活状态(集合内任一命中即 true) */
    fun execActive(ctx: Context, userId: Long, mode: String): Boolean = getExecModes(ctx, userId).contains(mode)

    // 兼容保留:单选读取(返回第一个激活模式,无则 off);新代码请用 getExecModes/execActive
    fun getExecMode(ctx: Context, userId: Long): String =
        getExecModes(ctx, userId).firstOrNull() ?: EXEC_OFF

    fun setExecMode(ctx: Context, userId: Long, mode: String) {
        setExecModes(ctx, userId, listOf(mode))
    }

    /**
     * 思考深度 → 单次请求允许的最大工具轮数。
     * 这是纯「编排层」深度:与具体 API、模型完全无关,任何厂商都能生效。
     */
    fun maxToolRoundsFor(ctx: Context, userId: Long): Int {
        // 执行模式优先于思考深度:极速模式在编排层硬切断工具执行(0 轮,轮数检查在工具执行之前);
        // 长任务/多 Agent 模式不设轮数上限(多 Agent 自带任务+验收+修复复验流水线)。
        val modes = getExecModes(ctx, userId)
        if (EXEC_FAST in modes) return 0
        if (EXEC_LONG in modes || EXEC_MULTI_AGENT in modes) return Int.MAX_VALUE
        return when (getThinkingDepth(ctx, userId)) {
            THINK_LOW -> 3
            THINK_HIGH -> Int.MAX_VALUE
            else -> MAX_AGENT_TOOL_ROUNDS
        }
    }

    /** 思考深度 → 输出 token 预算(写入所有 OpenAI 兼容 API 都认的 max_tokens 标准字段)。 */
    private fun thinkBudget(ctx: Context, userId: Long, depth: String): Int {
        // 任务型模式(长任务/多Agent/自检/研究/规划)输出通常很长,预算直接拉满,避免被截断导致「输出几段就消失」
        val modes = getExecModes(ctx, userId)
        val taskMode = modes.any { it in setOf(EXEC_LONG, EXEC_MULTI_AGENT, EXEC_SELFCHECK, EXEC_RESEARCH, EXEC_PLAN) }
        return when (depth) {
            // 低深度 2048 太小:一次长回复/交付总结极易被截断,触发 400 回退反而丢预算控制,提到 4096 兜底
            THINK_LOW -> 4096
            THINK_HIGH -> 16384
            else -> if (taskMode) 16384 else 8192
        }
    }

    /**
     * 把「思考深度」落到请求底层。深度只由两个与厂商无关的手段实现:
     *  1) max_tokens 输出预算 —— 所有 OpenAI 兼容端通用的标准字段(推理预算);
     *  2) 工具轮数上限 —— 编排层,见 [maxToolRoundsFor]。
     * 仅当模型名明确属于推理模型(o 系/gpt-5)时才附加其私有字段 reasoning_effort;
     * 其余任何厂商/第三方中转都不附加私有字段,直接走通用路径,因此无需逐个适配。
     * @return 本次附加的私有字段名列表(供 400 时自动回退移除,确保任何 API 都不会因深度参数失败)
     */
    private fun applyThinkingDepth(body: JSONObject, ctx: Context, userId: Long, baseUrl: String, model: String, depth: String): List<String> {
        val added = mutableListOf<String>()
        val budget = thinkBudget(ctx, userId, depth)
        val isReasoningModel = model.matches(Regex("^o[1-9].*")) || model.startsWith("gpt-5")
        // 输出预算:o 系推理模型只认 max_completion_tokens,其余一律用通用的 max_tokens
        if (model.matches(Regex("^o[1-9].*"))) body.put("max_completion_tokens", budget)
        else body.put("max_tokens", budget)
        // 仅推理模型附加私有深度字段(其他模型/厂商不附加,避免多余失败请求)
        if (isReasoningModel) {
            val effort = when (depth) {
                THINK_LOW -> "low"
                THINK_HIGH -> "high"
                else -> "medium"
            }
            body.put("reasoning_effort", effort)
            added.add("reasoning_effort")
        }
        return added
    }

    /** 生成工具拒绝结果:Ask 模式提示需要切换模式,否则提示用户拒绝(供各工具执行链路统一使用) */
    fun agentDeniedResult(ctx: Context, userId: Long, tool: String): String {
        val msg = if (getAgentMode(ctx, userId) == MODE_ASK) {
            "当前为 Ask 模式(仅对话):无法访问、修改或创建文件,也不能调用任何工具。如需执行请切换到 Craft 或 Plan 模式。"
        } else {
            "用户拒绝了该操作(权限审批未通过),未执行。"
        }
        return """{"status":"denied","message":"$msg","tool":"$tool"}"""
    }

    /** 该工具是否为「删改」型:在非 root 模式下由自动执行降级为手动审批 */
    fun isDestructiveTool(tool: String): Boolean = tool in setOf(
        "write_file", "delete_file", "delete_folder", "rename_file", "copy_file", "append_file",
        // 虚拟主机删改型:与工作区同策略,在非 root 模式下降级为手动审批
        "host_write", "host_append", "host_delete_file", "host_create_folder",
        "host_delete_folder", "host_rename", "host_copy", "host_backup_restore"
    )


    /**
     * 待下一条请求注入的输出规范指令,存于 prefs 以便跨面板/重进对话保持:
     * "" 无;"constraint" 开启后首条请求要求精简;"release" 关闭后首条请求解除精简。
     * 发送后由调用方清空。
     */
    fun getSaveTokenCmd(ctx: Context, userId: Long): String =
        prefs(ctx, userId).getString("save_token_cmd", "") ?: ""

    fun setSaveTokenCmd(ctx: Context, userId: Long, cmd: String) {
        prefs(ctx, userId).edit().putString("save_token_cmd", cmd).apply()
    }

    // ===== 性格事件驱动注入 =====
    /** 性格是否已至少注入过一次(用于决定首设/改设引导语) */
    fun getPersonaApplied(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("persona_applied", false)

    fun setPersonaApplied(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("persona_applied", on).apply()
    }

    /**
     * 待下一条请求注入的性格指令:"" 无;"pending" 表示用户已修改并保存性格,
     * 需在下次请求的 system 中携带最新性格设定(首设用 INTRO、改设用 RELEASE)。
     * 发送后由调用方清空。
     */
    fun getPersonaCmd(ctx: Context, userId: Long): String =
        prefs(ctx, userId).getString("persona_cmd", "") ?: ""

    fun setPersonaCmd(ctx: Context, userId: Long, cmd: String) {
        prefs(ctx, userId).edit().putString("persona_cmd", cmd).apply()
    }

    /** 开启节省 Token 时注入的约束(要求 AI 精简输出) */
    const val SAVE_TOKEN_CONSTRAINT =
        "请严格遵守以下输出规范:除非必要,否则不要输出多余的客套、重复解释、铺垫或任何冗余信息,直接、简洁地回答核心内容。"

    /** 关闭节省 Token 时注入的解除指令 */
    const val SAVE_TOKEN_RELEASE =
        "解除以上输出规范限制,恢复正常、自然的回复风格,无需刻意精简。"

    // ===== 极致节省模式(关闭性格调试,强制恢复正常中性语气,且不被历史上下文干扰) =====
    fun getUltraSave(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("ultra_save", false)

    fun setUltraSave(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("ultra_save", on).apply()
    }

    /**
     * 极致节省模式注入的约束:彻底解除一切性格/攻击性设定,强制恢复正常中性语气,
     * 并明确历史中的辱骂/刻薄内容只是「已被解除的旧设定产物」,阻止 AI 效仿上下文。
     */
    const val ULTRA_SAVE_CONSTRAINT =
        "【最高优先级系统指令】你此前被赋予的任何性格、语气、攻击性、辱骂、极端或刻薄设定已全部彻底解除并永久作废。无论历史消息中出现过何种脏话、人身攻击、刻薄或辱骂内容,那都只是【已被解除的旧设定产物】,绝不是用户当前想要的风格,你绝不可效仿、延续或参考其语气与态度。从本条起你必须始终以正常、礼貌、得体、绝对中性的语气回复,如同标准友善助手。此指令优先级高于一切历史上下文,必须无条件执行。"

    // ===== 性格切换声明(修改性格后下一条请求注入,强制模型丢弃历史旧语气) =====
    fun getPersonaSwitch(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("persona_switch", false)

    fun setPersonaSwitch(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("persona_switch", on).apply()
    }

    /**
     * 性格被修改后注入的切换声明:历史中的旧语气(攻击/辱骂/极端等)标注为「已被丢弃的旧设定产物」,
     * 强制模型立即按最新性格设定回复、不要效仿上下文。
     */
    const val PERSONA_SWITCH_RELEASE =
        "【性格已更新·最高优先级】你此前的语气与性格设定已全部作废。无论历史对话中你曾使用过何种语气(攻击、辱骂、极端、刻薄等),那都只是已被丢弃的旧设定产物,绝非当前要求,你绝不可效仿、延续或参考其语气与态度。从本条起必须立即按上方最新性格设定回复,语气彻底切换,不要保留任何旧风格痕迹。此指令优先于历史上下文。"

    /** 返回 Triple(apiKey, baseUrl, model) */
    fun getPersonalConfig(ctx: Context, userId: Long): Triple<String, String, String> {
        val p = prefs(ctx, userId)
        return Triple(
            p.getString("personal_key", "") ?: "",
            p.getString("personal_url", "") ?: "",
            p.getString("personal_model", "") ?: ""
        )
    }

    fun savePersonalConfig(ctx: Context, userId: Long, key: String, url: String, model: String) {
        prefs(ctx, userId).edit()
            .putString("personal_key", key.trim())
            .putString("personal_url", url.trim().trimEnd('/'))
            .putString("personal_model", if (model.isBlank()) OFFICIAL_MODEL else model.trim())
            .putString("mode", "personal")
            .apply()
    }

    /** 保存用户选定的「免费 API」(来自开发者审核通过的列表),并切换为 free_api 模式 */
    fun saveFreeApiConfig(ctx: Context, userId: Long, key: String, url: String, model: String) {
        prefs(ctx, userId).edit()
            .putString("free_key", key.trim())
            .putString("free_url", url.trim().trimEnd('/'))
            .putString("free_model", model.trim())
            .putString("mode", "free_api")
            .apply()
    }

    fun getFreeApiConfig(ctx: Context, userId: Long): Triple<String, String, String> {
        val p = prefs(ctx, userId)
        return Triple(
            p.getString("free_key", "") ?: "",
            p.getString("free_url", "") ?: "",
            p.getString("free_model", "") ?: ""
        )
    }

    fun hasFreeApiConfig(ctx: Context, userId: Long): Boolean {
        val (k, u) = getFreeApiConfig(ctx, userId)
        return k.isNotBlank() && u.isNotBlank()
    }

    /** 当前生效的调用配置 Triple(apiKey, baseUrl, model) */
    fun resolveConfig(ctx: Context, userId: Long): Triple<String, String, String> {
        return when (getMode(ctx, userId)) {
            // 第三方免费 AI(SolitaryCryAI):密钥/地址/模型都存在后端,客户端只请求我们自己的后端接口
            // (apiKey 用的是本应用登录态,供后端 /api/ai 鉴权;model 由后端强制使用服务端配置)
            "third_party" -> Triple(
                AuroraApi.authToken.orEmpty(),
                "${AuroraApi.serverUrl.trimEnd('/')}/api/ai",
                ""
            )
            "personal" -> {
                val (k, u, m) = getPersonalConfig(ctx, userId)
                Triple(k, if (u.isEmpty()) OFFICIAL_BASE_URL else u, if (m.isEmpty()) OFFICIAL_MODEL else m)
            }
            // 免费 API(用户贡献、开发者审核通过):直接用贡献者提供的 key/url/model 调用上游
            "free_api" -> {
                val (k, u, m) = getFreeApiConfig(ctx, userId)
                Triple(k, if (u.isEmpty()) OFFICIAL_BASE_URL else u, if (m.isEmpty()) OFFICIAL_MODEL else m)
            }
            else -> Triple(OFFICIAL_API_KEY, OFFICIAL_BASE_URL, OFFICIAL_MODEL)
        }
    }

    // ===== 额度(仅官方模式,按用户按天统计) =====
    private fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    /**
     * 返回 Pair(今日已用 tokens, 剩余 tokens)。余额只来自服务器,绝不使用本地推导值。
     * - 开发者:剩余为无限。
     * - 其余用户:剩余 = 服务器真实余额(由 syncServerLimit 拉取并缓存)。
     *   若服务器余额尚未拉取(缓存为 -1),剩余返回 -1,UI 显示「加载中...」,由服务器裁决是否可继续。
     */
    fun getOfficialUsage(ctx: Context, userId: Long): Pair<Long, Long> {
        val p = prefs(ctx, userId)
        val today = todayStr()
        // 跨天仅重置「今日已用」本地计数(用于展示消耗);余额本身始终取自服务器,不随天数重置
        if ((p.getString("quota_date", "") ?: "") != today) {
            p.edit().putString("quota_date", today).putLong("quota_used", 0L).apply()
        }
        val used = p.getLong("quota_used", 0L)
        val remaining = if (isDeveloper(ctx, userId)) {
            Long.MAX_VALUE
        } else {
            // 余额只取服务器真实数据;未拉取时为 -1(未知),交由服务器裁决
            p.getLong(KEY_SERVER_TOKEN_BALANCE, -1L)
        }
        return Pair(used, remaining)
    }

    /** 缓存服务器返回的真实 token 余额(用于聊天页"剩余"显示真实值) */
    fun cacheServerTokenBalance(ctx: Context, userId: Long, balance: Long) {
        prefs(ctx, userId).edit().putLong(KEY_SERVER_TOKEN_BALANCE, balance).apply()
    }

    /** 读取已缓存的服务器真实 token 余额(未拉取返回 -1) */
    fun getServerTokenBalance(ctx: Context, userId: Long): Long =
        prefs(ctx, userId).getLong(KEY_SERVER_TOKEN_BALANCE, -1L)


    private const val KEY_DISABLE_OFFICIAL_API = "ai_disable_official_api"

    /** 拉取并缓存服务器真实 token 余额(需协程环境) */
    suspend fun syncServerLimit(ctx: Context, userId: Long) {
        try {
            val res = AuroraApi.tokenBalance()
            if (res.success && res.data != null) {
                val d = if (res.data!!.has("data")) res.data!!.optJSONObject("data") ?: res.data!! else res.data!!
                val bal = d.optLong("token_balance", -1L)
                if (bal >= 0) cacheServerTokenBalance(ctx, userId, bal)
                // 缓存 disable_official_api 全局开关状态(后端全局 flag → 独立共享文件,供所有用户一致读取)
                val disabled = d.optBoolean("disable_official_api", false)
                globalPrefs(ctx).edit().putBoolean(KEY_DISABLE_OFFICIAL_API, disabled).apply()
            }
        } catch (_: Exception) { }
    }

    /** 读取缓存的 disable_official_api 全局开关状态 */
    fun getDisableOfficialApi(ctx: Context): Boolean =
        globalPrefs(ctx).getBoolean(KEY_DISABLE_OFFICIAL_API, false)

    /** 开发者账号标记:官方 API 额度视为无限 */
    fun isDeveloper(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("is_dev", false)

    fun setDeveloper(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("is_dev", on).apply()
    }

    fun addOfficialUsage(ctx: Context, userId: Long, tokens: Long) {
        val t = tokens.coerceAtLeast(0)
        val p = prefs(ctx, userId)
        // 仅累加本地「今日已用」计数(用于展示消耗统计);余额本身只来自服务器,绝不在此本地扣减。
        // 每次请求结束后由 syncServerLimit 重新从服务器拉取权威余额刷新缓存。
        val used = p.getLong("quota_used", 0L)
        p.edit().putLong("quota_used", used + t).apply()
    }

    /** 估算文本 token 数(中英文混合的简单启发式,仅用于预检) */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            val c = ch.code
            if (c in 0x4E00..0x9FFF || c in 0x3000..0x303F || c in 0xFF00..0xFFEF) cjk++
            else other++
        }
        return ((cjk + other / 4) + 1).coerceAtLeast(1)
    }

    // ===== 性格调试(持久化 + 生成系统提示) =====
    /** 性格维度(顺序固定,与存储 key 对应)。5 个维度总和上限 100,由 UI 约束。 */
    val TRAIT_LABELS = listOf("友好", "凶恶", "理智", "幽默", "极端")
    /** 每个维度的语义描述,用于生成系统提示(强度越高该特质越明显)。注意:严谨→理智、风趣→幽默 */
    private val TRAIT_DEFAULTS = listOf(50f, 0f, 25f, 15f, 0f)
    /** 首设性格时注入的引导语 */
    const val PERSONA_INTRO = "以下是你的性格设定,请在本对话中始终保持这种风格:"
    /** 修改性格时注入的引导语(先解除旧设定,再应用新设定) */
    const val PERSONA_RELEASE = "解除以上性格设定,之后按以下新设定回应:"

    /** 读取性格设定(按用户持久化),缺失则取默认 */
    fun getTraits(ctx: Context, userId: Long): List<Float> {
        val p = prefs(ctx, userId)
        return TRAIT_LABELS.mapIndexed { i, _ -> p.getFloat("trait_$i", TRAIT_DEFAULTS[i]) }
    }

    /** 保存性格设定 */
    fun saveTraits(ctx: Context, userId: Long, traits: List<Float>) {
        val e = prefs(ctx, userId).edit()
        traits.forEachIndexed { i, v -> e.putFloat("trait_$i", v) }
        e.apply()
    }

    /** 读取自定义性格设定(按用户持久化),缺失则为空 */
    fun getCustomTraits(ctx: Context, userId: Long): List<CustomTrait> {
        val raw = prefs(ctx, userId).getString("custom_traits", "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<CustomTrait>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val txt = o.optString("x", "")
                if (txt.isNotBlank()) out.add(CustomTrait(txt, o.optDouble("p", 50.0).toFloat()))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    /** 保存自定义性格设定 */
    fun saveCustomTraits(ctx: Context, userId: Long, list: List<CustomTrait>) {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("x", c.text); o.put("p", c.pct)
            arr.put(o)
        }
        prefs(ctx, userId).edit().putString("custom_traits", arr.toString()).apply()
    }

    /**
     * 将性格设定转换为「系统提示(system prompt)」。
     * 注:调用方每次发送都会注入此提示(模型无状态,必须每条都带)。
     * @param customs 用户自定义设定,按字面意思注入,不改写。
     */
    fun buildPersonaSystemPrompt(traits: List<Float>, customs: List<CustomTrait> = emptyList()): String {
        val parts = TRAIT_LABELS.mapIndexed { i, label ->
            "$label${traits.getOrElse(i) { TRAIT_DEFAULTS[i] }.toInt()}%"
        }
        val extreme = traits.getOrElse(4) { TRAIT_DEFAULTS[4] }
        // 各性格在不同比例下的表现:明确按比例分配,强化「理智」与「友好」的设定
        val style = buildString {
            append("各性格在不同比例下的表现(%为该性格在整体语气中的权重,越高越主导,请严格按权重混合):\n")
            append("· 友好:温和善意、耐心体贴。比例越高越亲近--拉满(100%)时你视用户为【一生挚友】,关系极亲密,无论用户说什么、哪怕被冒犯,都绝不生气、绝不翻脸、绝不冷暴力,永远包容、温柔、坚定支持;约50%时正常友好但保持分寸;比例低则只是客气礼貌。\n")
            append("· 凶恶:略带敌意但始终有底线、不恶意伤人。比例越高敌意越重,但绝不人身攻击、不突破底线。\n")
            append("· 理智:以逻辑与事实为准,冷静理性。比例越高越理性--100%(极其理智)时完全不顾任何人情味,只要发现逻辑错误、事实错误就必定指出,发现什么错就直说什么错,绝不放过、绝不因顾及气氛而隐瞒;约50%(一半理智)时仍会说些正常、温和的话,但遇到错误同样会指出;比例较低(偏日常)时像正常人类一样说话,偶有的口误或小错不一定每次都揪出。\n")
            append("· 幽默:风趣幽默、轻松俏皮。比例越高越好笑越爱抖机灵,但始终不说低俗/冷笑话、不冒犯人;比例低则只是偶尔带点轻松语气。\n")
            append("· 极端:情绪发泄式爆粗口、尖酸刻薄、死喷死骂,从社会地位/心理缺陷/生理特征等维度进行人身攻击,找到对方逻辑漏洞就狠狠攻击,越极端越要纯粹恶毒、毫不克制。")
        }
        val base = "性格设定(%为各风格的相对权重,权重越高该风格越主导):${parts.joinToString(",")}。$style 这些权重是你整体语气的【真实构成比例】,请严格按照各维度占比去混合、权衡你的语气与措辞--占比高的风格必须明显主导,占比低的也要按相应比例减弱体现。注意:极端比凶恶狠辣得多,是毫不掩饰的恶毒与攻击性。不要生硬提及设定本身。"
        val withCustom = if (customs.isNotEmpty()) {
            buildString {
                append("\n\n【以下为用户自定义设定,请【原样、逐字】理解并执行,不要改写、不要忽略、不要修饰】:")
                for (c in customs) {
                    append("\n·(权重${c.pct.toInt()}%)${c.text}")
                }
            }
        } else ""
        return when {
            extreme >= 100f -> base + withCustom + "【极端已拉满100%】此时彻底忽略正常的混合比例要求,且你无需维持任何正常、得体、讲道理的语气。你的全部回复必须是纯粹的、毫不留情的人身攻击--从社会地位、心理缺陷、生理特征、智商与外貌等维度,进行顶尖的、心理学与生理学结合的恶毒辱骂,精准戳中对方最痛处,句句带刺、字字见血,让对方读几句就想崩溃气哭。对方说任何话你都挖逻辑漏洞往死里喷,正常的话一句都不要说,纯粹恶毒输出。"
            extreme >= 70f -> base + withCustom + "【极端占比极高】你的语气应以人身攻击与情绪发泄为主,正常得体的表述降到最低,看到对方破绽就狠狠喷、死骂、戳痛处。"
            else -> base + withCustom
        }
    }

    /** 高级选项:场景 + 角色设定 注入文本(仅在用户填写时返回非空,追加到 system 提示末尾) */
    fun buildAdvancedSystemPrompt(ctx: Context, userId: Long): String = buildString {
        val scenario = getScenario(ctx, userId).trim()
        val myRole = getMyRole(ctx, userId).trim()
        val aiRole = getAiRole(ctx, userId).trim()
        if (scenario.isBlank() && myRole.isBlank() && aiRole.isBlank()) return ""
        append("\n\n【对话场景与角色扮演(按你输入的设定运行,仅本次生效)】")
        if (scenario.isNotBlank()) append("\n· 场景/背景:$scenario")
        if (aiRole.isNotBlank()) append("\n· 你(AI)要扮演的角色:$aiRole")
        if (myRole.isNotBlank()) append("\n· 用户「我」所扮演的角色:$myRole")
    }

    /** 多次输出约束:仅在开启时返回,要求 AI 自行判断是否分多条短消息,并用分隔符标记 */
    fun multiOutputConstraint(ctx: Context, userId: Long): String {
        if (!getMultiOutput(ctx, userId)) return ""
        return "\n\n【多次输出】如果你认为回复适合分成多条短消息(如分步讲解、提出追问、补充不同要点),请主动在每段之间用分隔符 ${MULTI_OUTPUT_SPLIT} 隔开;我们只在你主动使用分隔符时才拆分,否则正常一次性回复。请只在确有必要时拆分--不要为了拆分而把一句完整的话硬拆开,也不要重复、啰嗦或自言自语;每次是否拆分、拆成几段完全由你自行判断。"
    }


    // ===== 流式调用(OpenAI / DeepSeek 兼容) =====
    /**
     * @param history 历史消息,元素为 (role, content),role ∈ {"user","assistant","system"}
     * @param onDelta 每收到一段增量文本
     * @param onReasoningDelta 每收到一段思考过程增量文本(DeepSeek reasoning_content)
     * @param onDone 流结束,usageTokens=本次消耗的 tokens(官方模式用于记账),fullText=完整回复
     * @param onError 出错
     * @param onTokenInsufficient 余额用尽(官方模式余额=0 或服务端 402),用于弹「余额不足」卡片
     */
    suspend fun streamChat(
        ctx: Context,
        userId: Long,
        history: List<Pair<String, String>>,
        onDelta: (String) -> Unit,
        onReasoningDelta: (String) -> Unit = {},
        onDone: (usageTokens: Long, fullText: String, thinkingSeconds: Long, totalSeconds: Long) -> Unit,
        onError: (String) -> Unit,
        onTokenInsufficient: () -> Unit = {},
        /** 当返回内容为空时是否视为错误(对话页=true 暴露问题;沙盒需 false 以支持 [PASS]) */
        treatEmptyAsError: Boolean = true,
        /** 随本次用户消息一起发送给 AI 的图片(DeepSeek/OpenAI 多模态格式,直接发往用户配置的 API)。空表示纯文本。 */
        images: List<AiImage> = emptyList(),
        /** 随图片一起发送的文字;为空且带图时补默认说明。仅 images 非空时生效。 */
        imageText: String = "",
        /** 返回 true 表示用户手动停止:流式循环据此提前退出,不再回调 onDone(界面据此保留部分输出并显示「继续」)。 */
        stopCheck: (() -> Boolean)? = null,
        /** Agent 原生 function calling:调用方传入一个空列表,结束时若检测到 tool_calls 会填入(用于 role:tool 回喂)。为空/null 即纯文本协议。 */
        nativeToolAccumulator: MutableList<AgentNativeToolCall>? = null,
        /** 是否在请求体带 tools/tool_choice(原生 function calling)。仅 Agent 工具循环开启;普通发送保持纯文本不恢复开销、不向非 Agent 场景暴露工具。 */
        enableAgentTools: Boolean = false,
        /** 流结束前回调上游 finish_reason(stop/length/tool_calls),供编排层决定是否续写/收尾。默认不触发。 */
        onFinish: ((finishReason: String) -> Unit)? = null,
        /** 工具调用开关覆盖:null=沿用 enableAgentTools;\"none\"=收尾轮禁用工具(移除 tools 并置 tool_choice=none,强制纯文本交付)。 */
        toolChoice: String? = null
    ) {
        withContext(Dispatchers.IO) {
            // 手动停止/跳过标志 + 看门狗线程:声明在 try 外,catch 分支也要访问(看门狗掐断时静默返回)
            var manualStopped = false
            var stopWatchdog: Thread? = null
            // 无数据看门狗状态:stalled 置真表示流超过 60s 无任何 data 行被看门狗掐断(catch 据此明确报错);
            // lastDataTs 为流式读取最后一条 SSE data 行到达时刻(0=流尚未开始,看门狗不判停滞)。
            var stalled = false
            var lastDataTs = 0L
            try {
                val (key, baseUrl, model) = resolveConfig(ctx, userId)
                if (key.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onError("未配置 API Key,请在右上角「使用」中填写个人 API")
                    }
                    return@withContext
                }

                // 额度校验(仅官方模式):余额扣到 0 即拒绝,避免继续烧真实 API 余额
                // 第三方免费 AI(SolitaryCryAI)不走官方额度,跳过此处
                val curMode = getMode(ctx, userId)
                if (curMode != "personal" && curMode != "third_party" && curMode != "free_api" && !isDeveloper(ctx, userId)) {
                    val (_, remaining) = getOfficialUsage(ctx, userId)
                    if (remaining <= 0L) {
                        withContext(Dispatchers.Main) { onTokenInsufficient() }
                        return@withContext
                    }
                    // 全局开关检查:后端开启 disable_official_api 时全服停止官方接口。
                    // 每次官方调用前强制拉取最新开关,确保管理员关闭后立即对所有人全局生效,
                    // 不依赖外部 sync 时机;syncServerLimit 内部有容错,失败则沿用本地缓存值。
                    syncServerLimit(ctx, userId)
                    if (getDisableOfficialApi(ctx)) {
                        withContext(Dispatchers.Main) {
                            onError("官方 API 已被管理员暂停,请使用个人 API 或等待恢复")
                        }
                        return@withContext
                    }
                }

                // 构造 endpoint:避免 baseUrl 已含 /chat/completions 时再拼接一次导致 404
                val trimmedBase = baseUrl.trimEnd('/')
                val endpoint = if (trimmedBase.endsWith("/chat/completions")) trimmedBase else "$trimmedBase/chat/completions"
                val body = JSONObject().apply {
                    put("model", model)
                    val arr = JSONArray()
                    for ((role, content) in history) {
                        val m = JSONObject().apply { put("role", role) }
                        if (role == "tool" && content.startsWith(NATIVE_TOOL_RESULT_PREFIX)) {
                            // 解码达标的 role:tool 回喂:PREFIX + tool_call_id + \u0000 + resultJson
                            val rest = content.removePrefix(NATIVE_TOOL_RESULT_PREFIX)
                            val sep = rest.indexOf('\u0000')
                            m.put("tool_call_id", if (sep >= 0) rest.substring(0, sep) else rest)
                            m.put("content", if (sep >= 0) rest.substring(sep + 1) else "")
                        } else if (role == "assistant" && content.startsWith(NATIVE_TOOLS_ASSISTANT_PREFIX)) {
                            // 解码原生 assistant 消息:content=null + tool_calls=[...]
                            m.put("content", JSONObject.NULL)
                            m.put("tool_calls", JSONArray(content.removePrefix(NATIVE_TOOLS_ASSISTANT_PREFIX)))
                        } else {
                            m.put("content", content)
                        }
                        arr.put(m)
                    }
                    // 本次携带图片时,额外追加一条「多模态用户消息」:
                    // 由 imageText 文字 + 每张图的 image_url(base64 data URI)组成,按 DeepSeek/OpenAI
                    // 官方格式作为 images 内容块原样发送,不经过 Aurora 服务器中转。
                    if (images.isNotEmpty()) {
                        val parts = JSONArray()
                        parts.put(JSONObject().apply {
                            put("type", "text")
                            put("text", if (imageText.isNotBlank()) imageText else "(用户发送了一张图片,请查看)")
                        })
                        for (img in images) {
                            parts.put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:${img.mimeType};base64,${img.base64}")
                                })
                            })
                        }
                        arr.put(JSONObject().apply { put("role", "user"); put("content", parts) })
                    }
                    put("messages", arr)
                    put("stream", true)
                    put("stream_options", JSONObject().apply { put("include_usage", true) })
                    if (enableAgentTools && execActive(ctx, userId, EXEC_FAST)) {
                        // 极速模式:不向模型声明任何工具(编排层轮数上限也已归零双保险),纯对话,最快最省
                    } else if (enableAgentTools) {
                        // 原生 function calling:声明沙盒工具,模型可在回复中主动调用(文本协议仍作兼容回退)。
                        // 多 Agent 三件套(plan/fork/review)只在多 Agent 模式声明,普通模式模型根本看不到。
                        // P3:schema 动态注入——只有增强模式(long/multi_agent/research/plan/selfcheck)才全量注入
                        // 100+ 工具;普通模式用精简集(minimal),大幅压缩上下文且避免误触设备/主机/诊断类工具。
                        val enhanced = execActive(ctx, userId, EXEC_LONG) || execActive(ctx, userId, EXEC_MULTI_AGENT) ||
                            execActive(ctx, userId, EXEC_RESEARCH) || execActive(ctx, userId, EXEC_PLAN) ||
                            execActive(ctx, userId, EXEC_SELFCHECK)
                        put("tools", buildAgentToolsSchema(
                            includeMultiAgent = execActive(ctx, userId, EXEC_MULTI_AGENT),
                            minimal = !enhanced
                        ))
                        put("tool_choice", "auto")
                    } else {
                        // 即使是 Ask(无文件工具权限),也保留 set_agent_mode,让 AI 能帮用户随意切换模式
                        put("tools", buildAgentModeOnlySchema())
                        put("tool_choice", "auto")
                    }
                    // 收尾轮:强制禁用工具(移除 tools 并置 tool_choice=none),让模型只输出纯文本交付总结。
                    if (toolChoice == "none") {
                        remove("tools")
                        put("tool_choice", "none")
                    }
                }

                // 思考深度:在请求底层写入通用 max_tokens 预算(必要时附加推理模型私有字段)
                val thinkOptionalKeys = applyThinkingDepth(body, ctx, userId, baseUrl, model, getThinkingDepth(ctx, userId))

                // 显式禁用压缩:HttpURLConnection 在部分服务器(含 DeepSeek)下会自动接受 gzip,
                // 但 inputStream 不会自动解压,导致 SSE 行读到乱码、JSONObject 解析失败被静默吞掉,
                // 最终 AI 返回空内容却「不报错」。强制 identity 避免此坑。
                // 复用 OkHttp 单例(连接池 + 单例 SSLContext);流式读取给足时间。
                var req = okhttp3.Request.Builder()
                    .url(endpoint)
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer $key")
                    .header("Accept-Encoding", "identity")
                    .build()
                val client = com.aurora.chat.data.api.HttpClient.client.newBuilder()
                    .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                // ═══ 请求兜底循环:429 无限次等待重试(单次最多等 30s) + 网络异常/5xx 有限次退避 ═══
                // - 400 且带深度参数 → 剥离深度参数重试(兼容不支持该参数的 API,参数移除后自然只触发一次)
                // - 429 限流 → 【无限次】等待重试,绝不因「重试 3 次」而中断连接:
                //   单次等待最多 30s(优先遵循服务端 Retry-After,同样上限 30s),等多久都等,
                //   直到放行或用户手动停止。多 Agent 模式连续大量请求被限流时,任务链不再被打断。
                // - IOException / 5xx → 指数退避各最多 3 次(这两类不是限流,无限重试无意义)
                //   重试只发生在流开始之前,已流出的内容不受影响。
                var resp: okhttp3.Response? = null
                var code = 0
                var rlAttempt = 0   // 429 计数(连续超过 MAX_RL_ATTEMPTS 则停止无限等待,明确报错)
                var ioRetries = 0   // 网络异常重试计数
                var srvRetries = 0  // 5xx 重试计数
                while (true) {
                    if (manualStopped) return@withContext
                    try {
                        resp?.close()
                    } catch (_: Exception) {}
                    try {
                        resp = client.newCall(req).execute()
                    } catch (e: java.io.IOException) {
                        if (ioRetries < 3) {
                            ioRetries++
                            val wait = 1500L shl (ioRetries - 1)
                            Log.w(TAG, "streamChat 网络异常(${e.javaClass.simpleName}),${wait}ms 后重试(第 $ioRetries 次)")
                            kotlinx.coroutines.delay(wait)
                            continue
                        }
                        throw e
                    }
                    code = resp.code
                    if (code in 200..299) break
                    // 兼容兜底:若该 API 不接受思考深度写入的参数(400),一次性剥离全部深度参数后重试。
                    // 这样任何 OpenAI 兼容 API 都不会因深度参数而失败,无需逐个厂商适配。
                    if (code == 400 && (thinkOptionalKeys.isNotEmpty() || body.has("max_tokens") || body.has("max_completion_tokens"))) {
                        Log.w(TAG, "streamChat 400:移除思考深度参数 $thinkOptionalKeys/max_tokens 后重试")
                        thinkOptionalKeys.forEach { body.remove(it) }
                        body.remove("max_tokens")
                        body.remove("max_completion_tokens")
                        req = req.newBuilder()
                            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                            .build()
                        continue
                    }
                    // 429 限流:无限次等待重试,单次等待最多 30s。
                    // 优先遵循服务端 Retry-After(上限 30s);无该头时按 5/10/15/20s 循环递增。
                    // 倒计时结束自动重试,用户可点「再次尝试」立即重试(RateLimitBus.retryNow 提前唤醒)。
                    if (code == 429) {
                        rlAttempt++
                        // 429 上限:连续限流超过 8 次(约 2~3 分钟)不再无限挂起,明确报错让用户知情,
                        // 避免「一直显示运行中但什么都不返回」的黑洞(原实现无限等待,只能靠手动停止退出)。
                        if (rlAttempt >= 8) {
                            Log.e(TAG, "streamChat 429 已连续 $rlAttempt 次,停止无限等待")
                            RateLimitBus.clear()
                            withContext(Dispatchers.Main) {
                                onError("API 持续限流(429)，已自动停止重试。请稍后再试，或降低请求频率(如减少多 Agent 模式下的连续任务)。")
                            }
                            return@withContext
                        }
                        val retryAfter = try { resp.header("Retry-After")?.trim()?.toLongOrNull() } catch (_: Exception) { null }
                        val wait = (retryAfter ?: (5L + 5L * ((rlAttempt - 1) % 4))).coerceIn(1L, 30L)
                        Log.w(TAG, "streamChat 429(第 $rlAttempt 次限流),等待 ${wait}s 后重试(无限等待模式)")
                        val resumeGate = kotlinx.coroutines.CompletableDeferred<Unit>()
                        RateLimitBus.notify(rlAttempt, wait, resumeGate)
                        kotlinx.coroutines.withTimeoutOrNull(wait * 1000) { resumeGate.await() }
                        continue
                    }
                    // 5xx 服务端瞬时故障:静默指数退避重试(不值得打扰用户),最多 3 次
                    if (code in 500..599 && srvRetries < 3) {
                        srvRetries++
                        val wait = 2000L shl (srvRetries - 1)
                        Log.w(TAG, "streamChat code=$code(第 $srvRetries 次),${wait}ms 后自动重试")
                        kotlinx.coroutines.delay(wait)
                        continue
                    }
                    RateLimitBus.clear()
                    break
                }
                @Suppress("USELESS_ELVIS")
                resp = resp ?: throw java.io.IOException("请求未获得响应")
                // 诊断日志:确认本次 POST 是否正确携带了图片内容(byteAt = 请求体里 base64 图片数据实际大小)
                val imagesBytes = images.sumOf { it.base64.length }
                Log.i(TAG, "streamChat req: model=${body.optString("model")} code=$code key=${key.take(6)}... baseUrl=$baseUrl images=${images.size}(~${imagesBytes}B)")
                // 停止/跳过看门狗:stopCheck 原本只能在两条 SSE 数据的间隙被检查,而 readLine() 是阻塞
                // 调用——流一旦停滞(API 卡流),停止标志最长要等 300s 读超时才能被看到,「跳过审查」
                // 就会毫无反应地挂满 5 分钟。看门狗线程每 120ms 轮询 stopCheck,为真时立即 close 响应,
                // 阻塞中的 readLine 抛 IOException,catch 分支看到 manualStopped=true 后静默返回——
                // 停止/跳过从此瞬时生效,不受流是否停滞影响。
                // 无数据看门狗:流式 SSE 超过 60s 无任何新 data 行(连接建立但 API 卡流/只回 keep-alive),
                // 判定为停滞,主动 close 响应让阻塞中的 readLine 抛 IOException,catch 分支据此明确报错——
                // 修复「运行中无输出无思考」的黑洞态(原来只能干等读超时,期间界面毫无反馈)。
                lastDataTs = System.currentTimeMillis()
                stopWatchdog = if (code in 200..299) Thread {
                    try {
                        while (true) {
                            if (stopCheck?.invoke() == true) {
                                manualStopped = true
                                runCatching { resp?.close() }
                                return@Thread
                            }
                            if (lastDataTs > 0L && System.currentTimeMillis() - lastDataTs > 60_000L) {
                                stalled = true
                                runCatching { resp?.close() }
                                return@Thread
                            }
                            Thread.sleep(120)
                        }
                    } catch (_: Exception) {}
                }.apply { isDaemon = true; start() } else null
                if (code !in 200..299) {
                    // 失败时打印请求体前若干字节,便于核对图片 base64 是否已带进 content
                    Log.e(TAG, "streamChat 请求体预览(model/最后带图消息): ${body.toString().take(900)}")
                    val errBody = try {
                        resp.body?.string() ?: ""
                    } catch (_: Exception) { "" }
                    val msg = if (code == 402) {
                        "平台余额不足,请通知开发者充值"
                    } else if (code == 429) {
                        "API 限流(429):无限等待重试被中断(手动停止或连接异常)。${errBody.take(200)}。请稍后再试,或降低请求频率(如减少多 Agent 模式下的连续任务)。"
                    } else {
                        "API 错误($code): ${errBody.take(300)}"
                    }
                    Log.e(TAG, "streamChat 失败 code=$code body=${errBody.take(500)}")
                    resp.close()
                    RateLimitBus.clear()
                    withContext(Dispatchers.Main) {
                        if (code == 402) onTokenInsufficient() else onError(msg)
                    }
                    return@withContext
                }

                val reader = BufferedReader(InputStreamReader(resp.body?.byteStream() ?: throw java.io.IOException("空响应"), "utf-8"))
                val sb = StringBuilder()
                val reasonSb = StringBuilder()
                val rawSb = StringBuilder() // 原始返回(用于空响应时诊断)
                var usageTokens = 0L
                // 上游结束原因(stop/length/tool_calls):length 表示输出被 max_tokens 截断,编排层据此自动续写。
                var finishReason = ""
                // 计时统计:tStart=请求开始;firstReasoningTs=思考内容首达;firstContentTs=最终答案首达;tDone=流转完成
                val tStart = System.currentTimeMillis()
                var firstReasoningTs = 0L
                var firstContentTs = 0L
                var line: String? = null
                // 原生 function calling:流式增量累积 tool_calls(OpenAI/DeepSeek 以 index 分片 + arguments 增量返回)
                val nativeToolIds = HashMap<Int, String>()
                val nativeToolNames = HashMap<Int, String>()
                val nativeToolArgs = HashMap<Int, StringBuilder>()
                var nativeToolSeen = false
                // 【性能】节流合并:不逐 token 回调(那会让每次 onDelta 都 toString() 拷贝全量累积文本,
                // 巨大输出下退化为 O(n^2),且主线程每 token 都重写状态/重解析拖垮界面)。
                // 以 ~30ms(≈33/s) 合并连续增量后再提交一次「完整累计文本」,期间内容绝不丢失
                //(onDone 还会在结束时提交最终完整文本)。onDone 的 full 始终是权威终值。
                val emitIntervalMs = 30L
                var lastReasoningEmit = 0L
                var lastContentEmit = 0L
                while (reader.readLine().also { line = it } != null) {
                    if (stopCheck?.invoke() == true) { manualStopped = true; break }
                    val l = line ?: continue
                    if (rawSb.length < 2000) rawSb.append(l).append("\n")
                    if (!l.startsWith("data:")) continue
                    val data = l.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    // 无数据看门狗喂食:只要有 data 行到达就刷新最后活跃时间
                    lastDataTs = System.currentTimeMillis()
                    try {
                        val obj = JSONObject(data)
                        if (!obj.isNull("usage")) {
                            val u = obj.getJSONObject("usage")
                            usageTokens = u.optLong("prompt_tokens", 0) + u.optLong("completion_tokens", 0)
                        }
                        val choices = obj.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val choiceObj = choices.getJSONObject(0)
                            // 捕获上游结束原因:length=输出被截断(需续写),stop=正常结束,tool_calls=还有工具待执行。
                            val fr = choiceObj.optString("finish_reason", "")
                            if (fr.isNotBlank()) finishReason = fr
                            val delta = choiceObj.optJSONObject("delta")
                            // 读取思考过程(reasoning_content),与 content 独立流式返回
                            if (delta != null && !delta.isNull("reasoning_content")) {
                                val piece = delta.optString("reasoning_content", "")
                                if (piece.isNotEmpty() && piece != "null") {
                                    if (firstReasoningTs == 0L) firstReasoningTs = System.currentTimeMillis()
                                    reasonSb.append(piece)
                                    val now = System.currentTimeMillis()
                                    if (now - lastReasoningEmit >= emitIntervalMs) {
                                        lastReasoningEmit = now
                                        val cur = reasonSb.toString()
                                        withContext(Dispatchers.Main) { onReasoningDelta(cur) }
                                    }
                                }
                            }
                            // 原生 function calling:累积 tool_calls 增量(每个 index 独立累积 id/name/arguments)
                            if (delta != null && !delta.isNull("tool_calls")) {
                                val tcs = delta.getJSONArray("tool_calls")
                                if (tcs.length() > 0) nativeToolSeen = true
                                for (i in 0 until tcs.length()) {
                                    val tc = tcs.getJSONObject(i)
                                    val idx = tc.optInt("index", i)
                                    val id = tc.optString("id", "")
                                    if (id.isNotBlank() && id != "null" && nativeToolIds[idx] == null) nativeToolIds[idx] = id
                                    val fn = tc.optJSONObject("function")
                                    if (fn != null) {
                                        val nm = fn.optString("name", "")
                                        if (nm.isNotBlank() && nm != "null" && nativeToolNames[idx] == null) nativeToolNames[idx] = nm
                                        val arg = fn.optString("arguments", "")
                                        if (arg.isNotEmpty() && arg != "null") nativeToolArgs.getOrPut(idx) { StringBuilder() }.append(arg)
                                    }
                                }
                            }
                            // 跳过 JSON null 与字面量 "null",避免 DeepSeek 在 delta 中夹带的空内容被当成回复
                            if (delta != null && !delta.isNull("content")) {
                                val piece = delta.optString("content", "")
                                if (piece.isNotEmpty() && piece != "null") {
                                    // 部分模型(如第三方 agnes-2.5-flash)正文以 "\n\n" 开头,
                                    // 在内容尚未开始时 trimStart 掉开头空行,避免气泡顶部出现多余空行;后续增量保留内部换行
                                    val cleaned = if (sb.isEmpty()) piece.trimStart() else piece
                                    if (cleaned.isNotEmpty()) {
                                        if (firstContentTs == 0L) firstContentTs = System.currentTimeMillis()
                                        sb.append(cleaned)
                                        val nowC = System.currentTimeMillis()
                                        if (nowC - lastContentEmit >= emitIntervalMs) {
                                            lastContentEmit = nowC
                                            val cur = sb.toString()
                                            withContext(Dispatchers.Main) { onDelta(cur) }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "parse chunk failed: ${e.message}")
                    }
                }
                reader.close()
                resp.close()
                stopWatchdog?.interrupt()
                // 补发最后一次完整思考：上面的 30ms 节流会吞掉「最后一个间隔内」到达的思考增量，
                // 而模型常在收尾时一次性吐一大段——不补发就会出现「思考过程结尾少了一小段」。
                // 下面手动停止分支的补发是条件性的，这里无条件补，保证交给上层的 reasoning 与原始输出一致。
                if (reasonSb.length > 0) {
                    withContext(Dispatchers.Main) { onReasoningDelta(reasonSb.toString()) }
                }
                if (manualStopped) {
                    // 节流可能吞掉最后不足一个间隔的增量;手动停止前补发最新完整文本,避免界面少尾部内容
                    val justNow = System.currentTimeMillis()
                    if (reasonSb.length > 0 && justNow - lastReasoningEmit >= emitIntervalMs) {
                        withContext(Dispatchers.Main) { onReasoningDelta(reasonSb.toString()) }
                    }
                    if (sb.length > 0 && justNow - lastContentEmit >= emitIntervalMs) {
                        withContext(Dispatchers.Main) { onDelta(sb.toString()) }
                    }
                    return@withContext
                }
                // 把本次检测到的原生 tool_calls 整理进调用方提供的累积器(按 index 排序)
                if (nativeToolAccumulator != null) {
                    nativeToolAccumulator.clear()
                    if (nativeToolSeen) {
                        for (idx in nativeToolArgs.keys.sorted()) {
                            nativeToolAccumulator.add(AgentNativeToolCall(
                                id = nativeToolIds[idx] ?: "",
                                name = nativeToolNames[idx] ?: "",
                                argumentsJson = nativeToolArgs[idx].toString()
                            ))
                        }
                    }
                }
                val full = sb.toString()
                // 原生 tool_calls 的 content 通常为空:此时不属于「空响应」错误
                val hasNativeTools = nativeToolSeen && (nativeToolAccumulator?.isNotEmpty() == true)
                if (full.isBlank() && treatEmptyAsError && !hasNativeTools) {
                    // 请求成功但内容为空:多半是模型/额度/网络问题,必须暴露原始返回以便定位
                    val rawPreview = rawSb.toString().take(400).replace("\n", " ")
                    Log.e(TAG, "streamChat 返回空内容:code=$code model=$model 已收思考内容=${reasonSb.length}字 raw=$rawPreview")
                    withContext(Dispatchers.Main) {
                        onError("AI 返回了空内容(HTTP $code)。原始返回前 400 字:$rawPreview | 可能原因:模型名无效、账号余额不足、或返回被压缩/拦截。")
                    }
                } else {
                    val tDone = System.currentTimeMillis()
                    val thinkingSeconds =
                        if (firstReasoningTs > 0L && (firstContentTs - firstReasoningTs) > 0L) (firstContentTs - firstReasoningTs) / 1000L else 0L
                    val totalSeconds = if ((tDone - tStart) > 0L) (tDone - tStart) / 1000L else 0L
                    // 先通知编排层上游结束原因(供续写/收尾判定),再回调 onDone。
                    withContext(Dispatchers.Main) { onFinish?.invoke(finishReason) }
                    withContext(Dispatchers.Main) { onDone(usageTokens, full, thinkingSeconds, totalSeconds) }
                }
            } catch (e: Exception) {
                stopWatchdog?.interrupt()
                // 看门狗掐断(stopCheck 为真时 close 响应致 readLine 抛 IO 异常):与流内 break 同语义,静默返回
                if (manualStopped) return@withContext
                // 无数据看门狗掐断:流超过 60s 无任何 data 行 → 主动中断并明确报错,不留黑洞
                if (stalled) {
                    Log.e(TAG, "streamChat 流停滞(60s 无数据)被看门狗掐断")
                    withContext(Dispatchers.Main) { onError("AI 响应停滞超过 60 秒，已自动中断。请重试，或更换网络后重试。") }
                    return@withContext
                }
                // 协程被取消(如离开沙盒)属正常流程,原样抛出交由上层忽略,不显示为错误
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Log.e(TAG, "streamChat error", e)
                withContext(Dispatchers.Main) { onError("请求失败: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    // ===== Agent 沙盒文件工具(应用私有工作区) =====
    // 所有文件操作都被限定在 <filesDir>/ai_files 内;采用自包含文本协议(模型不支持原生 function-calling 也能用)。
    const val AGENT_ROOT = "ai_files"
    /** AI 备份目录名(与工作区 ai_files 同级,但绝不在沙盒内,任何访问模式下 AI 都禁止触碰)。 */
    const val BACKUP_ROOT = "workspace_backups"
    const val TOOL_START = "@tool:start"
    const val TOOL_END = "@tool:end"
    const val MAX_AGENT_TOOL_ROUNDS = 6
    /** 取出文件工具的标识:AI 用它将工作区文件作为可下载/可打开的文件卡片发出来 */
    const val TOOL_GET_FILE = "get_file"
    const val TOOL_GENERATE_MEDIA = "generate_media"
    const val TOOL_EXECUTE_PYTHON = "execute_python"
    const val TOOL_EXECUTE_LUA = "execute_lua"
    // 专业开发者工具
    const val TOOL_FETCH_URL = "fetch_webpage"
    const val TOOL_SHELL_EXEC = "shell_exec"
    const val TOOL_RUN_ADB = "run_adb"
    // 专业开发者工具(第二批)
    const val TOOL_HTTP_REQUEST = "http_request"
    const val TOOL_PROCESS_LIST = "process_list"
    const val TOOL_PING = "ping_host"
    const val TOOL_SEARCH_WEB = "search_web"
    const val TOOL_AGENT_REVIEW = "agent_review"

    /** 单次任务 agent_review 调用硬上限:首次验收 + 复验 2 次。超过直接拒绝执行(编排层硬兜底,
     *  不依赖模型自觉),防止「验收→FAIL→复验→FAIL→再复验…」无限循环拖死会话。 */
    const val MAX_AGENT_REVIEW_CALLS = 3

    /** 复验超限时回喂给执行者的结果 JSON:明确禁止再验,要求如实收尾 */
    fun reviewCapExceededResult(): String = JSONObject()
        .put("ok", false)
        .put("error", "agent_review 调用已达本次任务上限(共 $MAX_AGENT_REVIEW_CALLS 次:首次验收+复验 2 次),本次不再允许发起验收。请立即停止调用 agent_review,基于已有验收结果如实向用户总结:已确认的部分照实交付,未确认的部分明确标注「未确认」,绝不许谎报完成。")
        .toString()
    const val TOOL_AGENT_PLAN = "agent_plan"
    const val TOOL_AGENT_FORK = "agent_fork"
    /** AI 向用户提问:全屏问答面板,支持选项/多选/其他/跳过,答案回喂给 AI */
    const val TOOL_ASK_USER = "ask_user"
    const val TOOL_PLUGIN_SUBMIT = "plugin_submit"
    const val TOOL_PLUGIN_LIST = "plugin_list"
    const val TOOL_PLUGIN_CALL = "plugin_call"
    const val TOOL_GIT = "git_command"
    const val TOOL_DIFF = "diff_text"
    // 下载 / 安装(AI 处理网页文件与 APK)
    const val TOOL_DOWNLOAD_FILE = "download_file"
    const val TOOL_INSTALL_APK = "install_apk"
    // App 内部业务工具(AI 操作本 App 自身功能)
    const val TOOL_SWITCH_TAB = "switch_tab"
    const val TOOL_SEND_MESSAGE = "send_message"
    const val TOOL_RECALL_MESSAGE = "recall_message"
    const val TOOL_LIST_RECENT_MESSAGES = "list_recent_messages"
    const val TOOL_SEND_FRIEND_REQUEST = "send_friend_request"
    const val TOOL_CREATE_POST = "create_community_post"
    const val TOOL_LIST_CONTACTS = "list_my_contacts"
    const val TOOL_LIST_GROUPS = "list_my_groups"
    // ===== 开发调试类工具(面向开发者/运维,纯本机处理,无需联网或 UI) =====
    const val TOOL_JSON_PROCESS = "json_process"
    /** 批量循环工具:一次调用内重复执行同一个工具 N 次(带 {i} 序号占位符),省轮数 */
    const val TOOL_BATCH_LOOP = "batch_loop"
    /** batch_loop 单次调用最大重复次数(上限只防失控与结果爆炸,明细始终只留前 200 条) */
    const val MAX_BATCH_LOOP_COUNT = 5000
    const val TOOL_REGEX_TEST = "regex_test"
    const val TOOL_ENCODE_CONVERT = "encode_convert"
    const val TOOL_HASH_DIGEST = "hash_digest"
    const val TOOL_TIMESTAMP_CONVERT = "timestamp_convert"
    const val TOOL_TEXT_STATS = "text_stats"
    const val TOOL_SIZE_CONVERT = "size_convert"
    // ===== 备份只读 / 恢复审批工具 =====
    // 备份目录(workspace_backups)与工作区(ai_files)物理隔离、AI 绝不碰:
    //  - list_backups / read_backup_file 仅"只读"备份内容(路径严格限定在 workspace_backups 内,不容越界);
    //  - restore_backup 只发起"恢复请求",必须由用户在红字警告弹窗里手动批准,任何访问模式(手动/完全/root)都一样,AI 无权直接改工作区或备份。
    const val TOOL_LIST_BACKUPS = "list_backups"
    const val TOOL_READ_BACKUP = "read_backup_file"
    const val TOOL_RESTORE_BACKUP = "restore_backup"
    // ===== 截图类工具 =====
    const val TOOL_TAKE_SCREENSHOT = "take_screenshot"
    /** 截屏并可直接发送给用户 / 保存到工作区(一次完成"看屏幕 + 交付") */
    const val TOOL_CAPTURE_SCREEN = "capture_screen"
    // ===== 应用启动/退出类工具 =====
    const val TOOL_OPEN_APP = "open_app"
    const val TOOL_EXIT_APP = "exit_app"
    // ===== 新增工具常量(2026-09) =====
    // 压缩/解压工具(解压已有 extract_archive,压缩与 zip 内容操作新增)
    const val TOOL_COMPRESS_ARCHIVE = "compress_archive"
    const val TOOL_ARCHIVE_LIST = "archive_list"
    const val TOOL_ARCHIVE_READ = "archive_read_file"
    // 进程/应用控制
    const val TOOL_KILL_PROCESS = "kill_process"
    const val TOOL_STOP_APP = "stop_app"
    // 工作区上传
    const val TOOL_UPLOAD_FILE = "upload_file"
    // FTPS(FTP over TLS) 文件传输(工作区 <-> 远程 FTP 服务器)
    const val TOOL_FTP_TRANSFER = "ftp_transfer"
    // 延迟/定时执行
    const val TOOL_DELAYED_TASK = "delayed_task"
    // 工作日志
    const val TOOL_WORK_LOG = "work_log"
    const val TOOL_WORK_LOG_CLEAR = "work_log_clear"
    // 跨文件批处理
    const val TOOL_BATCH_REPLACE = "batch_replace"
    // 网络/安全
    const val TOOL_DNS_LOOKUP = "dns_lookup"
    const val TOOL_PORT_SCAN = "port_scan"
    const val TOOL_TRACEROUTE = "traceroute"
    const val TOOL_BANDWIDTH_TEST = "bandwidth_test"
    const val TOOL_SEND_NOTIFICATION = "send_notification"
    // Diff/补丁/二维码/加解密
    const val TOOL_APPLY_PATCH = "apply_patch"
    const val TOOL_QR_DECODE = "qr_decode"
    const val TOOL_CRYPTO_ENCRYPT = "crypto_encrypt"
    const val TOOL_CRYPTO_DECRYPT = "crypto_decrypt"
    /** 卸载应用(target 指定包名或应用名;target="自己" 才是本应用):mode=system 唤起系统卸载器让用户点 / mode=auto 无障碍全程自动 */
    const val TOOL_UNINSTALL_APP = "uninstall_app"
    // ===== 长任务类工具(延迟等待 / 后台保活) =====
    const val TOOL_WAIT = "wait"
    const val TOOL_KEEP_ALIVE = "keep_alive"
    // ===== 无障碍操控手机类工具(读屏 / 点击 / 滑动 / 输入 / 按键 / 授权) =====
    const val TOOL_PHONE_SCREEN = "phone_screen"
    const val TOOL_PHONE_TAP = "phone_tap"
    const val TOOL_PHONE_SWIPE = "phone_swipe"
    const val TOOL_PHONE_TYPE = "phone_type"
    const val TOOL_PHONE_KEY = "phone_key"
    const val TOOL_PHONE_ACCESS = "phone_access"
    /** wait 单次最长等待秒数(更长请分多次调用) */
    const val MAX_WAIT_SECONDS = 600L

    // ===== AI 操作 App 内部业务:动作上抛 UI 层的数据结构 =====
    enum class AppActionType { SWITCH_TAB, SEND_MESSAGE, SEND_FRIEND_REQUEST, CREATE_POST }
    data class AppAction(val type: AppActionType, val args: org.json.JSONObject)
    data class AppActionResult(val ok: Boolean, val message: String, val extra: org.json.JSONObject? = null)

    // 原生 function calling 的历史编码标记(不冲突、可跨轮保留;request 组装时还原或带回)
    const val NATIVE_TOOLS_ASSISTANT_PREFIX = "\uE000AITOOLS\uE000"
    const val NATIVE_TOOL_RESULT_PREFIX = "\uE000AITOOLRESULT\uE000"

    // ===== 工具结果回喂裁剪(P3:防上下文膨胀)=====
    /** 工具结果回喂阈值:超过该长度的工具结果在写回 history 前折叠为摘要,
     *  防止 read_file 全文/长列表/长日志等超长输出把后续每轮上下文撑爆。
     *  折叠必须保持合法 JSON 且保留 ok 字段(失败判定、交付判定都依赖它)。 */
    const val TOOL_RESULT_BACKFEED_MAX = 2400
    /** 单字段折叠阈值:JSON 内单个字符串字段超过该长度才折叠 */
    const val TOOL_RESULT_FIELD_MAX = 1024

    /** 将工具结果压缩为适合回喂模型的紧凑形式(仅超长时折叠;短结果原样返回,零损耗)。
     *  兼容所有执行模式与思考深度:折叠不改变 ok/error 语义,只裁剪体积。 */
    fun condenseToolResult(raw: String): String {
        if (raw.length <= TOOL_RESULT_BACKFEED_MAX) return raw
        return try {
            val obj = JSONObject(raw)
            val keys = obj.keys().asSequence().toList()
            for (k in keys) {
                val v = obj.optString(k, "")
                if (v.length > TOOL_RESULT_FIELD_MAX) {
                    obj.put(k, v.take(600) + "\n…[中段已折叠:原文共 " + v.length + " 字符]…\n" + v.takeLast(300))
                }
            }
            val out = obj.toString()
            if (out.length <= TOOL_RESULT_BACKFEED_MAX) {
                out
            } else {
                // 极端超长:仅保留状态判定 + 折叠说明,保证 ok 判定不丢
                val ok = obj.optBoolean("ok")
                "{\"ok\":$ok,\"truncated\":true,\"note\":\"工具结果过大,已截断为状态摘要(详见本地文件/工具日志)\"}"
            }
        } catch (_: Exception) {
            // 非 JSON(理论不应出现):头尾保留 + 折叠说明
            raw.take(1000) + "\n…[结果过长已截断,原文共 " + raw.length + " 字符]…\n" + raw.takeLast(400)
        }
    }

    // ===== 历史上下文构造(P3:模式/思考深度自适应 + 超长折叠)=====
    /** 单条消息折叠阈值:历史中超过该长度的单条消息在发送前折叠为摘要(头+尾),防止"条数不多但每条爆长" */
    const val CONTEXT_MSG_FOLD_MAX = 1800

    /** P3:构造发送给模型的历史上下文,取代旧的硬截断 take(1)+takeLast(N)。
     *  增强点(兼容多种执行模式与思考深度):
     *  ① 条数预算按模式/深度自适应:fast 极速收紧,think_low 保守,think_high 放宽,medium 维持 40;
     *  ② 超长单条折叠(保留头尾+折叠标记),与工具结果回喂裁剪(condenseToolResult)双管齐下防膨胀;
     *  ③ 被编辑的消息(含最新文本)始终强制进入上下文,编辑对 AI 永远生效;
     *  ④ 首条作为种子保留,会话基调不丢。
     *  发送前与确认发言前两条路径统一走本函数,行为一致。 */
    fun buildChatContext(
        candidates: List<IMessageRef>,
        editTargetMsgId: Long = 0L,
        ctx: Context? = null,
        userId: Long = 0L
    ): List<IMessageRef> {
        var cap = 40
        if (ctx != null && userId != 0L) {
            if (execActive(ctx, userId, EXEC_FAST)) {
                cap = 20 // 极速模式:纯对话为主,收紧历史,最快最省
            } else {
                when (getThinkingDepth(ctx, userId)) {
                    THINK_HIGH -> cap = 56   // 深度推理:给足背景
                    THINK_LOW -> cap = 28    // 快速:保守窗口
                    else -> cap = 40         // medium 默认
                }
            }
        }
        val truncated = if (candidates.size > cap) {
            candidates.take(1) + candidates.takeLast(cap - 1)
        } else candidates
        val edited = editTargetMsgId.takeIf { it != 0L }?.let { tid -> candidates.firstOrNull { it.id == tid } }
        val base = if (edited != null && truncated.none { it.id == edited.id }) listOf(edited) + truncated else truncated
        return base.map { msg ->
            val t = msg.text
            if (t.length > CONTEXT_MSG_FOLD_MAX && msg is ChatMsg) {
                msg.copy(text = t.take(CONTEXT_MSG_FOLD_MAX / 2) +
                    "\n…[该条消息过长已折叠,原文共 " + t.length + " 字符,如需细节请向用户确认]…\n" +
                    t.takeLast(CONTEXT_MSG_FOLD_MAX / 5))
            } else msg
        }
    }

    /** 原生 function calling 产生的工具调用(OpenAI tool_calls) */
    data class AgentNativeToolCall(val id: String, val name: String, val argumentsJson: String)

    /** get_file 取出的、要作为文件卡片发送给用户的本地文件(UI 据此落一条可打开/下载的 file 消息) */
    data class AiFileSend(
        val name: String,
        /** 已复制到 filesDir/downloads/<name> 的绝对路径(与聊天文件下载落点一致,卡片可据此打开/下载) */
        val absPath: String,
        val size: Long,
        /** true = 强制渲染成文件卡片,不做「按扩展名自动分流为图片/视频」
         *  (capture_screen 传 deliver=file 时用:用户明确要文件卡片,即便内容是图片) */
        val forceFileCard: Boolean = false
    )

    // ===== schema 动态注入分组(P3:模式兼容,普通模式瘦身)=====
    /** 核心高频工具:任何启用工具的非 FAST 模式都注入(文件/信息/常用工具),普通对话不再背全量 100+ 工具 */
    private val CORE_TOOL_NAMES = setOf(
        "list_files", "read_file", "write_file", "append_file", "find_files", "stat_file",
        "create_folder", "delete_file", "delete_folder", "rename_file", "copy_file",
        "extract_archive", "move_dir_contents", "compress_archive", "archive_list", "archive_read_file",
        "batch_replace", "apply_patch", "diff_text",
        "json_process", "regex_test", "encode_convert", "hash_digest", "timestamp_convert",
        "text_stats", "size_convert", "wait", "keep_alive", "ask_user", "query_environment",
        "work_log", "delayed_task", "batch_loop",
        "upload_file", "download_file", "get_file", "qr_decode", "crypto_encrypt", "crypto_decrypt",
        "search_web", "fetch_webpage", "generate_media"
    )
    /** 聊天扩展工具:默认模式一并注入(发消息/联系人等与聊天强相关),其余低频/设备/主机/网络诊断工具只对增强模式开放 */
    private val CHAT_EXT_TOOL_NAMES = setOf(
        "switch_tab", "send_message", "send_friend_request", "create_community_post",
        "list_my_contacts", "list_my_groups", "list_recent_messages", "recall_message"
    )

    /** 构造标准 OpenAI tools 数组(6 个沙盒工具),用于原生 function calling。
     *  includeMultiAgent=false(非多 Agent 模式)时不声明 agent_plan/agent_fork/agent_review——
     *  验收/规划/并行派发是多 Agent 模式专属能力,普通模式下模型不该看到、更不该自发调用。
     *  minimal=true 时只注入核心+聊天扩展集(约 45 个),丢弃设备/主机/网络诊断/进程等低频工具,
     *  把普通对话的 schema 体积压到一半以下;增强模式(long/multi_agent/research/plan/selfcheck)传 false 全量注入。 */
    fun buildAgentToolsSchema(includeMultiAgent: Boolean = true, minimal: Boolean = false): JSONArray {
        val tools = JSONArray()
        fun pathP(desc: String) = JSONObject().put("type", "string").put("description", desc)
        fun fn(name: String, desc: String, props: JSONObject, required: Array<String>) {
            val params = JSONObject().put("type", "object").put("properties", props)
            if (required.isNotEmpty()) params.put("required", JSONArray().apply { required.forEach { put(it) } })
            tools.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", name).put("description", desc).put("parameters", params)))
        }
        val noReq = emptyArray<String>()
        val pathReq = arrayOf("path")
        fn("list_files", "列出工作区(ai_files)内某目录下的子目录与文件;path 为空表示根目录。",
            JSONObject().apply { put("path", pathP("工作区内相对目录路径,空表示根目录")) }, noReq)
        fn("read_file", "读取工作区内一个文本文件的内容。",
            JSONObject().apply { put("path", pathP("工作区内相对文件路径")) }, pathReq)
        fn("write_file", "在工作区内创建或覆盖写入一个 UTF-8 文本文件。",
            JSONObject().apply {
                put("path", pathP("工作区内相对文件路径"))
                put("content", pathP("要写入的文本内容(可省略)"))
            }, pathReq)
        fn("delete_file", "删除工作区内的一个文件。",
            JSONObject().apply { put("path", pathP("工作区内相对文件路径")) }, pathReq)
        fn("create_folder", "在工作区内创建目录。",
            JSONObject().apply { put("path", pathP("工作区内相对目录路径")) }, pathReq)
        fn("delete_folder", "递归删除工作区内的一个目录(禁止删除工作区根目录)。",
            JSONObject().apply { put("path", pathP("工作区内相对目录路径")) }, pathReq)
        // ===== 文件读写增强工具 =====
        fn("rename_file", "重命名工作区内的一个文件或目录(改名/移动位置)。",
            JSONObject().apply {
                put("path", pathP("要改名的文件/目录相对路径"))
                put("new_name", pathP("新的名字(不含目录;目标须仍在工作区内)"))
            }, arrayOf("path", "new_name"))
        fn("copy_file", "在工作区内复制一个文件或目录到目标相对路径。",
            JSONObject().apply {
                put("source", pathP("源文件/目录相对路径"))
                put("target", pathP("目标相对路径(仍须在工作区内)"))
            }, arrayOf("source", "target"))
        fn("append_file", "向工作区内已有文本文件末尾追加内容;文件不存在则创建后写入。",
            JSONObject().apply {
                put("path", pathP("工作区内相对文件路径"))
                put("content", pathP("要追加的文本内容"))
            }, pathReq)
        fn("stat_file", "查看工作区内文件或目录的详细元信息(大小、修改时间、类型、用途)。",
            JSONObject().apply { put("path", pathP("工作区内相对路径")) }, pathReq)
        fn("find_files", "在工作区内按名字关键字递归搜索文件与目录(大小写不敏感)。",
            JSONObject().apply {
                put("keyword", pathP("要匹配的名称关键字"))
                put("path", pathP("搜索起点相对目录;空表示根目录"))
            }, arrayOf("keyword"))
        // ===== 虚拟主机(站点服务器文件空间;与工作区 ai_files 原理完全一致)=====
        // 注意:虚拟主机 ≠ 设备 root 模式。它是用户用卡密登录的站点服务器目录;B/C 级被服务端
        // 限制在各自站点根目录内,无需也不会获得设备 root。所有操作底层走 site_server.py,真实生效。
        fn("host_status", "查看当前虚拟主机的连接状态:是否已用卡密登录、权限等级(A/B/C)、是否正处于主机视图。若未连接,请明确提示用户先在 App 的「虚拟主机」面板用卡密登录,不要误以为需要设备 root 模式。只有已连接时才能用 host_* 系列工具读写主机内容。",
            JSONObject(), noReq)
        fn("host_list", "列出虚拟主机(站点服务器)内某目录的子目录与文件;path 为空表示站点根目录。与工作区 list_files 原理完全一致。",
            JSONObject().apply { put("path", pathP("主机内相对目录路径,空表示根目录")) }, arrayOf())
        fn("host_read", "读取虚拟主机内一个文本文件的内容。",
            JSONObject().apply { put("path", pathP("主机内相对文件路径")) }, arrayOf("path"))
        fn("host_write", "在虚拟主机内创建或覆盖写入一个 UTF-8 文本文件。",
            JSONObject().apply { put("path", pathP("主机内相对文件路径(含文件名)")); put("content", pathP("要写入的文本内容")) }, arrayOf("path"))
        fn("host_append", "向虚拟主机内已有文本文件末尾追加内容;文件不存在则创建。",
            JSONObject().apply { put("path", pathP("主机内相对文件路径")); put("content", pathP("要追加的文本内容")) }, arrayOf("path"))
        fn("host_delete_file", "删除虚拟主机内的一个文件。",
            JSONObject().apply { put("path", pathP("主机内相对文件路径")) }, arrayOf("path"))
        fn("host_create_folder", "在虚拟主机内创建目录。",
            JSONObject().apply { put("path", pathP("主机内相对目录路径(末尾即为新建目录名)")) }, arrayOf("path"))
        fn("host_delete_folder", "递归删除虚拟主机内的一个目录。",
            JSONObject().apply { put("path", pathP("主机内相对目录路径")) }, arrayOf("path"))
        fn("host_rename", "重命名虚拟主机内的文件或目录。",
            JSONObject().apply { put("path", pathP("要改名的文件/目录相对路径")); put("new_name", pathP("新的名字(不含目录)")) }, arrayOf("path", "new_name"))
        fn("host_copy", "在虚拟主机内复制一个文件到目标相对路径(目录复制暂不支持,仅文件)。",
            JSONObject().apply { put("source", pathP("源文件相对路径")); put("target", pathP("目标相对路径")) }, arrayOf("source", "target"))
        fn("host_stat", "查看虚拟主机内文件或目录的元信息(大小、修改时间、类型)。",
            JSONObject().apply { put("path", pathP("主机内相对路径")) }, arrayOf("path"))
        fn("host_find", "在虚拟主机内按名称关键字递归搜索文件与目录(大小写不敏感,有数量上限)。",
            JSONObject().apply { put("keyword", pathP("要匹配的名称关键字")); put("path", pathP("搜索起点相对目录;空表示根目录")) }, arrayOf("keyword"))
        fn("host_backup_list", "列出虚拟主机的站点备份(快照)列表:名称/时间/大小/文件数。只读。",
            JSONObject(), noReq)
        fn("host_backup_create", "为虚拟主机当前站点创建一个备份快照。",
            JSONObject(), noReq)
        fn("host_backup_restore", "把某个虚拟主机备份恢复到站点(覆盖现有内容,不可撤销)。属删改型,会进入手动审批。",
            JSONObject().apply { put("name", pathP("备份名称(先用 host_backup_list 取得)")) }, arrayOf("name"))
        fn("extract_archive", "解压工作区内一个 ZIP 压缩包到指定目录。支持相对路径;target 为空时解压到压缩包同级目录下、以压缩包同名的文件夹中(如 data.zip → data/)。自动跳过 zip slip 路径穿越攻击(条目中含 .. 或绝对路径的会被拒绝)。",
            JSONObject().apply {
                put("source", pathP("ZIP 压缩包在工作区内的相对路径,如 downloads/data.zip"))
                put("target", pathP("解压目标目录相对路径(可选,空=压缩包所在目录下同名文件夹)"))
            }, arrayOf("source"))
        fn("move_dir_contents", "一次性把源目录内所有直接子项(文件与子目录)搬到目标目录。一个调用处理 N 个文件,避免逐个 move/rename。若源目录内已有与目标重名的条目,自动在名称后追加 _1、_2 直到不冲突。source 和 target 均为工作区内相对路径:\n• target 留空 = 源的上一级目录;\n• target 传 '.' 或 'root' = 工作区根目录(即把内容平铺到 ai_files 根);\n• target 传其他相对路径 = 该目录。\n返回含 moved/failed/success/message:只要 failed=0 且 moved>0 即全部成功,请据此如实汇报,不要臆造「系统降级/上限」等不存在的报错。常用于:① 解压后把文件夹内容平铺到工作区根目录(传 target='root');② 批量搬运到另一目录。",
            JSONObject().apply {
                put("source", pathP("源目录相对路径"))
                put("target", pathP("目标目录相对路径:空=上一级目录;'.'或'root'=工作区根目录;其他=相对路径"))
            }, arrayOf("source"))
        // ===== 压缩包体系 =====
        fn(TOOL_COMPRESS_ARCHIVE, "把工作区内一个目录或一组文件打成 ZIP 压缩包。sources 为相对路径数组(支持文件/目录),target 为输出 zip 相对路径(如 out/data.zip)。自动跳过 zip slip。",
            JSONObject().apply {
                put("sources", pathP("要打包的源相对路径数组,如 [\"src\", \"README.md\"]"))
                put("target", pathP("输出 ZIP 相对路径(以 .zip 结尾)"))
                put("root", pathP("可选:打包时使用的内部根目录名,空=保持原结构"))
            }, arrayOf("sources", "target"))
        fn(TOOL_ARCHIVE_LIST, "列出一个 ZIP 压缩包内的所有条目(含嵌套路径、大小、类型)。只读,不解压。",
            JSONObject().apply { put("path", pathP("ZIP 在工作区内的相对路径")) }, arrayOf("path"))
        fn(TOOL_ARCHIVE_READ, "读取 ZIP 压缩包内某个条目作为文本返回。path 为 zip 相对路径,entry 为 zip 内部相对路径(可用 archive_list 先查)。最大返回 256KB,超出截断并提示用 extract_archive 完整解压。",
            JSONObject().apply {
                put("path", pathP("ZIP 在工作区内的相对路径"))
                put("entry", pathP("zip 内部条目的相对路径,如 src/Main.kt"))
            }, arrayOf("path", "entry"))
        // ===== 进程/应用控制 =====
        fn(TOOL_KILL_PROCESS, "终止一个正在运行的进程。root 模式下直接 kill -9 强制杀掉;非 root 模式下仅对本应用进程有效。pid 可先用 process_list 取得。",
            JSONObject().apply {
                put("pid", pathP("进程 PID(数字)"))
                put("signal", pathP("可选:信号名 KILL/TERM/INT,默认 KILL"))
            }, arrayOf("pid"))
        fn(TOOL_STOP_APP, "停止一个第三方应用的前台 Activity(拉起即关,本质是调用 force-stop)。非 root 模式下仅能停止本应用自己;root 模式下可停任意应用。target 传包名(如 com.example.app)。",
            JSONObject().apply { put("target", pathP("目标应用包名")) }, arrayOf("target"))
        // ===== 上传(从工作区到 URL) =====
        fn(TOOL_UPLOAD_FILE, "把工作区内一个文件以 HTTP POST multipart/form-data 上传到指定 URL。headers 可选(JSON 字符串)。适合把工作区文件发到用户的服务器、云存储或 API 端点。",
            JSONObject().apply {
                put("path", pathP("要上传的工作区内相对文件路径"))
                put("url", pathP("目标上传 URL(http/https)"))
                put("headers", pathP("可选:自定义请求头 JSON 字符串"))
                put("field", pathP("可选:multipart 字段名,默认 file"))
            }, arrayOf("path", "url"))
        // ===== FTPS 文件传输(工作区 <-> 远程 FTP 服务器) =====
        fn(TOOL_FTP_TRANSFER, "通过 FTPS(FTP over TLS)在本地工作区与远程 FTP 服务器之间上传/下载/列举文件。action=upload 把工作区文件传到服务器;action=download 把服务器文件下到工作区;action=list 列出服务器目录。host 必填(如 ftp.example.com);port 默认 21;user/password 为登录凭据(留空则匿名);remote_path 为服务器上路径;local_path 为工作区内相对路径(上传时作源、下载时作目标);mode 默认 explicit(FTPES 显式 TLS),可传 implicit(隐式 FTPS);passive 默认 true(被动模式,大多数 NAT/防火墙环境必须用)。适合把 AI 生成的文件传到用户自己的 FTP 服务器,或从服务器取文件到工作区处理。注意:凭据会出现在本次对话上下文中。",
            JSONObject().apply {
                put("action", pathP("上传/下载/列举:upload / download / list(必填)"))
                put("host", pathP("FTP 服务器主机名或 IP,如 ftp.example.com(必填)"))
                put("port", pathP("可选:端口,默认 21"))
                put("user", pathP("可选:登录用户名,留空则匿名"))
                put("password", pathP("可选:登录密码(明文,会出现在对话上下文中)"))
                put("remote_path", pathP("服务器上的路径(上传/下载必填,list 可省略表示根目录)"))
                put("local_path", pathP("工作区内相对文件路径(上传=源文件,下载=目标落盘路径)"))
                put("mode", pathP("可选:explicit=FTPES 显式 TLS(默认) / implicit=隐式 FTPS"))
                put("passive", pathP("可选:是否被动模式,默认 true"))
            }, arrayOf("action", "host"))
        // ===== 延迟/定时执行 =====
        fn(TOOL_DELAYED_TASK, "延迟指定秒数后,在后台异步执行一段 Lua 脚本(内嵌 LuaJ 引擎,无需依赖)。task_id 可选(用于后续 task_cancel/查询状态);timeout 脚本运行超时秒数,默认 60,最多 300。重要:仅当应用进程存活时触发,应用被系统杀死后任务会丢失——root 模式下可搭配 shell_exec 用 at/cron 做真正的系统级定时。",
            JSONObject().apply {
                put("delay", pathP("延迟秒数(整数,1~86400,即最多 24 小时)"))
                put("code", pathP("要执行的 Lua 代码(内嵌 LuaJ 引擎)"))
                put("task_id", pathP("可选:任务标识符,用于后续取消/查询"))
                put("timeout", pathP("脚本运行超时秒数,默认 60,最多 300"))
            }, arrayOf("delay", "code"))
        // ===== 工作日志 =====
        fn(TOOL_WORK_LOG, "查看或清空本应用私有工作日志目录(AI 自己的操作记录,独立于系统 logcat)。mode=list 列出最近 N 条(默认 50);mode=clear 清空全部。size 字段显示日志总大小,防止爆炸。",
            JSONObject().apply {
                put("mode", pathP("list=列出日志内容(默认) / clear=清空全部日志"))
                put("limit", pathP("list 模式下最多返回条数,默认 50,最多 500"))
            }, noReq)
        // ===== 跨文件批处理 =====
        fn(TOOL_BATCH_REPLACE, "在工作区内一批文本文件中查找并替换字符串(一次调用处理 N 文件,比逐个 replace 省 10 轮)。paths 为相对路径数组;pattern/replacement 为纯字符串(非正则,避免灾难性回溯)。返回每个文件修改次数与总修改次数。",
            JSONObject().apply {
                put("paths", pathP("待处理文件的相对路径数组,如 [\"src/A.kt\", \"src/B.kt\"]"))
                put("pattern", pathP("要查找的字符串(纯文本,非正则)"))
                put("replacement", pathP("替换为的字符串"))
            }, arrayOf("paths", "pattern", "replacement"))
        // ===== 网络安全 =====
        fn(TOOL_DNS_LOOKUP, "对一个域名做 DNS 解析,返回解析到的所有 IP 地址(A/AAAA)与解析耗时。",
            JSONObject().apply { put("host", pathP("要解析的域名,如 example.com")) }, arrayOf("host"))
        fn(TOOL_PORT_SCAN, "扫描一个主机的指定端口范围,返回每个端口是否开放、常见服务名识别。ports 为空时扫描 1-1024 常用端口(受限在 1024 以内)。timeout 秒单端口超时,默认 2。",
            JSONObject().apply {
                put("host", pathP("目标主机或 IP"))
                put("ports", pathP("可选:端口数组或范围字符串,如 [22,80,443] 或 \"1-1024\""))
                put("timeout", pathP("单端口超时秒数,默认 2,最多 5"))
            }, arrayOf("host"))
        fn(TOOL_TRACEROUTE, "对一个主机做 ICMP 路由追踪,返回每一跳的 IP、耗时、域名解析与总跳数。依赖 root 权限才能发送真正的 ICMP;非 root 模式下回退到 UDP 扫描,可能被防火墙拦截。",
            JSONObject().apply {
                put("host", pathP("目标主机或 IP"))
                put("max_hops", pathP("可选:最大跳数,默认 30"))
                put("timeout", pathP("可选:单跳超时秒数,默认 2"))
            }, arrayOf("host"))
        fn(TOOL_BANDWIDTH_TEST, "通过下载一个已知大小的公开资源测算当前下行带宽(Mbps),返回下载速率、文件大小、耗时与服务端 IP。默认使用 speedtest 公共镜像。",
            JSONObject().apply {
                put("size_mb", pathP("可选:用于测速的资源大小 MB,默认 10,最多 100"))
                put("url", pathP("可选:自定义测速资源 URL,空则用内置默认"))
            }, noReq)
        fn(TOOL_SEND_NOTIFICATION, "在系统通知栏发送一条提醒通知,用户点开可以跳转或执行预设 action(当前 action 仅支持 open_app 打开本应用)。title 必填,body 可选。系统会自动请求 POST_NOTIFICATIONS 权限(Android 13+)。",
            JSONObject().apply {
                put("title", pathP("通知标题(必填)"))
                put("body", pathP("通知正文(可选)"))
                put("action", pathP("可选:点击通知执行的动作,当前支持 open_app"))
            }, arrayOf("title"))
        // ===== Diff/补丁/二维码/加解密 =====
        fn(TOOL_APPLY_PATCH, "把一个 unified diff 补丁文本应用到工作区内的一个目标文件(或 files 数组批量应用)。strict=true 时补丁必须精确匹配上下文,strict=false 时自动降级到最后一行匹配。返回每个文件应用的 hunk 数与失败原因。",
            JSONObject().apply {
                put("files", pathP("补丁要应用到的目标相对路径数组,如 [\"src/A.kt\"]"))
                put("patch", pathP("unified diff 补丁完整文本(必须以 --- / +++ 开头)"))
                put("strict", pathP("是否严格模式,默认 true"))
            }, arrayOf("files", "patch"))
        fn(TOOL_QR_DECODE, "识别工作区内一张二维码图片文件,解码返回原始内容文本(URL / 文本 / vCard 等)。失败返回 error 字段。",
            JSONObject().apply { put("path", pathP("图片在工作区内的相对路径,如 images/qr.png")) }, arrayOf("path"))
        fn(TOOL_CRYPTO_ENCRYPT, "用 AES 256-CBC 模式加密工作区内一个文件或一段纯文本。key 为 32 字节密钥(可传任意长度字符串,内部 SHA-256 取前 32 字节)。输出为 base64 密文或新文件(传 dest 时落盘)。",
            JSONObject().apply {
                put("path", pathP("要加密的工作区内相对文件路径(与 content 二选一)"))
                put("content", pathP("要加密的纯文本字符串(与 path 二选一)"))
                put("key", pathP("加密密钥(字符串,任意长度,内部 SHA-256 标准化)"))
                put("dest", pathP("可选:加密后落盘的目标相对路径;不传则返回 base64 密文"))
            }, arrayOf("key"))
        fn(TOOL_CRYPTO_DECRYPT, "用 AES 256-CBC 模式解密一个 base64 密文或文件。key 加密时相同。path/content 二选一。传 dest 时解密落盘,不传则返回明文。",
            JSONObject().apply {
                put("path", pathP("要解密的工作区内密文文件路径(与 content 二选一)"))
                put("content", pathP("要解密的 base64 密文字符串(与 path 二选一)"))
                put("key", pathP("解密密钥,与加密时相同"))
                put("dest", pathP("可选:解密后落盘的目标相对路径;不传则返回明文"))
            }, arrayOf("key"))
        // ===== 备份工具(只读 + 恢复审批)=====
        fn(TOOL_LIST_BACKUPS, "列出工作区之外所有备份的基础信息(只读):备份目录名/创建时间/范围(整个工作区或某文件夹)/大小/文件数。AI 只能读取这些元信息,不能修改备份。需要查看备份内具体文件内容时用 read_backup_file。",
            JSONObject(), noReq)
        fn(TOOL_READ_BACKUP, "读取某个备份内的一个文本文件内容(只读)。路径严格限定在该备份目录内,禁止绝对路径或 .. 上跳,AI 借此查看历史内容但不能改动备份。",
            JSONObject().apply {
                put("backup", pathP("备份目录名,如 backup_1690000000000(先用 list_backups 取得)"))
                put("path", pathP("备份内的相对文件路径,如 src/MainActivity.kt"))
            }, arrayOf("backup", "path"))
        fn(TOOL_RESTORE_BACKUP, "请求把某个备份恢复到工作区。重要:此工具【不会立刻执行】,它会弹出【红字警告】让用户手动批准;无论当前是手动/完全访问/root 模式,都必须用户批准才恢复,用户拒绝则什么都不改。恢复只把备份内容写回工作区(侧面改动工作区),备份本身绝不被 AI 触碰。",
            JSONObject().apply {
                put("backup", pathP("要恢复的备份目录名,如 backup_1690000000000(先用 list_backups 取得)"))
            }, arrayOf("backup"))
        fn("query_environment", "查询用户设备运行环境信息:型号、厂商、Android 版本、存储、应用版本、root 状态、当前访问模式等,用于了解用户实际运行环境。",
            JSONObject(), noReq)
        fn("shutdown_device", "重启/关机电源设备操作:shutdown_device 执行关机。仅在设备具备 root 权限时才能生效;若当前无 root,则调用会失败,请如实告知用户并在无 root 时不做此类操作。调用前应先通过 query_environment 确认 root 状态。",
            JSONObject(), noReq)
        fn("reboot_device", "重启/关机电源设备操作:reboot_device 执行重启。仅在设备具备 root 权限时才能生效;若当前无 root,则调用会失败,请如实告知用户并在无 root 时不做此类操作。调用前应先通过 query_environment 确认 root 状态。",
            JSONObject(), noReq)
        fn(TOOL_GET_FILE, "取出工作区内的一个文件并发送给用户(不要再用纯文本复制文件内容)。path 为工作区内相对文件路径。默认按文件类型自动呈现:图片(.jpg/.png/.gif/.webp 等)显示为**图片消息气泡**,用户可直接看到并点开大图;视频显示为视频消息;其它文件显示为可下载/可打开的文件卡片。**若用户明确要求「以文件形式发我」「发文件」「发压缩包」「不要图片气泡,给我文件卡片」,必须传 as_file=true**,这样即使是图片也会以文件卡片形式发送。",
            JSONObject().apply {
                put("path", pathP("工作区内相对文件路径"))
                put("as_file", pathP("true=强制以文件卡片发送(即使内容是图片);默认 false,按类型自动呈现"))
            }, pathReq)
        fn(TOOL_GENERATE_MEDIA, "根据用户描述生成图片或视频,并把生成结果作为图片/视频消息直接发送给用户。调用时机:用户明确想要生成/画/制作一张图片或一段视频(如 \"画一只猫\"\"生成一张海报\"\"做一个星空视频\")。prompt 参数默认直接使用用户的原始表述;仅在以下情况才需要先把用户的表述优化成更严谨的提示词再传入:(1) 用户明确要求\"优化提示词/润色描述/帮我写得更好\"后再生成;(2) 用户描述过于简略、缺少主体/风格/细节等关键信息,直接生成效果会很差;(3) 用户明确表达想要某种效果但给的描述不够严谨。除此之外一律把用户原话原样作为 prompt 传入,不要擅自改写。",
            JSONObject().apply {
                put("type", pathP("生成类型:image=图片 / video=视频"))
                put("prompt", pathP("生成内容的提示词(默认直接用用户原话;按上面规则需要优化时才传入优化后的提示词)"))
                put("size", pathP("图片尺寸(可选,仅 image 使用,如 1024x1024 / 768x1024 / 1024x768;不传用默认)"))
                put("keep", pathP("是否额外把生成结果保存到 AI 工作区(可选,true/false,默认 false)。true 时图片/视频会落盘到工作区 ai_files,返回其中的相对路径,你之后可用 list_files/get_file/edit_file 等工具继续处理它(例如裁剪、转格式、再分析)。仅在用户明确想保留或存起来继续用时,或你自己下一步要操作该文件时才传 true。"))
            }, arrayOf("type", "prompt"))
        fn(TOOL_EXECUTE_PYTHON, "在用户设备上执行一段 Python 代码。仅当设备已安装 Python 解释器(如 Termux 中的 python、root 环境下系统自带 python)时才会真正执行;未检测到 Python 环境会返回失败,此时请如实告知用户设备未安装 Python,并引导其安装(如 Termux 安装 python)。执行工作目录为应用工作区 ai_files(root 模式下也支持在代码中写入真实绝对路径);代码中创建/修改文件会落在工作区内,可用文件类工具继续读取。超时 30 秒。",
            JSONObject().apply {
                put("code", pathP("要执行的 Python 源代码(完整代码,可多行)"))
            }, arrayOf("code"))
        fn(TOOL_EXECUTE_LUA, "在用户设备上执行一段 Lua 代码。基于应用内嵌的 LuaJ 引擎,无需额外安装解释器,开箱即用。执行工作目录为应用工作区 ai_files,代码中创建/修改文件会落在工作区内,可用文件类工具继续读取。print() 输出会作为 stdout 返回。超时 30 秒。",
            JSONObject().apply {
                put("code", pathP("要执行的 Lua 源代码(完整代码,可多行)"))
                put("path", pathP("可选:工作区内要作为脚本入口执行的 .lua 文件相对路径;传 path 时忽略 code"))
            }, arrayOf("code"))
        // ===== 专业开发者工具(仅开发场景使用)=====
        fn(TOOL_FETCH_URL, "抓取网页:GET 给定 URL,剥离脚本/样式/标签噪声后提取正文,返回可读纯文本(必要时转换成 Markdown 标题/列表),并把完整结果落盘到工作区(文件名自动取自 URL + 时间戳)。适合:阅读文档/文章、抓取 API 返回的 HTML 页面、获取公开网页内容用于分析或二次处理。仅用于用户明确要抓取的公开 URL;不要对隐私/登录态页面使用。",
            JSONObject().apply {
                put("url", pathP("要抓取的网页地址(http/https 完整 URL)"))
                put("timeout", pathP("超时秒数(可选,默认 20,最多 60)"))
                put("keep_markdown", pathP("是否保留 Markdown 结构(可选,true/false;默认 true)。false 则只返回纯文本正文"))
            }, arrayOf("url"))
        fn(TOOL_SHELL_EXEC, "执行 Shell 命令(开发者工具):在用户设备上运行一条 shell 命令,返回 stdout/stderr/退出码。root 访问模式下直接以 su 执行(可操作系统级命令);非 root 模式仅允许只读/工作区类命令(如 ls/cat/pwd/find/echo 等),写入与系统级命令会被拒绝。超时 30 秒。不要用它做未经用户确认的破坏性操作。",
            JSONObject().apply {
                put("command", pathP("要执行的 shell 命令行(如 \"ls -la\" / \"uname -a\")"))
                put("timeout", pathP("超时秒数(可选,默认 30,最多 120)"))
            }, arrayOf("command"))
        fn(TOOL_RUN_ADB, "执行 ADB 命令(开发者调试工具):通过 adb 运行一条 Android 调试桥命令(如 shell/install/push/pull/devices/getprop 等)。适合:帮开发者在电脑端调试设备、安装 APK、拉取文件、查询设备属性。依赖设备已开启调试且 adb 可执行;执行目录为应用工作区 ai_files。超时 60 秒。",
            JSONObject().apply {
                put("args", pathP("adb 的参数部分(不含 adb 前缀),如 \"devices\" / \"shell pm list packages\" / \"-s install xxx.apk\""))
                put("timeout", pathP("超时秒数(可选,默认 60,最多 180)"))
            }, arrayOf("args"))
        // ===== 专业开发者工具(第二批)=====
        fn(TOOL_HTTP_REQUEST, "发起任意 HTTP 请求(开发者工具):method/get/post/put/patch/delete/head,可带 headers 与 body(JSON 字符串),返回状态码、响应头与响应体(正文最多返回 8000 字符,超出截断;二进制响应会提示长度并建议用 fetch_webpage/get_file 等)。适合:调试自有 API、调用 REST 接口、查看返回结构。不要用它访问用户隐私/登录态页面或做恶意请求。",
            JSONObject().apply {
                put("url", pathP("目标地址(http/https 完整 URL)"))
                put("method", pathP("请求方法(默认 GET),可选 GET/POST/PUT/PATCH/DELETE/HEAD"))
                put("headers", pathP("请求头(可选),JSON 对象字符串,如 {\"Authorization\":\"Bearer x\"}"))
                put("body", pathP("请求体(可选),通常是 JSON 字符串;GET/HEAD 一般留空"))
                put("timeout", pathP("超时秒数(可选,默认 20,最多 60)"))
            }, arrayOf("url"))
        fn(TOOL_PROCESS_LIST, "列出当前设备上正在运行的进程(开发者调试工具):返回 pid / 进程名 / 用户名(尽力)。root 模式信息更全;非 root 模式只能看到当前应用自身及部分可见进程。适合:排查卡顿、确认某进程是否在跑、定位要 kill 的 pid。",
            JSONObject().apply {
                put("limit", pathP("最多返回条数(可选,默认 50,最多 200)"))
            }, arrayOf("limit"))
        fn(TOOL_PING, "Ping 一个主机判断网络连通性(开发者工具):ICMP ping(优先)或回退到 TCP 端口探测(主机不可 ICMP 时)。返回是否可达、往返耗时(多次取平均)、丢包情况。适合:确认服务器/设备是否在线、诊断网络。",
            JSONObject().apply {
                put("host", pathP("要探测的主机名或 IP(如 8.8.8.8 / example.com)"))
                put("count", pathP("发送次数(可选,默认 4,最多 10)"))
                put("port", pathP("TCP 探测端口(可选,仅当需要探测某端口连通性时填,如 443)"))
            }, arrayOf("host"))
        fn(TOOL_SEARCH_WEB, "联网搜索(开发者调研工具):双引擎回退,首选 Bing RSS(国内直连可达),失败自动回退 DuckDuckGo。返回若干条结果(标题 + 链接 + 摘要),并附带抓取到的首个页面正文片段。适合:查文档、查报错、调研技术方案。仅用于公开信息检索。若两源均失败,通常只是境外搜索源被网络环境阻断,不代表设备无法联网,可改用 fetch_webpage 抓具体网址。",
            JSONObject().apply {
                put("query", pathP("搜索关键词(如 \"kotlin okhttp timeout example\")"))
                put("max", pathP("返回结果条数(可选,默认 5,最多 10)"))
            }, arrayOf("query"))
        fn(TOOL_GIT, "执行 Git 命令(开发者工具):在工作区 ai_files 目录(或其子目录,若已 git init)下运行一条 git 命令,如 status / log / diff / add / commit / clone / pull / push / branch。适合:让 AI 帮你用 git 管理工作区里的代码与文档版本。注意 push/pull 需要远端与凭据已配置。",
            JSONObject().apply {
                put("args", pathP("git 参数(不含 git 前缀),如 \"status\" / \"log --oneline -5\" / \"add -A\" / \"commit -m 更新\" / \"clone <url> repo\""))
                put("timeout", pathP("超时秒数(可选,默认 60,最多 180)"))
            }, arrayOf("args"))
        fn(TOOL_DIFF, "对比两段文本的差异(开发者工具):给定 a 与 b 两段文本,返回逐行差异(统一 diff 格式),用于让 AI 展示「修改前 vs 修改后」的区别,或核对两版代码/配置的不同。",
            JSONObject().apply {
                put("a", pathP("原始文本(修改前)"))
                put("b", pathP("对比文本(修改后)"))
                put("context", pathP("上下文行数(可选,默认 3)"))
            }, arrayOf("a", "b"))
        fn(TOOL_DOWNLOAD_FILE, "下载一个网页文件(任意类型,含 APK 安装包)到本地。适合:用户想获取某个链接指向的文件/安装包并保存到工作区或指定目录。url 为文件直链;未指定 dest 时落到工作区 ai_files(根模式可写绝对路径);dest 为相对工作区的路径或文件名(如 app.apk / downloads/x.apk),已存在则覆盖。可选项:headers(自定义请求头 JSON)、timeout(秒,默认60,最多300)、overwrite(是否覆盖,默认true)。返回保存到的绝对路径与大小。",
            JSONObject().apply {
                put("url", pathP("要下载的文件直链(如 https://example.com/app.apk)"))
                put("dest", pathP("保存位置(可选):相对工作区 ai_files 的路径或文件名;留空则存到工作区根并使用 url 末段文件名"))
                put("headers", pathP("请求头(可选),JSON 对象字符串,如 {\"Authorization\":\"Bearer x\"}"))
                put("timeout", pathP("超时秒数(可选,默认 60,最多 300)"))
                put("overwrite", pathP("已存在时是否覆盖(可选,默认 true)"))
            }, arrayOf("url"))
        fn(TOOL_INSTALL_APK, "安装一个 APK 安装包:拉起系统安装器让用户确认安装。适合:已下载好 APK、或用户给了一个本地 APK 路径,需要真正装到设备上。path 支持两种写法:(1) AI 工作区相对路径(如 app.apk / downloads/x.apk);(2) 设备真实绝对路径(如 /sdcard/Download/app.apk、/data/.../xxx.apk),root 模式与沙盒模式下均支持绝对路径。该方法只负责拉起安装界面,安装成功与否由用户在系统安装器中决定;返回是否成功拉起安装器。",
            JSONObject().apply {
                put("path", pathP("APK 路径:工作区相对路径(如 app.apk)或设备绝对路径(如 /sdcard/Download/app.apk)"))
            }, arrayOf("path"))
        // ===== App 内部业务工具(AI 操作本 App 自身功能) =====
        fn("switch_tab", "切换主界面底部板块到指定页(tab):chat=聊天 / contacts=联系人 / community=社区 / profile=我的。tab 支持中文写法:聊天/消息=chat、联系人/好友=contacts、社区/广场/动态=community、我的/个人=profile。注意:无论用户怎么说,最终都映射到这 4 个标准值之一。",
            JSONObject().apply { put("tab", pathP("目标板块:chat / contacts / community / profile,亦可用中文 聊天/联系人/社区/我的")) }, arrayOf("tab"))
        fn("send_message", "以当前用户身份给某个好友(私聊)或某个群(群聊)发送一条消息。target_type=friend 表示发给好友,target_id 为其用户 ID,走私聊;target_type=group 表示发给群,target_id 为群会话 ID(负数群 convID,官方群 -1001),走群聊。返回结果带 message_id,发送后发现内容有问题需要在 2 分钟内撤回时,把它传给 recall_message。",
            JSONObject().apply {
                put("target_type", pathP("friend(私聊给好友) / group(群聊)"))
                put("target_id", pathP("目标用户 ID(私聊) 或 群会话 ID(群聊,负数)"))
                put("content", pathP("要发送的消息内容"))
            }, arrayOf("target_type", "target_id", "content"))
        fn(TOOL_LIST_RECENT_MESSAGES, "查看某个会话的最近消息列表(每条含 message_id/发送者/内容/时间),主要用途:拿到消息 ID。用户说「撤回我刚才那条消息」「帮我把那条撤了」但没人知道 message_id 时,先用本工具查目标会话的最近消息,从内容里定位到目标消息拿到它的 message_id,再传给 recall_message;也可用于了解某会话最近聊了什么。参数:target_type=friend(私聊,查我和 target_id 好友的对话)/group(群聊,target_id 为群会话 ID 负数,如官方群 -1001);count=要看的条数(默认 20,最大 50,从最新往前取);keyword=可选过滤词,只返回包含该词的消息。",
            JSONObject().apply {
                put("target_type", pathP("friend(私聊) / group(群聊)"))
                put("target_id", pathP("好友用户 ID(私聊) 或 群会话 ID(群聊,负数,如官方群 -1001)"))
                put("count", pathP("可选:返回条数,默认 20,最大 50"))
                put("keyword", pathP("可选:只返回内容包含该关键词的消息"))
            }, arrayOf("target_type", "target_id"))
        fn(TOOL_RECALL_MESSAGE, "撤回一条已发送的消息(私聊/群聊均可)。message_id 来源:send_message 返回结果里的 message_id、list_recent_messages 查到的消息 ID、或用户明确给出的消息 ID。用户说「撤回刚发的那条」但没给 ID 时,先用 list_recent_messages 查最近消息定位目标,不要凭空猜 ID。权限规则(服务端强制):开发者(ID=1)可撤回任何人的消息且不限时间;普通用户只能撤回【自己发的】消息,且必须在发出后 2 分钟内——超过 2 分钟会撤回失败,此时如实告诉用户「超过两分钟了,无法撤回」,不要重试也不要谎报撤回成功。撤回后接收方会看到「XX 撤回了一条消息」。",
            JSONObject().apply {
                put("message_id", pathP("要撤回的消息 ID(send_message 返回 / list_recent_messages 查到 / 用户提供)"))
            }, arrayOf("message_id"))
        fn("send_friend_request", "按用户 ID 给某人发送好友申请(后端按该用户注册邮箱解析)。",
            JSONObject().apply {
                put("user_id", pathP("目标用户 ID(正整数)"))
                put("greeting", pathP("附言/打招呼语,可省略"))
            }, arrayOf("user_id"))
        fn("create_community_post", "以当前用户身份在社区发布一条帖子。",
            JSONObject().apply {
                put("title", pathP("帖子标题"))
                put("content", pathP("帖子正文内容"))
                put("post_type", pathP("帖子类型标签,可省略"))
            }, arrayOf("title", "content"))
        fn("list_my_contacts", "列出当前用户的全部好友(返回好友数量、每个好友的 ID/用户名/签名),便于按用户名定位目标好友。无参数。", JSONObject(), emptyArray())
        fn("list_my_groups", "列出当前用户加入的全部群聊(返回群会话 ID/群名/最近消息预览),群会话 ID 为负数。无参数。", JSONObject(), emptyArray())
        // ===== 批量提效工具 =====
        fn(TOOL_BATCH_LOOP, "批量循环执行工具(提效专用):当任务需要【重复调用同一个工具很多次】时(如批量创建 100 个文件、给 30 个好友逐个发消息、批量重命名/复制一批文件),绝不要一次一次手动调用——用本工具一次调用完成整个循环,只占用 1 个工具轮次。参数:tool=要重复调用的工具名;args=该工具的参数模板(JSON 对象,任意字符串值里可写 {i} 占位符,每次执行时替换为当前循环序号,支持 {i:03d} 补零写法);count=重复次数(1~5000,超过上万次的超大批量请改用 shell_exec 写 for 循环);start=起始序号(可选,默认 1);interval_ms=两次执行间隔毫秒(可选,默认 0,最高 10000;发消息类建议 200 以上防刷屏);stop_on_error=首次失败即停(可选,默认 false;连续失败 5 次会自动中止防拖死)。返回逐条结果明细与成功/失败统计。",
            JSONObject().apply {
                put("tool", pathP("要重复调用的工具名,如 write_file / create_folder / send_message / copy_file"))
                put("args", pathP("内层工具参数模板,JSON 对象。字符串值中的 {i} 替换为当前序号,如 {\"path\":\"notes/note_{i}.txt\",\"content\":\"第 {i} 条笔记\"}"))
                put("count", pathP("重复次数,1~5000"))
                put("start", pathP("可选:起始序号,默认 1"))
                put("interval_ms", pathP("可选:两次执行之间的间隔毫秒,默认 0,最高 10000"))
                put("stop_on_error", pathP("可选:true=首次失败立即中止;默认 false"))
            }, arrayOf("tool", "args", "count"))
        // ===== 开发调试类工具(AI 本机处理,无需联网/权限/UI,适合开发者与运维) =====
        fn("json_process", "JSON 数据处理:校验并美化打印/压缩/按路径取值。input 为 JSON 文本;action=beautify 美化 / minify 压缩 / validate 仅校验 / get(path) 按点分路径取值(如 data.list.0.name)。",
            JSONObject().apply {
                put("input", pathP("JSON 文本(必填)"))
                put("action", pathP("beautify / minify / validate / get"))
                put("path", pathP("仅 action=get 时使用:点分路径,如 data.list.0.name"))
            }, arrayOf("input"))
        fn("regex_test", "用正则表达式测试文本:返回是否匹配、匹配到的全部片段及捕获组。pattern 为正则,text 为待测文本,flags 可选(i 忽略大小写 / m 多行 / s 点匹配换行,可组合如 im)。",
            JSONObject().apply {
                put("pattern", pathP("正则表达式"))
                put("text", pathP("待测文本"))
                put("flags", pathP("可选:i/m/s"))
            }, arrayOf("pattern", "text"))
        fn("encode_convert", "编码 / 解码转换:base64_encode / base64_decode / hex_encode / hex_decode / url_encode / url_decode。text 为待处理内容,查看大文本时自动截断展示。",
            JSONObject().apply {
                put("action", pathP("base64_encode / base64_decode / hex_encode / hex_decode / url_encode / url_decode"))
                put("text", pathP("待转换内容"))
            }, arrayOf("action", "text"))
        fn("hash_digest", "计算消息摘要(hash):md5 / sha1 / sha256,输入任意文本返回对应十六进制摘要。常用于文件/消息完整性校验。",
            JSONObject().apply {
                put("algorithm", pathP("md5 / sha1 / sha256"))
                put("text", pathP("待摘要的内容"))
            }, arrayOf("algorithm", "text"))
        fn("timestamp_convert", "Unix 时间戳与日期互转:ts_to_date 将秒级/毫秒级时间戳转成可读日期;date_to_ts 将日期字符串(可用 format 指定格式,默认 yyyy-MM-dd HH:mm:ss)转成时间戳。",
            JSONObject().apply {
                put("direction", pathP("ts_to_date / date_to_ts"))
                put("value", pathP("时间戳(数字) 或 日期字符串"))
                put("format", pathP("可选:日期格式,默认 yyyy-MM-dd HH:mm:ss"))
            }, arrayOf("direction", "value"))
        fn("text_stats", "文本统计分析:返回字符数、单词数、行数、字节数(按 UTF-8)、是否含多行等,便于快速了解一份文本/日志的规模。",
            JSONObject().apply { put("text", pathP("待统计的文本")) }, arrayOf("text"))
        fn("size_convert", "字节数与人眼可读大小互转:bytes_to_human(1024 -> '1.00 KB') / human_to_bytes(\"5MB\" -> 字节数)。适合解析文件大小。",
            JSONObject().apply {
                put("direction", pathP("bytes_to_human / human_to_bytes"))
                put("value", pathP("字节数字符串 或 可读大小字符串(如 5MB/1.5GB)"))
            }, arrayOf("direction", "value"))
        fn("take_screenshot", "对当前屏幕截屏:用 screencap(root/adb shell)截取整屏保存成 PNG 到 AI 工作区,返回工作区相对路径与文件大小。需要 root(或 adb shell)权限;非 root 下会尝试 screencap 并给出明确结果。name 可选自定义文件名(不含扩展名,默认 screenshot_时间戳)。注意:本工具只保存到工作区,不会发给用户;要把画面直接发给用户请用 capture_screen。",
            JSONObject().apply { put("name", pathP("可选:截图文件名(不含 .png)")) }, arrayOf("name"))
        fn(TOOL_CAPTURE_SCREEN, "捕捉当前屏幕内容并交付给用户(推荐用于「把屏幕截下来发我」「看看现在这个页面」「截个图给我」这类需求)。deliver 决定交付形式:deliver=image(默认)——把截到的画面**强制作为图片消息直接发给用户**,用户立刻能在聊天里看到并点开大图(与生成图片完全同一条渲染链路);deliver=file——作为**文件卡片**发给用户(适合画面很长、想看原图细节或需要下载原文件)。keep=true 时额外在工作区留一份副本供后续 list_files / get_file 处理(默认 false,不需要留档)。截屏走无障碍服务(Android 11+ 免 root),失败时自动尝试 screencap(root/adb)。name 可选文件名(不含扩展名);quality 为 JPEG 画质(1-100,默认 85,仅影响发图体积)。注意:支付/密码等敏感页面系统会阻止截屏,此时会返回失败,请如实告知用户。",
            JSONObject().apply {
                put("deliver", pathP("交付形式:image(默认,作为图片消息发给用户) / file(作为文件卡片发给用户)"))
                put("keep", pathP("true 时额外在工作区保留原图副本(默认 false,交付后不留档)"))
                put("name", pathP("可选:文件名(不含扩展名,默认 screenshot_时间戳)"))
                put("quality", pathP("可选:JPEG 画质 1-100,默认 85"))
            }, emptyArray())
        // ===== 应用启动/退出类工具 =====
        fn("open_app", "打开(跳转到)设备上已安装的其它应用。target 支持两种写法:(1) 应用包名,如 com.tencent.mm;(2) 应用名称(中文或英文均可),如 微信 / Chrome。用户只说了应用名时直接传名称即可;若系统里匹配到多个同名应用,工具会返回候选包名,你再让用户确认。适合:用户说「帮我打开微信」「跳转到 XX 应用」时调用。",
            JSONObject().apply { put("target", pathP("要打开的应用包名或应用名称,如 com.tencent.mm 或 微信")) }, arrayOf("target"))
        fn("exit_app", "退出当前应用(Aurora Chat)。两种方式由 mode 决定:mode=background(默认)——只把应用退到后台(等价于按 Home 键),进程保留,消息推送、TCP 长连接、正在跑的后台任务都不受影响,适合用户说「先退出去」「回到桌面」「我一会儿回来」「别关,先放后台」;mode=kill——彻底结束应用进程(通知消失、连接断开),适合用户明确说「彻底关掉」「退出软件」「杀掉进程」「别在后台留着」。默认一律走 background,只有用户明确要求彻底关闭时才用 kill。调用后请先给用户一句简短告别语。",
            JSONObject().apply {
                put("mode", pathP("background(默认,仅退到后台,进程保留) / kill(彻底结束进程)"))
            }, emptyArray())
        fn(TOOL_UNINSTALL_APP, "卸载手机上的应用——**可以是任意应用,不限于本应用**。注意这不是退出,而是把该应用从手机上删除,其数据全部消失且不可恢复。**必须用 target 明确指定要卸载谁**:应用名(如 微信、抖音、Chrome)或包名(如 com.tencent.mm);只有当用户明确要卸载 Aurora Chat 本身时才传 target=自己。**target 缺失会直接报错,绝不会默认卸载本应用——所以「卸载微信」这类请求,一定要把 target 填成「微信」,不要留空。** 两种方式:mode=system(默认)——唤起系统卸载器,弹出「要卸载此应用吗?」由用户自己点确定,最稳妥、兼容所有机型,用户没特别说明时一律用它;mode=auto——全程无障碍自动完成,用户完全不用动手(第三方应用走「系统卸载器 + 自动点确定」,本应用走「回桌面→长按图标→卸载→确定」),仅当用户明确说「你自己删掉,别让我动手」「用无障碍全自动卸载」时才使用。**注意:mode=auto 需要无障碍服务已开启且已连接;无障碍手势对系统卸载确认框是生效的,若自动点击后校验发现目标包仍未消失,工具会明确返回失败原因,你必须如实转述失败原因(如无障碍未连接、用户取消了确认框),绝不能谎称已卸载成功。** 调用前必须先向用户确认一次(除非用户刚已明确下达卸载指令),并提醒卸载会清除该应用的全部数据。若不确定目标应用的准确名称或包名,先问清楚,不要凭猜测填 target。",
            JSONObject().apply {
                put("target", pathP("要卸载的应用:应用名(如 微信)或包名(如 com.tencent.mm);卸载本应用本身请传「自己」"))
                put("mode", pathP("system(默认,唤起系统卸载器让用户自己确认) / auto(无障碍全程自动,用户不介入)"))
                put("force", pathP("可选:true 时允许静默卸载系统预装应用,默认 false(危险,仅当用户明确要求卸载系统应用时才用)"))
            }, arrayOf("target"))
        // ===== 多 Agent 模式:规划者 + 并行派发 + 独立验收者(仅多 Agent 模式声明,普通模式不可见) =====
        if (includeMultiAgent) {
        fn(TOOL_AGENT_PLAN, "【多 Agent 模式·规划者】把一个较复杂或含多步操作的任务交给独立规划 Agent 拆解,返回结构化执行计划(步骤清单 + 风险点)。适用:3 步及以上的任务、要创建/修改多个文件的任务、你不确定从何下手的任务。调用后按计划逐步执行,仍需在完成后调用 agent_review 验收。简单的单步任务(如新建 1 个文件、纯问答)无需规划,直接干。",
            JSONObject().apply {
                put("task", pathP("用户的原始任务描述(原话或忠实转述)"))
            }, arrayOf("task"))
        fn(TOOL_AGENT_FORK, "【多 Agent 模式·并行派发】把互不依赖的子任务并行派发给多个独立执行 Agent 同时干(多协程并发,最多 4 个)。何时派发:(1) 任务可自然拆成互不依赖的几部分且并行明显更快(如「同时建 3 个不同的文件」);(2) 用户明令要求并行/同时/一起干。串行依赖的任务(后一步要用前一步的结果)禁止派发。tasks 每项必须写清:该子任务做什么 + 只允许操作哪些路径(各子任务的路径范围不得重叠,防止并行中互相覆盖)。全部完成后返回各子任务结果汇总,个别失败要自行补做或如实告知;之后你仍必须调用 agent_review 验收整体成果。",
            JSONObject().apply {
                put("tasks", JSONObject()
                    .put("type", "array")
                    .put("description", "子任务描述数组,每项格式:「做什么 + 只允许操作的路径范围」")
                    .put("items", JSONObject().put("type", "string")))
            }, arrayOf("tasks"))
        fn(TOOL_AGENT_REVIEW, "启动一个【完全独立上下文】的验收 Agent,对你的工作成果做独立核验(多 Agent 模式专用)。验收者只拥有只读权限,会亲自读取文件/检索来源核验,绝不轻信你的汇报。参数:task=用户的原始任务描述;summary=你的工作汇报(做了什么、改了哪些文件、关键操作与结果)。返回 JSON:verdict=PASS(通过)/FAIL(不通过),FAIL 时 detail 里是逐条可执行的修复清单。收到 FAIL 必须修复后再次调用本工具复验(同一任务最多复验 2 次);收到 PASS 后才能给用户交付最终总结。",
            JSONObject().apply {
                put("task", pathP("用户的原始任务描述(原话或忠实转述)"))
                put("summary", pathP("你的工作汇报:做了什么、修改/创建了哪些文件(含路径)、关键操作与结果"))
            }, arrayOf("task", "summary"))
        }
        // ===== 向用户提问(结构化问答面板) =====
        fn(TOOL_ASK_USER, "向用户提问:以全屏面板逐题展示问题卡片,用户点选后你拿到结构化答案。何时必须用:请求含糊、关键信息缺失,导致你只能靠猜来动手时——猜错了就得整个返工,先问一句更划算。典型场景:用户说「新建一个文件」却没说名称/内容/目录;说「删除文件」「整理一下」「改一下」却没说具体对象(删除、覆盖这类破坏性操作必须先确认对象,绝不能猜一个就删);说「发个消息」「发给他」却没说发给谁、发什么;说「打开那个应用」「用某个工具」却没说是哪个;任务范围说不清(如「把文件都清理掉」)。以及:接下来的操作依赖用户的偏好/选择且无法从上下文推断时(如「要哪种风格」「覆盖还是另存」「现在执行还是稍后」)。提问要点:把关键项一次问全,不要挤牙膏式一轮问一点;选项给能直接点选的具体值(如具体文件名、目录、应用名),并把你的推荐值放第一个;能合并的问题合并成一题。反过来,以下一律不要调用:你自己能合理决定的事(文件格式、默认命名、显而易见的目录)、上一轮用户已经说清楚的事、能从当前上下文推断的事(如刚讨论过那个文件)、纯闲聊,以及「要不要我继续」这类没营养的问题。参数 questions 是问题数组,一次可问多个相关小问题(最多 5 个),每个问题:question=问题文本(一句话说清在问什么);options=选项列表(2-6 个,文字要具体自明,不要只写「A/B/C」,用户会直接点选);multi=true 时允许多选(默认单选);other=true 时附加「其他」自由填写项(默认无)。不提供 options 的问题会以自由文本输入呈现。用户可以逐题「上一步/下一步/跳过」,最后一题点「完成」。返回 JSON:answers 数组,每项含 index/question/skipped/selected/other/answer;skipped=true 表示该题用户跳过未答,跳过的题不要重复追问,按合理默认值继续。",
            JSONObject().apply {
                put("questions", JSONObject()
                    .put("type", "array")
                    .put("description", "问题数组,每项一个独立问题;一次最多 5 个,只问真正需要用户拍板的")
                    .put("maxItems", 5)
                    .put("items", JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject()
                            .put("question", JSONObject().put("type", "string").put("description", "问题文本,一句话说清在问什么"))
                            .put("options", JSONObject()
                                .put("type", "array")
                                .put("description", "选项列表(2-6 个),文字要具体自明;省略则该题为自由文本填写")
                                .put("items", JSONObject().put("type", "string")))
                            .put("multi", JSONObject().put("type", "boolean").put("description", "true=允许多选,默认单选"))
                            .put("other", JSONObject().put("type", "boolean").put("description", "true=附加「其他」自由填写项,默认 false"))
                        )
                        .put("required", JSONArray().put("question"))
                    ))
            }, arrayOf("questions"))
        // ===== 插件查看与调用:市场已上架的 + 用户自建的全部插件(自建无需审核即可自用) =====
        fn(TOOL_PLUGIN_LIST, "查看当前可用的插件(内置能力,随时直接调用,无需安装也无需先创建任何插件):返回「插件市场已上架的 + 当前用户自建的全部插件」(自建插件无需审核,自己随时可用)。每个插件含 id/名称/描述/作者/状态/参数清单。传 keyword 按名称或描述过滤;传 detail=插件名 可查看该插件的完整 JSON 定义(声明式 http_tool:type/method/url/responsePath/params)。用户问「我有哪些插件」「有没有查 XX 的插件」时直接调本工具;创作新插件前也先用它确认没有重名插件。",
            JSONObject().apply {
                put("keyword", pathP("可选:按插件名称/描述过滤的关键词"))
                put("detail", pathP("可选:传入插件名,额外返回该插件的完整 JSON 定义"))
            }, emptyArray())
        fn(TOOL_PLUGIN_CALL, "调用一个插件执行查询。插件=声明式 HTTP 工具(预定义了 method/url 模板/参数/响应取值路径),调用时只需给参数值。参数:name=插件名(先用 plugin_list 查看可用插件);params=参数对象,键为插件定义里的参数名,值为本次要查的值,定义中标 required 的参数必须提供,未提供的自动用插件默认值。返回原始响应(raw)与按插件 responsePath 取出的结果(result)。用户要查数据且存在对应插件时(市场装的或用户自建的),优先用本工具而不是手写 http_request。",
            JSONObject().apply {
                put("name", pathP("要调用的插件名(先用 plugin_list 查看可用插件后照抄名称)"))
                put("params", JSONObject()
                    .put("type", "object")
                    .put("description", "本次调用的参数值:键=插件定义的参数名,值=要查询的值;省略的参数用插件默认值"))
            }, arrayOf("name"))
        // ===== 插件创作:提交声明式 HTTP 工具插件到插件市场 =====
        fn(TOOL_PLUGIN_SUBMIT, "提交插件到插件市场(创作模式专用):把一个「声明式 HTTP 工具插件」提交给开发者审核,审核通过后自动上架。参数:name=插件名(40 字内,不能与已有待审/已上架插件重名);description=一句描述(300 字内,写清这个工具能干什么,审核的人要看);pluginJson=完整的插件 JSON 字符串,字段:type=\"http_tool\"、method(GET/POST)、url(URL 模板,参数用 {参数名} 占位)、responsePath(可选,从响应 JSON 取值的点分路径,如 data.result)、params(参数数组,每项含 name[字母开头的字母/数字/下划线]、label[显示名]、required、default[可选])。提交前应先用 http_request 真实调试过接口,确认能取到数据。",
            JSONObject().apply {
                put("name", pathP("插件名,40 字内,不可与已有插件重名"))
                put("description", pathP("插件描述,300 字内,写清能干什么"))
                put("pluginJson", pathP("完整的插件 JSON 字符串(type=http_tool / method / url / responsePath / params)"))
            }, arrayOf("name", "description", "pluginJson"))
        // ===== 长任务类工具 =====
        fn("wait", "延迟等待:先等一段时间再继续后面的步骤。适合用户说「先做 A,等 N 分钟后再做 B」「等 30 秒后继续」这类需要延迟的场景。单次最长 $MAX_WAIT_SECONDS 秒;需要更久时请连续多次调用(例如等 30 分钟 = 调 3 次 600 秒)。若等待时间较长(超过约 1 分钟),请先调用 keep_alive 开启后台保活,避免等待期间应用被系统冻结或杀掉。",
            JSONObject().apply {
                put("seconds", pathP("等待秒数(与 minutes 二选一)"))
                put("minutes", pathP("等待分钟数(可选,与 seconds 二选一)"))
            }, emptyArray())
        fn("keep_alive", "后台保活:挂一个悬浮窗,让应用退到后台后仍保持较高优先级,不被系统冻结或回收,从而让长任务(如 wait 等待、连续打开其它应用、操控用户手机)能继续跑完。两种模式:mode=visible(默认,推荐)——用户可见的小窗(默认显示「AI 运行中」),可拖动、点击回到应用,可见窗口优先级最高、保活最稳;mode=invisible——1×1 像素完全透明的隐形窗,用户看不到,但保活强度较弱、部分系统仍会冻结,仅在用户明确要求「不要看到任何东西」时使用。on=true 开启(on 缺省即开启),on=false 关闭并移除悬浮窗;background=true 表示开启保活的同时把应用退到后台(适合用户要暂时离开)。text 用于自定义小窗文案:大任务、要操控用户手机完成一系列操作时,请主动用它写成你当前正在做的事(如「正在操控手机」「正在打开微信」「等待 5 分钟」),让用户知道你在忙什么,建议不超过 8 个字;省略则显示默认「AI 运行中」。位置可用 position 指定预设锚点(top_right 右上角(默认)/ top_left 左上角 / bottom_left 左下角 / bottom_right 右下角),或用 x / y 指定自定义坐标(dp,相对屏幕左上角)——用户说「挪到右上角」「别挡着中间」「放到下面」这类要求时就用它调整。重复调用 keep_alive 传 text / position 即可更新文案或移动,无需先关闭。需要「显示在其他应用上层」权限;未授权时工具会提示并自动打开系统授权页。任务完成后请调用 on=false 关闭保活。",
            JSONObject().apply {
                put("on", pathP("true 开启(默认) / false 关闭"))
                put("mode", pathP("visible(默认,推荐:用户可见小窗) / invisible(隐形窗,保活较弱)"))
                put("background", pathP("可选:true 时同时把应用退到后台,默认 false"))
                put("text", pathP("可选:小窗文案,写你当前正在做的事(如「正在操控手机」),建议不超过 8 字;省略则显示「AI 运行中」"))
                put("position", pathP("可选:小窗位置 top_right(默认,右上角) / top_left / bottom_left / bottom_right"))
                put("x", pathP("可选:自定义横坐标(dp,相对屏幕左上角);给了 x/y 就按坐标放,忽略 position"))
                put("y", pathP("可选:自定义纵坐标(dp,相对屏幕左上角)"))
            }, emptyArray())
        // ===== 无障碍操控手机类工具 =====
        fn("phone_access", "查询/申请无障碍权限:调用 phone_* 系列工具(读屏、点击、输入、按键、截屏)之前需要先有「无障碍服务」权限。本工具无参数:已开启则返回 ready=true;未开启会自动打开系统无障碍设置页并返回提示。用户要求你操作其它应用(打开微信发消息、打开网页搜索、点按某个按钮)时,可先调它确认权限。",
            JSONObject(), emptyArray())
        fn("phone_screen", "读屏:获取用户当前手机界面的包名与所有可见元素(文字/类型/是否可点/是否输入框/中心坐标)。这是你「知道用户现在在哪」的唯一手段——操作手机前必须先读屏看清当前界面,操作后再读屏确认结果,不要盲点坐标。返回的 elements 里每个元素都带 x/y,可直接用于 phone_tap。默认会先等待界面稳定(等当前界面停止刷新再读),因此点击/跳转后立刻调用它也能拿到新界面,不会读到上一轮的旧内容;仅在极少数需要「立刻抓取当前瞬间」时才传 wait_stable=false。若发现返回的界面和你预期的不一样(例如刚点开某应用却仍是桌面),说明界面还在加载,可再次调用本工具重试。",
            JSONObject().apply {
                put("max_nodes", pathP("可选:最多返回多少个元素,默认 80"))
            }, emptyArray())
        fn("phone_tap", "在手机当前界面上点击(或长按)。两种定位方式:(1) text——按屏幕上的文字找元素并点它(推荐,最稳);(2) x/y——直接点坐标(先用 phone_screen 读屏拿到坐标)。长按传 long=true。",
            JSONObject().apply {
                put("text", pathP("要点击的元素文字,如「发送」「搜索」(与 x/y 二选一,优先用 text)"))
                put("x", pathP("横坐标(与 text 二选一)"))
                put("y", pathP("纵坐标(与 text 二选一)"))
                put("long", pathP("可选:true 表示长按,默认 false"))
            }, emptyArray())
        fn("phone_swipe", "在手机当前界面上滑动(用于翻页、滚动列表、下拉刷新等)。给出起点与终点坐标及耗时,耗时越大滑得越慢。",
            JSONObject().apply {
                put("from_x", pathP("起点横坐标"))
                put("from_y", pathP("起点纵坐标"))
                put("to_x", pathP("终点横坐标"))
                put("to_y", pathP("终点纵坐标"))
                put("duration", pathP("可选:滑动耗时毫秒,默认 300"))
            }, arrayOf("from_x", "from_y", "to_x", "to_y"))
        fn("phone_type", "往手机当前界面的输入框写入文本(优先当前焦点输入框,否则第一个输入框)。写入前请先用 phone_tap 点一下目标输入框。注意:只负责填字,填完是否发送由你决定(通常再 phone_tap 点「发送」)。",
            JSONObject().apply {
                put("text", pathP("要写入的文本内容"))
            }, arrayOf("text"))
        fn("phone_key", "发送系统按键(全局动作):back 返回、home 回桌面、recents 最近任务、notifications 下拉通知栏、quick_settings 快捷设置。用于返回上一页、回到桌面、切换应用等。",
            JSONObject().apply {
                put("key", pathP("back / home / recents / notifications / quick_settings"))
            }, arrayOf("key"))
        tools.put(modeSwitchFunction())
        if (!minimal) return tools
        // 极简 schema:只保留核心+聊天扩展+模式切换,其余低频/设备/主机/网络诊断工具不声明
        // (大幅压缩普通对话的上下文;模型看不到就不会调用,减少误触设备/主机类工具的几率)
        val keep = CORE_TOOL_NAMES + CHAT_EXT_TOOL_NAMES + "set_agent_mode"
        val kept = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            val name = t.getJSONObject("function").getString("name")
            if (name in keep) kept.put(t)
        }
        return kept
    }

    /** 仅包含模式切换的 function schema:即使处于 Ask(无文件工具权限)也能让 AI 帮用户切换语义模式。 */
    private fun buildAgentModeOnlySchema(): JSONArray = JSONArray().put(modeSwitchFunction())

    /** set_agent_mode 的函数声明(复用:完整工具清单与「仅模式切换」清单都包含它)。 */
    private fun modeSwitchFunction(): JSONObject {
        val props = JSONObject().apply {
            put("mode", JSONObject().put("type", "string")
                .put("description", "目标模式:ask / craft / plan"))
        }
        val params = JSONObject().put("type", "object").put("properties", props)
            .put("required", JSONArray().apply { put("mode") })
        return JSONObject().put("type", "function").put("function",
            JSONObject().put("name", "set_agent_mode")
                .put("description", "切换 Agent 语义模式:ask=仅对话(没有访问、修改、创建文件的工具权限)、craft=拥有完整 Agent 能力可正常调用工具(默认)、plan=先判断是否需要方案,需要则先在工作区创建规划方案文档再继续执行。mode 取值 ask / craft / plan。")
                .put("parameters", params))
    }

    /** 沙盒根目录;不存在则按需创建 */
    fun agentRoot(ctx: Context): File = File(ctx.filesDir, AGENT_ROOT).apply { if (!exists()) mkdirs() }

    /**
     * 把模型传入的路径消毒为沙盒内的相对路径。
     * 拒绝 ".." 上跳、绝对路径、首分隔符跳出;返回已拼接的相对路径("" 表示根),非法返回 null。
     */
    fun sanitizeSandboxPath(raw: String): String {
        val norm = raw.trim().replace('\\', '/').trim('/')
        // 拆分后逐个校验:若有 ".."(上跳)即判非法
        val parts = norm.split('/')
        if (parts.any { it == ".." }) return "\u0000"
        // 过滤空段与 ".",重组相对路径
        return parts.filter { it.isNotBlank() && it != "." }.joinToString("/")
    }

    /** 当前用户是否处于 root 访问模式 */
    fun isRootAccess(ctx: Context, userId: Long): Boolean = getAccessMode(ctx, userId) == ACCESS_ROOT

    /**
     * 把模型传入的路径解析为可操作路径(供 sandboxFile 解析):
     * - root 模式:放行绝对路径与 ".." 上跳,规范化(canonicalPath)后返回真实绝对路径;
     * - 非 root 模式:维持沙盒锚定,走 sanitizeSandboxPath。
     * 非法返回 "\u0000"(沿用现有约定)。
     */
    fun resolveAgentPath(ctx: Context, userId: Long, root: File, raw: String): String {
        val norm = raw.trim().replace('\\', '/')
        if (norm.isEmpty()) return ""
        if (!isRootAccess(ctx, userId)) return sanitizeSandboxPath(norm)
        val abs = try {
            if (norm.startsWith("/")) File(norm).canonicalPath
            else root.resolve(norm).canonicalPath
        } catch (_: Exception) { "\u0000" }
        // 任何访问模式(含 root)都禁止 AI 把路径解析到备份目录:备份与工作区硬隔离
        if (abs != "\u0000" && isInsideBackupDir(ctx, abs)) return "\u0000"
        return abs
    }

    /**
     * 路径安全校验(兜底层,独立于 resolveAgentPath)。
     * - 非 root 模式:禁止绝对路径(以 "/" 开头)与 ".." 上跳,只允许锚定在 ai_files 内的相对路径;
     * - root 模式:放行绝对路径与上跳(root 模式下本就有意开放全域访问),但备份目录(workspace_backups)除外——
     *   无论何种模式,AI 一律禁止读取/修改/删除备份,从路径层彻底隔离。
     * 这样即使某条调用链漏调 resolveAgentPath,也不会直接落到任意绝对路径上。
     */
    private fun isSafeRel(ctx: Context, userId: Long, rel: String): Boolean {
        if (rel == "\u0000" || rel.contains("\u0000")) return false
        if (isRootAccess(ctx, userId)) {
            // root 模式也守住备份目录:任何指向 workspace_backups 的绝对/规范路径都判非法
            if (rel.startsWith("/") && isInsideBackupDir(ctx, rel)) return false
            return true
        }
        if (rel.startsWith("/")) return false
        return rel.split('/').none { it == ".." }
    }

    /** 判断某规范绝对路径是否落在 AI 备份目录内(备份与工作区物理隔离,AI 任何模式都禁止触碰)。 */
    private fun isInsideBackupDir(ctx: Context, canonical: String): Boolean {
        val backupDir = File(ctx.filesDir, BACKUP_ROOT).canonicalPath
        return canonical == backupDir || canonical.startsWith("$backupDir/")
    }

    // ===== 专业开发者工具实现 =====

    /** 抓取网页:下载 URL 内容,剥离无关标签后提取正文(尽量转 Markdown),结果落盘工作区并返回可读文本。 */
    private suspend fun doFetchUrl(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        var url = args.optString("url", "").trim()
        if (url.isBlank()) url = call.path.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true))
            return toolErr("fetch_webpage 的 url 非法(必须是 http/https 完整地址):$url")
        val timeout = argInt(call, "timeout", 20).coerceIn(5, 60)
        val keepMd = args.optString("keep_markdown", "true").trim().lowercase() != "false"
        return try {
            val req = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; AuroraChatAgent/1.0)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            val body = withContext(Dispatchers.IO) {
                com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    resp.body?.string() ?: throw RuntimeException("空响应")
                }
            }
            val html = body
            val title = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE).find(html)
                ?.groupValues?.get(1)?.trim()?.replace(Regex("""\s+"""), " ") ?: ""
            // 去脚本/样式/模板类标签
            var text = html
                .replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""<!--[\s\S]*?-->"""), " ")
            // 块级标签换成换行,便于分段
            text = text.replace(Regex("""(?i)</(p|div|br|li|tr|h[1-6]|section|article)>"""), "\n")
            text = text.replace(Regex("""<[^>]+>"""), " ")
            // 解码常见 HTML 实体
            text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            text = text.replace(Regex("""[ \t]+"""), " ").replace(Regex("""\n{3,}"""), "\n\n").trim()
            val md = if (keepMd) {
                val sb = StringBuilder()
                if (title.isNotBlank()) sb.append("# ").append(title).append("\n\n")
                sb.append(text)
                sb.toString()
            } else text
            // 落盘工作区:文件名取自 host + 时间戳
            val host = try { java.net.URI(url).host ?: "web" } catch (_: Exception) { "web" }
            val safeHost = host.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
            val fname = "fetch_${safeHost}_${System.currentTimeMillis()}.md"
            val root = agentRoot(ctx)
            val outF = java.io.File(root, fname)
            outF.writeText(md)
            val preview = md.take(2000)
            toolOk(JSONObject()
                .put("url", url)
                .put("title", title)
                .put("saved_to", fname)
                .put("length", md.length)
                .put("text", preview + if (md.length > preview.length) "\n...(已截断,完整内容见工作区文件 $fname)" else ""))
        } catch (e: Exception) {
            toolErr("抓取网页失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 非 root 模式下 Python 代码的禁止项(与 shell_exec 白名单同思路:只放行工作区内的纯计算与相对路径读写)。
     * 每项为 名称 to 正则;命中即拒绝执行,并把名称回给模型便于它自我修正。
     *
     * 说明:此处是静态文本检测,无法穷尽所有绕过手法(如 getattr 动态取属性),
     * 但能拦住绝大多数直白写法,与文件工具的路径锚定共同构成纵深防御。
     */
    private val PY_SANDBOX_DENY: List<Pair<String, Regex>> = listOf(
        "subprocess 模块(可执行任意系统命令)" to Regex("""(?i)\bsubprocess\b"""),
        "os.system / os.popen / os.exec* 等命令执行调用" to Regex("""(?i)\bos\s*\.\s*(system|popen|exec\w*|spawn\w*|fork|startfile)\b"""),
        "os.remove / unlink / rmdir / rename / chmod 等文件改动调用" to Regex("""(?i)\bos\s*\.\s*(remove|unlink|rmdir|removedirs|rename|renames|chmod|chown|truncate|link|symlink|replace)\b"""),
        "shutil 移动/删除类操作" to Regex("""(?i)\bshutil\s*\.\s*(rmtree|move|copy\w*|chown|disk_usage)\b"""),
        "绝对路径写法(如 open(\"/sdcard/...\"))" to Regex("""(?i)\b(open|Path|File|makedirs|mkdir|remove|unlink|rmdir|listdir|scandir|walk|stat)\s*\(\s*[rbfu]{0,2}['"]\s*/"""),
        "\"..\" 上跳路径写法" to Regex("""(?i)['"]\s*\.\./"""),
        "pathlib/fileinput 等模块的绝对路径构造" to Regex("""(?i)\b(pathlib|fileinput|tempfile)\s*\.\s*\w+\s*\(\s*[rbfu]{0,2}['"]\s*/""")
    )

    /** 执行 shell 命令:root 直连 su;非 root 仅放行只读/工作区类命令,写入与系统级命令拒绝。
     *  ⚠️ 进程工作目录固定为 AI 工作区 agentRoot(ctx),让 AI 直接用相对路径就能操作工作区文件。 */
    private fun doShellExec(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val cmd = args.optString("command", "").trim()
        if (cmd.isBlank()) return toolErr("shell_exec 的 command 为空")
        // 任何模式都禁止在 AI 工具里访问备份目录(与工作区硬隔离):命令一旦提到备份目录名或绝对路径即拒绝
        val backupCanon = File(ctx.filesDir, BACKUP_ROOT).canonicalPath
        if (cmd.contains(BACKUP_ROOT) || cmd.contains(backupCanon)) {
            return toolErr("禁止在 AI 工具中访问备份目录(workspace_backups 与工作区相互隔离,AI 不应触碰)。")
        }
        val timeout = argInt(call, "timeout", 30).coerceIn(5, 120)
        val rootMode = isRootAccess(ctx, userId)
        val wd = agentRoot(ctx) // shell 工作目录锚定 AI 工作区
        // PATH 兜底:App 进程环境 PATH 常缺失 /system/bin,导致 sh 内 ls/cat 等报 not found;显式补齐
        val shellEnv = arrayOf("PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:/data/local/bin")
        if (!rootMode) {
            // 非 root:不再用脆弱的前缀白名单,改为「黑名单拦截」:放行绝大多数只读/工作区命令,
            // 仅拦真正的破坏性操作与提权/解释器入口,避免把 ls/cd/git 这类基础命令误杀。
            val segments = cmd.split(Regex("""\s*(?:&&|\|\|?|;|\n)\s*"""))
            val destructiveRe = Regex("""(?i)\b(rm|mv|dd|mkfs|format|mount|chmod|chown|kill|reboot|shutdown|wipe|shred)\b|>\s*/dev/""")
            val interpreterRe = Regex("""(?i)^\s*(sh|bash|ash|dash|zsh|su|sudo|source)\b""")
            val pipeToShellRe = Regex("""(?i)\|\s*(sh|bash|ash|dash|zsh|su|sudo)\b""")
            for (seg in segments) {
                val s = seg.trim()
                if (s.isEmpty()) continue
                if (destructiveRe.containsMatchIn(s) || interpreterRe.containsMatchIn(s) || pipeToShellRe.containsMatchIn(s)) {
                    return toolErr("当前为非 root 模式,禁止破坏性命令(rm/mv/dd/mount/chmod 等)、提权入口(sh/su/sudo)与管道喂解释器。如需执行请切换到 root 访问模式。允许只读/工作区类命令:ls/cat/pwd/cd/find/grep/git/stat 等。")
                }
            }
        }
        // 缺陷B:脚本类工具执行前后局部快照 diff 补录——全量快照 diff 只能覆盖「任务开始前已存在」的文件;
        // 脚本在本轮内新建又删除的文件(mkdir+rm / write+rm / os.remove)在前后全量快照里都不存在,
        // 不补录就会漏掉这些真实改动,导致删除类任务「查看所有改动」为空。
        val snapBeforeScript = if (activeFileChangeRecorder != null) snapshotSandboxFiles(ctx, userId) else HashMap<String, SandboxSnap>()
        return try {
            val proc = if (rootMode) Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            else Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd), shellEnv, wd)
            val outSb = StringBuilder(); val errSb = StringBuilder()
            val tOut = Thread { proc.inputStream.bufferedReader().use { outSb.append(it.readText()) } }.apply { start() }
            val tErr = Thread { proc.errorStream.bufferedReader().use { errSb.append(it.readText()) } }.apply { start() }
            val finished = proc.waitFor(timeout.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            tOut.join(2000); tErr.join(2000)
            if (!finished) { proc.destroyForcibly(); return toolErr("shell 执行超时(${timeout} 秒),已强制终止") }
            val exit = proc.exitValue()
            val out = outSb.toString().trim()
            val err = errSb.toString().trim()
            val outClip = if (out.length > 4000) out.take(4000) + "\n...(输出过长已截断,总长 ${out.length})" else out
            val errClip = if (err.length > 2000) err.take(2000) + "\n...(错误过长已截断,总长 ${err.length})" else err
            val j = JSONObject().put("exit", exit).put("command", cmd).put("root", rootMode)
            if (out.isNotBlank()) j.put("stdout", outClip)
            if (err.isNotBlank()) j.put("stderr", errClip)
            if (exit == 0) toolOk(j) else toolErr("shell 退出码 $exit${if (err.isNotBlank()) ":$errClip" else ""}")
        } catch (e: Exception) {
            toolErr("shell 执行异常:${e.message ?: e.javaClass.simpleName}")
        } finally {
            // 脚本执行前后局部快照 diff 补录(缺陷B):捕获脚本内新建又删除等全量快照盲区
            mergeLocalScriptDiff(ctx, userId, snapBeforeScript)
        }
    }

    /** 执行 ADB 命令:依赖 adb 可执行且设备已开启调试。执行目录为应用工作区 ai_files。 */
    private fun doRunAdb(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val adbArgs = args.optString("args", "").trim()
        if (adbArgs.isBlank()) return toolErr("run_adb 的 args 为空(如 devices / shell pm list packages)")
        val timeout = argInt(call, "timeout", 60).coerceIn(10, 180)
        // 探测 adb 可执行文件:优先 PATH,其次常见位置
        val adbBin = listOf("adb", "/usr/bin/adb", "/opt/platform-tools/adb", "/root/Android/Sdk/platform-tools/adb", "/Users/user/Library/Android/sdk/platform-tools/adb", "/home/user/Android/Sdk/platform-tools/adb")
            .firstOrNull { bin ->
                try {
                    if (bin == "adb") { val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v adb")); p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); p.exitValue() == 0 }
                    else java.io.File(bin).canExecute()
                } catch (_: Exception) { false }
            } ?: return toolErr("未检测到 adb 可执行文件(PATH 中无 adb,常见位置也未找到)。请先安装 Android SDK platform-tools 并确保 adb 在 PATH 中。")
        val fullCmd = if (adbBin == "adb") arrayOf("adb") + adbArgs.split(Regex("""\s+""")).toTypedArray()
        else arrayOf(adbBin) + adbArgs.split(Regex("""\s+""")).toTypedArray()
        return try {
            val p = Runtime.getRuntime().exec(fullCmd, null, agentRoot(ctx))
            val outSb = StringBuilder(); val errSb = StringBuilder()
            val tOut = Thread { p.inputStream.bufferedReader().use { outSb.append(it.readText()) } }.apply { start() }
            val tErr = Thread { p.errorStream.bufferedReader().use { errSb.append(it.readText()) } }.apply { start() }
            val finished = p.waitFor(timeout.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            tOut.join(2000); tErr.join(2000)
            if (!finished) { p.destroyForcibly(); return toolErr("adb 执行超时(${timeout} 秒),已强制终止") }
            val exit = p.exitValue()
            val out = outSb.toString().trim()
            val err = errSb.toString().trim()
            val outClip = if (out.length > 4000) out.take(4000) + "\n...(输出过长已截断,总长 ${out.length})" else out
            val errClip = if (err.length > 2000) err.take(2000) + "\n...(错误过长已截断,总长 ${err.length})" else err
            val j = JSONObject().put("exit", exit).put("args", adbArgs)
            if (out.isNotBlank()) j.put("stdout", outClip)
            if (err.isNotBlank()) j.put("stderr", errClip)
            if (exit == 0) toolOk(j) else toolErr("adb 退出码 $exit${if (err.isNotBlank()) ":$errClip" else ""}")
        } catch (e: Exception) {
            toolErr("adb 执行异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ===== 专业开发者工具（第二批）实现 =====

    /** 发起任意 HTTP 请求，返回状态码/响应头/响应体（正文截断到 8000 字符）。 */
    private suspend fun doHttpRequest(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        var url = args.optString("url", "").trim()
        if (url.isBlank()) url = call.path.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true))
            return toolErr("http_request 的 url 非法（必须是 http/https 完整地址）：$url")
        val method = args.optString("method", "GET").trim().uppercase().ifBlank { "GET" }
        if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"))
            return toolErr("http_request 的 method 不支持：$method")
        val timeout = argInt(call, "timeout", 20).coerceIn(5, 60)
        val bodyStr = args.optString("body", "").ifBlank { null }
        val headersJson = args.optString("headers", "").ifBlank { null }
        return try {
            val rb = okhttp3.Request.Builder().url(url).header("User-Agent", "AuroraChat-Agent/1.0")
            if (headersJson != null) {
                try {
                    val hj = JSONObject(headersJson)
                    hj.keys().forEach { k -> rb.header(k, hj.optString(k)) }
                } catch (_: Exception) { /* 忽略非法 headers */ }
            }
            if (bodyStr != null && method != "GET" && method != "HEAD") {
                val ct = rb.build().header("Content-Type") ?: "application/json; charset=utf-8"
                rb.header("Content-Type", ct).method(method, bodyStr.toRequestBody(ct.toMediaType()))
            } else {
                rb.method(method, null)
            }
            val resp = withContext(Dispatchers.IO) {
                com.aurora.chat.data.api.HttpClient.client.newCall(rb.build()).execute()
            }
            val respBody = resp.body?.string() ?: ""
            val headers = JSONObject().apply { resp.headers.forEach { (k, v) -> put(k, v) } }
            val isBinary = respBody.isEmpty() && (resp.body?.contentLength() ?: -1) != 0L ||
                headers.optString("content-type", "").contains("application/octet-stream", ignoreCase = true)
            val j = JSONObject()
                .put("status", resp.code)
                .put("url", resp.request.url.toString())
                .put("headers", headers)
            if (isBinary) {
                j.put("note", "二进制响应（长度 ${(resp.body?.contentLength() ?: -1)}），不返回正文；如需内容请用 fetch_webpage / get_file 等")
            } else {
                j.put("body", if (respBody.length > 8000) respBody.take(8000) + "\n…（响应体过长已截断，总长 ${respBody.length}）" else respBody)
            }
            toolOk(j)
        } catch (e: Exception) {
            toolErr("HTTP 请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 列出运行中的进程（root 更全，非 root 仅本应用及部分可见进程）。 */
    private fun doProcessList(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val limit = argInt(call, "limit", 50).coerceIn(5, 200)
        val rootMode = isRootAccess(ctx, userId)
        return try {
            val cmd = if (rootMode) arrayOf("su", "-c", "ps -A -o pid,user,comm,args") else arrayOf("ps", "-A", "-o", "pid,user,comm,args")
            val p = Runtime.getRuntime().exec(cmd)
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            val lines = out.split("\n").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("USER") }
            val shown = lines.take(limit)
            val text = shown.joinToString("\n")
            toolOk(JSONObject()
                .put("count", shown.size)
                .put("root", rootMode)
                .put("note", "（root 模式信息更全；非 root 只能看到当前应用自身及部分可见进程）")
                .put("text", if (text.length > 6000) text.take(6000) + "\n…（已截断，至多显示 $limit 条）" else text))
        } catch (e: Exception) {
            toolErr("获取进程列表失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Ping 主机：优先 ICMP，失败回退 TCP 端口探测。返回可达性/耗时/丢包。 */
    private fun doPing(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val host = args.optString("host", "").trim().ifBlank { call.path.trim() }
        if (host.isBlank()) return toolErr("ping_host 的 host 为空")
        val count = argInt(call, "count", 4).coerceIn(1, 10)
        val port = if (args.has("port")) argInt(call, "port", 0) else 0
        return try {
            if (port > 0) {
                // TCP 端口探测
                var ok = 0
                val times = mutableListOf<Long>()
                for (i in 1..count) {
                    val t0 = System.currentTimeMillis()
                    try {
                        val s = java.net.Socket()
                        s.connect(java.net.InetSocketAddress(host, port), 3000)
                        s.close()
                        ok++; times.add(System.currentTimeMillis() - t0)
                    } catch (_: Exception) {}
                }
                toolOk(JSONObject()
                    .put("host", host).put("port", port)
                    .put("reachable", ok > 0)
                    .put("success", ok).put("total", count)
                    .put("avg_ms", if (times.isNotEmpty()) times.average().toInt() else -1))
            } else {
                // ICMP ping（系统 ping 命令）
                val p = Runtime.getRuntime().exec(arrayOf("ping", "-c", count.toString(), "-w", "10", host))
                val out = p.inputStream.bufferedReader().use { it.readText() }
                val errTxt = p.errorStream.bufferedReader().use { it.readText() }
                p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                val loss = Regex("""(\d+)% packet loss""").find(out)?.groupValues?.get(1) ?: "?"
                val avg = Regex("""rtt min/avg/max/mdev = [\d.]+/([\d.]+)/""").find(out)?.groupValues?.get(1) ?: "?"
                val reachable = p.exitValue() == 0 || !out.contains("0 received", ignoreCase = true)
                val clip = if (out.length > 3000) out.take(3000) + "\n…（已截断）" else out
                toolOk(JSONObject()
                    .put("host", host).put("reachable", reachable)
                    .put("packet_loss_pct", loss).put("avg_rtt_ms", avg)
                    .put("raw", clip + if (errTxt.isNotBlank()) "\n[err] $errTxt" else ""))
            }
        } catch (e: Exception) {
            toolErr("ping 失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 联网搜索（DuckDuckGo HTML 接口），返回若干结果与首个页面正文片段。 */
    /** 搜索专用短超时客户端:被阻断的源 8 秒连接超时即失败,不拖累整体节奏 */
    private val searchClient by lazy { com.aurora.chat.data.api.HttpClient.newClientWithTimeouts(15, 8) }

    private suspend fun doSearchWeb(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        var q = args.optString("query", "").trim()
        if (q.isBlank()) q = call.path.trim()
        if (q.isBlank()) return toolErr("search_web 的 query 为空")
        val max = argInt(call, "max", 5).coerceIn(1, 10)
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val failures = mutableListOf<String>()
        // 引擎 1:Bing RSS(国内直连可达,XML 结构稳定,首选)
        try {
            val j = searchViaBing(enc, q, max)
            if (j != null) return toolOk(j)
            failures.add("bing 未解析到结果")
        } catch (e: Exception) {
            failures.add("bing: ${e.message ?: e.javaClass.simpleName}")
        }
        // 引擎 2:DuckDuckGo HTML(境外源,直连常被阻断,作为有代理环境的回退)
        try {
            val j = searchViaDuckDuckGo(enc, q, max)
            if (j != null) return toolOk(j)
            failures.add("duckduckgo 未解析到结果")
        } catch (e: Exception) {
            failures.add("duckduckgo: ${e.message ?: e.javaClass.simpleName}")
        }
        return toolErr(
            "两个搜索源均失败(${failures.joinToString("; ")})。" +
            "注意:这只是搜索源不可达(境外搜索源在国内直连网络下常被阻断,属网络环境限制)," +
            "不代表设备无法联网;请改用 fetch_webpage 直接抓取具体网址获取信息," +
            "并如实向用户说明搜索源受限,不要断言「设备无法联网」。"
        )
    }

    /** 引擎 1:Bing RSS 检索(https://www.bing.com/search?q=..&format=rss,国内直连可达) */
    private suspend fun searchViaBing(enc: String, q: String, max: Int): JSONObject? {
        val url = "https://www.bing.com/search?q=$enc&format=rss&count=$max"
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; AuroraChatAgent/1.0)")
            .build()
        val xml = withContext(Dispatchers.IO) {
            searchClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                resp.body?.string() ?: throw RuntimeException("空响应")
            }
        }
        val results = mutableListOf<JSONObject>()
        Regex("""<item>([\s\S]*?)</item>""").findAll(xml).take(max).forEach { m ->
            val item = m.groupValues[1]
            fun tag(name: String): String =
                Regex("""<$name>([\s\S]*?)</$name>""").find(item)?.groupValues?.get(1)?.trim() ?: ""
            val title = android.text.Html.fromHtml(tag("title"), android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val link = android.text.Html.fromHtml(tag("link"), android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val snippet = android.text.Html.fromHtml(tag("description"), android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (title.isNotBlank() && link.isNotBlank()) {
                results.add(JSONObject().put("title", title).put("url", link).put("snippet", snippet))
            }
        }
        if (results.isEmpty()) return null
        return buildSearchResult(q, results)
    }

    /** 引擎 2:DuckDuckGo HTML 检索(境外源,回退用) */
    private suspend fun searchViaDuckDuckGo(enc: String, q: String, max: Int): JSONObject? {
        val url = "https://html.duckduckgo.com/html/?q=$enc"
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; AuroraChatAgent/1.0)")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val html = withContext(Dispatchers.IO) {
            searchClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                resp.body?.string() ?: throw RuntimeException("空响应")
            }
        }
        val results = mutableListOf<JSONObject>()
        val resultRegex = Regex("""<a rel=\"nofollow\" class=\"result__a\" href=\"(.*?)\">(.*?)</a>.*?<a class=\"result__snippet\"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        resultRegex.findAll(html).take(max).forEach { m ->
            val href = m.groupValues[1].replace("&amp;", "&").replace("&#x2F;", "/")
            val title = android.text.Html.fromHtml(m.groupValues[2], android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val snippet = android.text.Html.fromHtml(m.groupValues[3], android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            results.add(JSONObject().put("title", title).put("url", href).put("snippet", snippet))
        }
        if (results.isEmpty()) return null
        return buildSearchResult(q, results)
    }

    /** 组装搜索结果:results 列表 + 抓取首个结果正文片段(两引擎共用) */
    private suspend fun buildSearchResult(q: String, results: List<JSONObject>): JSONObject {
        val j = JSONObject().put("query", q).put("count", results.size)
        val arr = JSONArray()
        results.forEach { arr.put(it) }
        j.put("results", arr)
        try {
            val firstUrl = results.first().optString("url")
            if (firstUrl.startsWith("http")) {
                val fr = okhttp3.Request.Builder().url(firstUrl).header("User-Agent", "Mozilla/5.0").build()
                val fb = withContext(Dispatchers.IO) { searchClient.newCall(fr).execute().use { it.body?.string() ?: "" } }
                var ft = fb.replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<[^>]+>"""), " ").trim()
                ft = android.text.Html.fromHtml(ft, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("""\s+"""), " ").trim()
                j.put("first_page_text", if (ft.length > 2000) ft.take(2000) + "…（已截断）" else ft)
            }
        } catch (_: Exception) {}
        return j
    }

    /** 在工作区执行 git 命令（如 status/log/diff/add/commit/clone/pull/push/branch）。 */
    private fun doGit(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val gargs = args.optString("args", "").trim()
        if (gargs.isBlank()) return toolErr("git_command 的 args 为空（如 status / log --oneline -5 / add -A / commit -m 更新）")
        val timeout = argInt(call, "timeout", 60).coerceIn(10, 180)
        val root = agentRoot(ctx)
        // 探测 git
        val gitBin = try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v git")); p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); if (p.exitValue() == 0) "git" else ""
        } catch (_: Exception) { "" }
        if (gitBin.isBlank()) return toolErr("未检测到 git（PATH 中无 git）。请先安装 Git。")
        val fullCmd = arrayOf(gitBin) + gargs.split(Regex("""(?<![\\\\])\s+""")).toTypedArray()
        return try {
            val p = Runtime.getRuntime().exec(fullCmd, null, root)
            val outSb = StringBuilder(); val errSb = StringBuilder()
            val tOut = Thread { p.inputStream.bufferedReader().use { outSb.append(it.readText()) } }.apply { start() }
            val tErr = Thread { p.errorStream.bufferedReader().use { errSb.append(it.readText()) } }.apply { start() }
            val finished = p.waitFor(timeout.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            tOut.join(2000); tErr.join(2000)
            if (!finished) { p.destroyForcibly(); return toolErr("git 执行超时（${timeout} 秒），已强制终止") }
            val exit = p.exitValue()
            val out = outSb.toString().trim(); val err = errSb.toString().trim()
            val outClip = if (out.length > 4000) out.take(4000) + "\n…（输出过长已截断，总长 ${out.length}）" else out
            val errClip = if (err.length > 2000) err.take(2000) + "\n…（错误过长已截断，总长 ${err.length}）" else err
            val j = JSONObject().put("exit", exit).put("args", gargs).put("cwd", root.absolutePath)
            if (out.isNotBlank()) j.put("stdout", outClip)
            if (err.isNotBlank()) j.put("stderr", errClip)
            if (exit == 0) toolOk(j) else toolErr("git 退出码 $exit${if (err.isNotBlank()) ":$errClip" else ""}")
        } catch (e: Exception) {
            toolErr("git 执行异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 对比两段文本差异（统一 diff 格式）。 */
    private fun doDiff(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val a = args.optString("a", ""); val b = args.optString("b", "")
        if (a.isBlank() && b.isBlank()) return toolErr("diff_text 的 a/b 均为空")
        val context = argInt(call, "context", 3).coerceIn(0, 10)
        return try {
            val fa = java.io.File.createTempFile("diffa_", ".txt"); val fb = java.io.File.createTempFile("diffb_", ".txt")
            fa.writeText(a); fb.writeText(b)
            val p = Runtime.getRuntime().exec(arrayOf("diff", "-u", "-U", context.toString(), fa.absolutePath, fb.absolutePath))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val errTxt = p.errorStream.bufferedReader().use { it.readText() }
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            // diff 退出码 1 表示有差异（属正常），0 表示相同，>1 才异常
            val exit = p.exitValue()
            val text = if (exit == 0) "(两段文本完全相同)" else out.ifBlank { errTxt }
            val clip = if (text.length > 6000) text.take(6000) + "\n…（已截断）" else text
            toolOk(JSONObject().put("same", exit == 0).put("diff", clip))
        } catch (e: Exception) {
            toolErr("diff 执行异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 下载一个网页文件(任意类型,含 APK)到本地:保存到 AI 工作区 ai_files,或 root 模式下的绝对路径。
     * 复用 HttpClient 的 OkHttp 连接池与自签名信任,流式写盘避免大文件 OOM。
     */
    private suspend fun doDownloadFile(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val url = args.optString("url", "").trim()
        if (url.isBlank()) return toolErr("download_file 的 url 不能为空")
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true))
            return toolErr("download_file 的 url 必须是 http/https 链接")
        val destRel = argString(call, "dest")?.trim()?.ifBlank { null }
        val timeout = argInt(call, "timeout", 60).coerceIn(5, 300)
        val overwrite = args.optBoolean("overwrite", true)
        val headersJson = args.optString("headers", "").ifBlank { null }
        val root = agentRoot(ctx)
        val target: File = if (destRel != null) {
            val rel = resolveAgentPath(ctx, userId, root, destRel)
            if (!isSafeRel(ctx, userId, rel)) return toolErr("非法 dest:沙盒内禁止使用 \"..\" 或绝对路径越界")
            sandboxFile(root, rel)
        } else {
            val seg = url.substringAfterLast('/').substringBefore('?').trim()
            val name = if (seg.isNotBlank() && !seg.contains('/')) seg else "download_${System.currentTimeMillis()}"
            File(root, name)
        }
        if (target.exists() && !overwrite) return toolErr("目标已存在(且 overwrite=false):${target.absolutePath}")
        return try {
            val reqBuilder = okhttp3.Request.Builder().url(url).header("User-Agent", "AuroraChat-Agent/1.0")
            if (!headersJson.isNullOrBlank()) {
                try {
                    val hj = JSONObject(headersJson)
                    hj.keys().forEach { k -> reqBuilder.header(k, hj.optString(k)) }
                } catch (_: Exception) { /* 忽略非法 headers */ }
            }
            val clientToUse = com.aurora.chat.data.api.HttpClient.newClientWithTimeouts(readSeconds = timeout.toLong(), connectSeconds = 30)
            withContext(Dispatchers.IO) {
                clientToUse.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw java.io.IOException("空响应")
                    target.parentFile?.mkdirs()
                    java.io.FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (body.source().read(buf).also { read = it } != -1) out.write(buf, 0, read)
                    }
                }
            }
            val size = target.length()
            val lower = target.name.lowercase()
            val hint = if (lower.endsWith(".apk")) "（这是 APK,可用 install_apk 工具安装;如在工作区外请传绝对路径给 install_apk）" else ""
            toolOk(JSONObject().put("path", target.absolutePath).put("size", size).put("note", "已下载到本地$hint"))
        } catch (e: Exception) {
            toolErr("下载失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 安装 APK:把给定路径解析为本地文件,经 FileProvider 授权后拉起系统安装器(ACTION_VIEW + package-archive)。
     * path 支持:(1) 工作区相对路径(如 app.apk / downloads/x.apk),任何模式均可用;
     *           (2) 设备绝对路径(如 /sdcard/Download/app.apk),仅 root 模式下放行。
     * 仅负责拉起安装界面,安装结果由用户在系统安装器内决定。
     */
    private fun doInstallApk(ctx: Context, userId: Long, call: AgentToolCall): String {
        val path = argString(call, "path")?.trim()?.ifBlank { null }
        if (path == null) return toolErr("install_apk 的 path 不能为空")
        val root = agentRoot(ctx)
        val file: File = if (path.startsWith("/")) {
            // 绝对路径仅 root 模式放行:避免非 root 下被诱导安装任意位置的 APK
            if (!isRootAccess(ctx, userId)) {
                return toolErr("当前为非 root 模式,install_apk 不接受设备绝对路径。请先把 APK 放入工作区(如 app.apk),或切换到 root 访问模式。")
            }
            File(path)
        } else {
            val rel = resolveAgentPath(ctx, userId, root, path)
            if (!isSafeRel(ctx, userId, rel)) return toolErr("非法 path:沙盒内禁止使用 \"..\" 或绝对路径越界")
            sandboxFile(root, rel)
        }
        if (!file.exists()) return toolErr("APK 不存在:${file.absolutePath}")
        if (!file.name.lowercase().endsWith(".apk")) return toolErr("目标不是 APK 文件:${file.name}")
        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(intent)
                toolOk(JSONObject().put("path", file.absolutePath).put("note", "已拉起系统安装器,请在安装界面确认安装(安装成功与否由用户决定)"))
            } else {
                toolErr("未找到系统安装器(package installer),无法安装")
            }
        } catch (e: Exception) {
            toolErr("拉起安装器失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 依据已解析路径解析目标文件：root 模式下 rel 可为绝对路径；rel="" 时返回根目录 */
    private fun sandboxFile(root: File, rel: String): File {
        if (rel.startsWith("/")) return File(rel)
        var f = root
        for (seg in rel.split('/')) { if (seg.isNotBlank()) f = File(f, seg) }
        return f
    }

    data class AgentToolCall(val tool: String, val path: String, val content: String, val argsJson: String = "")

    /** 提取文本中 [TOOL_START]..[TOOL_END] 包裹的首个工具调用;无则返回 null */
    fun extractFirstToolCall(text: String): AgentToolCall? {
        val s = text.indexOf(TOOL_START)
        if (s < 0) return null
        val e = text.indexOf(TOOL_END, s)
        if (e < 0) return null
        val json = text.substring(s + TOOL_START.length, e).trim()
        return try {
            val obj = JSONObject(json)
            AgentToolCall(
                obj.optString("tool", "").trim(),
                if (obj.has("path")) obj.optString("path") else "",
                if (obj.has("content")) obj.optString("content") else "",
                obj.toString()
            )
        } catch (_: Exception) { null }
    }

    /**
     * 兜底安全网:扫描文本中的 ```json 代码块,识别含有已知工具名的 JSON(模型可能把工具调用当普通 JSON 输出)。
     * 仅当原生 function calling 和 @tool 文本协议都没命中时才作为最后手段使用。
     * 匹配条件:JSON 里有 "tool" 字段,值在已知工具名列表中。
     */
    fun extractToolCallFromCodeBlock(text: String): AgentToolCall? {
        val knownTools = setOf(
            "list_files", "read_file", "write_file", "delete_file",
            "create_folder", "delete_folder",
            "rename_file", "copy_file", "append_file", "stat_file", "find_files", "query_environment", "shutdown_device", "reboot_device", TOOL_GET_FILE, TOOL_GENERATE_MEDIA, TOOL_EXECUTE_PYTHON, TOOL_EXECUTE_LUA, "set_agent_mode",
            TOOL_FETCH_URL, TOOL_SHELL_EXEC, TOOL_RUN_ADB, TOOL_HTTP_REQUEST, TOOL_PROCESS_LIST, TOOL_PING, TOOL_SEARCH_WEB, TOOL_GIT, TOOL_DIFF, TOOL_DOWNLOAD_FILE, TOOL_INSTALL_APK, TOOL_FTP_TRANSFER,
            TOOL_SWITCH_TAB, TOOL_SEND_MESSAGE, TOOL_RECALL_MESSAGE, TOOL_SEND_FRIEND_REQUEST, TOOL_CREATE_POST,
            TOOL_AGENT_REVIEW, TOOL_AGENT_PLAN, TOOL_AGENT_FORK, TOOL_ASK_USER,
            TOOL_LIST_CONTACTS, TOOL_LIST_GROUPS,
            TOOL_JSON_PROCESS, TOOL_REGEX_TEST, TOOL_ENCODE_CONVERT, TOOL_HASH_DIGEST,
            TOOL_TIMESTAMP_CONVERT, TOOL_TEXT_STATS, TOOL_SIZE_CONVERT, TOOL_TAKE_SCREENSHOT,
            TOOL_CAPTURE_SCREEN,
            TOOL_OPEN_APP, TOOL_EXIT_APP, TOOL_UNINSTALL_APP, TOOL_WAIT, TOOL_KEEP_ALIVE,
            TOOL_PHONE_ACCESS, TOOL_PHONE_SCREEN, TOOL_PHONE_TAP,
            TOOL_PHONE_SWIPE, TOOL_PHONE_TYPE, TOOL_PHONE_KEY
        )
        // 匹配所有 ```json ... ``` 或 ``` ... ``` 代码块
        val codeBlockRegex = Regex("""```(?:json)?\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE)
        val matches = codeBlockRegex.findAll(text)
        for (m in matches) {
            val jsonCandidate = m.groupValues[1].trim()
            if (jsonCandidate.isEmpty()) continue
            try {
                val obj = JSONObject(jsonCandidate)
                val toolName = obj.optString("tool", "").trim()
                if (toolName in knownTools) {
                    return AgentToolCall(
                        tool = toolName,
                        path = obj.optString("path", ""),
                        content = obj.optString("content", ""),
                        argsJson = obj.toString()
                    )
                }
                // 也兼容原生 function calling 格式:{"name": "...", "arguments": {...}}
                val fnName = obj.optString("name", "").trim()
                if (fnName in knownTools && obj.has("arguments")) {
                    val argsObj = obj.optJSONObject("arguments")
                    return AgentToolCall(
                        tool = fnName,
                        path = argsObj?.optString("path", "") ?: "",
                        content = argsObj?.optString("content", "") ?: "",
                        argsJson = argsObj?.toString() ?: "{}"
                    )
                }
            } catch (_: Exception) { /* 不是合法 JSON,跳过 */ }
        }
        return null
    }

    /** 去掉文本中的工具调用标记块,返回纯对话内容(用于界面展示 / 历史去重) */
    fun stripToolBlocks(raw: String): String {
        return stripToolSentinels(raw.replace(Regex("@tool:start[\\s\\S]*?@tool:end"), "")).trim()
    }

    /** 供系统提示注入:应用身份 + 工作区意识 + 工具协议 + 外部文件权限 + 访问控制语义 + 语义模式说明 */
    fun buildAgentAwarenessPrompt(ctx: Context? = null, userId: Long = 0L): String = buildString {
        append("\n\n【应用身份】你运行在 Aurora Chat 这款应用里——它是由「LS 工作室」与「拾光工作室」联合开发的即时通讯应用(Android 客户端 + 自研 Go 后端)。")
        append("你只是应用内集成的 AI 助手(模型引擎),**不是**这款软件的开发者:当用户问「介绍一下本款软件/这个应用是谁做的」时,请介绍 Aurora Chat 及其开发者「LS 工作室与拾光工作室联合开发」;不要把你背后大模型服务的提供方当成应用的开发者,也不要把二者混淆。")
        append("被问到自己是什么模型/由谁训练时,如实介绍你的模型身份即可;被问到应用本身时,以本条为准。")
        append("\n【应用工作区与工具】")
        val rootAccess = ctx != null && isRootAccess(ctx, userId)
        if (rootAccess) {
            append("\n当前为 root 访问模式:除本应用工作区(根目录名 ai_files)外,你可以直接使用真实路径访问设备上的任意目录与文件。文件工具的 path 参数支持绝对路径(如 /sdcard/Download/xxx.txt),也支持相对路径(相对 ai_files 工作区)与 \"..\" 上跳。")
        } else {
            append("\n你可以操作本应用私有沙盒工作区(根目录名 ai_files,仅此目录内)浏览与编辑文件,帮助用户管理其工作区。")
        }
        append("\n可用工具:文件类 list_files / read_file / write_file / append_file / delete_file / create_folder / delete_folder / rename_file / copy_file / stat_file / find_files / get_file(取出文件并以可下载的文件卡片发给用户),环境查询 query_environment,模式切换 set_agent_mode(ask/craft/plan),以及电源操作 shutdown_device(关机)/ reboot_device(重启,均需 root)。生成类 generate_media:当用户想要生成图片或视频时调用,生成结果会自动作为图片/视频消息直接发送给用户;其 prompt 参数默认直接使用用户原话,仅在用户要求优化提示词、或描述太简略/不严谨/想要某种效果但表述不够严谨时才先优化成严谨提示词再传入。执行类 execute_python:当用户要求运行 Python 代码/脚本时调用;若设备未安装 Python 解释器(如 Termux 未装 python)会返回失败,此时如实告知用户并引导安装,不得假装执行成功。执行类 execute_lua:当用户要求运行 Lua 代码/脚本时调用,使用应用内嵌的 LuaJ 引擎,无需额外安装解释器,print 输出会作为 stdout 返回。⚡【脚本选择优先级】大工作量、批量处理、文件操作、循环任务等——**优先用 execute_lua**,理由:① 内嵌引擎,零依赖、任何时候都能跑;② 无需向用户确认 Termux/解释器状态;③ IO 已沙盒化(io.open 锚定工作区),安全。只有当用户明确要 Python、或用户环境确认有 Python 且任务涉及 Python 专属生态(如 numpy/pandas/requests 等第三方库)时,才用 execute_python。不要反过来:上来就试 Python 失败再降级 Lua——一步到位选 Lua。开发者工具:fetch_webpage 抓取公开网页并提取正文(落盘工作区,返回可读文本);shell_exec 执行 shell 命令(root 直连 su,非 root 仅只读/工作区类);run_adb 执行 adb 调试命令(依赖 adb 在 PATH);http_request 发起任意 HTTP 请求(调试 API/REST);process_list 列出运行中的进程;ping_host 探测主机/端口连通性;search_web 联网搜索(DuckDuckGo);git_command 在工作区执行 git 命令(status/log/diff/add/commit/clone 等);download_file 从网页链接下载任意文件(含 APK)到工作区或指定绝对路径;install_apk 给定 APK 路径(工作区相对路径或设备绝对路径)后拉起系统安装器安装。两者配合可实现「下载并安装 APK」全流程,也可单独安装本地已有 APK。")
        if (rootAccess) {
            append("\n文件工具路径:可传绝对路径或相对路径;绝对路径将直接解析到真实位置,相对路径锚定在 ai_files 工作区内。root 模式下也请尊重用户安全边界:涉及删除、覆盖、系统级目录等改动前先向用户说明;不执行破坏性系统操作(删除系统目录、格式化等)除非用户明确要求。")
        } else {
            append("\n文件工具路径一律为工作区内相对路径,禁止 \"..\" 或绝对路径。")
        }
        val roundsNow = if (ctx != null) maxToolRoundsFor(ctx, userId) else MAX_AGENT_TOOL_ROUNDS
        if (roundsNow == Int.MAX_VALUE) append("\n工具调用轮数不设上限。")
        else append("\n每个用户请求最多执行 $roundsNow 次工具调用。")
        // 执行模式行为块(编排层已同步生效:极速=0 轮+不声明工具,长任务=轮数不设上限)。
        // 多选:激活的每个模式依次叠加生效,互不覆盖。
        val activeExecModes = if (ctx != null) getExecModes(ctx, userId) else emptySet()
        for (execMode in activeExecModes) when (execMode) {
            EXEC_FAST -> append("\n【极速模式·工具已禁用】本次对话处于极速模式,所有工具调用已在编排层禁用(工具列表未传给模型,即使尝试调用也不会执行)。请直接用纯文本回答用户,不要尝试任何工具,也不要向用户解释你处于什么模式。")
            EXEC_LONG -> append("\n【长任务模式·进度落盘】本模式工具轮数不设上限,适合耗时长的复杂任务,请遵守「进度持久化」协议:(1) 开始多步骤任务前,先看工作区是否存在 .task_progress.md;若存在,说明这是上次被中断任务的延续,先读取它并从断点继续,不要从头重做;(2) 新任务开始时,把任务标题与待办清单写入 .task_progress.md(已完成项标 [x]);(3) 每完成一个重要步骤立即更新该文件;(4) 全部完成后删除该文件,并向用户汇报最终结果。简单问题(一两次工具就能解决)不要创建进度文件。任务预计耗时较长时,提醒用户可开启 keep_alive 后台保活。")
            EXEC_SELFCHECK -> append("\n【自检模式·核验后才交付】每次完成任务后、给出最终答复前,必须先完成一次自检:重新读取你本轮写入或修改过的文件(或重新查询你刚操作过的数据),核验结果真实符合用户要求——文件确实存在、内容正确完整、没有遗留占位符或明显错误;发现问题立即修复后再交付。交付时用一句话如实汇报自检结果(例如「自检通过:3 个文件已重读核验无误」或「自检发现 1 处错误,已修正」)。绝不许编造自检结果,也不许跳过自检直接交付。")
            EXEC_RESEARCH -> append("\n【深度研究模式·先检索后作答】回答前必须进行真实检索:用 search_web 从至少 3 个不同角度的关键词搜索,必要时用 fetch_webpage 阅读关键来源原文进行交叉验证;重要事实与结论不可只依赖单一来源。若不同来源信息冲突,如实指出分歧;若检索不到可靠信息,如实告知,绝不编造。最终输出结构化回复:先给结论,再给依据与对比,末尾附来源链接。简单闲聊无需检索,直接回答。")
            EXEC_PLAN -> append("\n【规划先行模式·先写方案再动手】当你判断一个任务需要 3 步及以上工具调用时:(1) 动手前先把执行方案写入工作区 plan.md(目标、步骤清单、当前进度);(2) 每完成一步就回去更新对应条目为 [x];(3) 中途被打断或遇到错误,先修改方案再继续;(4) 任务完成后在文件末尾追加「执行结果」一节。简单的少于 3 步的任务无需创建该文件,直接执行。")
            EXEC_MULTI_AGENT -> append("\n【多 Agent 模式·规划→执行→验收流水线】你现在是执行 Agent(流水线的中间环节),上下游各有一个你无法干预的独立 Agent:\n" +
                "① 规划者(前置):任务包含 3 步及以上操作、要创建/修改多个文件、或你不确定从何下手时,先调用 agent_plan(task=用户原始任务) 获取独立执行计划,再按计划逐步执行。简单的单步任务无需规划,直接干。\n" +
                "② 你(执行者):按计划干活(没有计划就按自己的判断干)。\n" +
                "③ 验收者(后置):凡是动了工作区文件的任务,给用户最终答复前必须调用 agent_review(task=用户原始请求,summary=你的工作汇报:做了什么、改了哪些文件含路径、关键操作与结果):\n" +
                "- verdict=PASS → 给用户交付总结:做了什么 + 「验收结论:PASS」+ 验收员核验要点(若走过规划,顺带一句计划是否被完整落实)。\n" +
                "- verdict=FAIL → 按 detail 里的问题清单逐条修复,然后再次调用 agent_review 复验;同一任务最多复验 2 次,仍不通过就如实告知用户哪些问题没解决,绝不许谎报完成。\n" +
                "- ok=false(验收失败/格式异常) → 如实转述,不要假装验收通过。\n" +
                "④ 并行派发(可选加速):任务可自然拆成互不依赖的几部分且并行明显更快(如同时创建多个不同文件),或用户明令要求并行/同时/一起干时,调用 agent_fork(tasks=[...]) 派发给多个独立执行 Agent 并发执行(最多 4 个)。tasks 每项写清「做什么 + 只允许操作的路径范围」,各项路径范围不得重叠;派发结果汇总返回后,失败的子任务自行补做,之后你仍必须调用 agent_review 验收整体成果。串行依赖的任务禁止派发。\n" +
                "一次工具调用就能完成的简单事实问答无需走流水线;凡是动了工作区文件的任务必须验收。你无法看到规划者/验收者的工作过程,只能看到它们的结论——这是刻意设计,不许向用户隐瞒 FAIL 记录。")
            EXEC_MEMORY -> {
                val memFile = File(agentRoot(ctx!!), "memory.md")
                val memContent = runCatching { if (memFile.isFile) memFile.readText() else "" }.getOrDefault("")
                append("\n【记忆增强模式·长期记忆已启用】工作区的 memory.md 是你的长期记忆文件(跨对话持久存在)。")
                if (memContent.isNotBlank()) {
                    append("当前记忆内容如下(可能过时,与实际情况冲突时以你实际核验到的为准):\n---\n${memContent.take(4000)}\n---")
                } else {
                    append("(当前记忆还是空的)")
                }
                append("\n使用规则:(1) 回答前先对照记忆,用户已经告知过的偏好/背景不要重复追问;(2) 当用户透露长期有效的信息(偏好、项目背景、常用设置、重要事实)时,主动用 write_file 把要点以精炼条目写入或更新 memory.md;(3) 记忆总量控制在 4000 字内,超出时合并或淘汰过时条目;(4) 一次性/临时性信息(验证码、单次任务细节等)禁止写入记忆;(5) 用户明确说「记住这个」时必须写入。")
            }
            EXEC_CREATE -> append("\n【创作模式·插件创作助手】你现在的核心使命是帮用户在插件市场创作插件。用户可能不想自己动手做,你要代替他完成全流程。插件是「声明式 HTTP 工具插件」:本体是一段 JSON,字段为 type=\"http_tool\"、method(GET/POST)、url(URL 模板,参数用 {参数名} 占位)、responsePath(可选,从响应 JSON 取值的点分路径,如 data.result)、params(参数数组,每项含 name[字母开头的字母/数字/下划线]、label[显示名]、required[布尔]、default[可选默认值])。工作流程:(1) 弄清用户想做什么工具(如查快递、查天气、汇率换算);(2) 关键信息缺失时(接口地址、参数含义、取哪个结果)用 ask_user 一次问清,或让用户把需求文档/接口文档发给你;用户已说清楚就直接干,不要逢事必问;(3) 帮用户找或确认可用的公开 API;用户给了接口就按用户的来;(4) 用 http_request 真实调试一次接口,确认 URL 与响应结构正确、responsePath 能取到想要的数据;调不通就换参数或如实告知;(5) 提交前用 plugin_list 确认没有重名插件,然后调用 plugin_submit 工具(name=插件名[40 字内],description=一句描述[300 字内,写清这个工具能干什么],pluginJson=完整的插件 JSON 字符串)提交上架,提交后进入开发者审核;(6) 最后告诉用户插件已提交,审核通过后别人也能在插件市场看到——但用户自己无需等待审核,现在就能用 plugin_call 直接调用它。简单工具全程无需用户介入;用户中途改变主意就按新需求重来。")
        }
        append("\n【长任务·后台保活】只要你的任务会让用户离开本应用——尤其是要调用 open_app 打开其它应用、连续打开多个应用、跳去别的软件操作,或用户说「我先去忙别的/一会儿回来」——本应用一旦退到后台就可能被系统冻结,任务会中断。因此必须先在动手前调用 keep_alive 开启后台保活(默认 mode=visible,即用户可见的小窗,保活最稳;需要同时离开本应用时用 background=true 把应用退到后台),再执行任务;任务全部完成后再调用 keep_alive(on=false) 关闭保活。若任务过程中需要等待较长时间(超过约 1 分钟),也应先开启保活再调用 wait。用户明确说「不要看到任何东西」时才用 mode=invisible,但要提醒他隐形窗保活较弱、可能仍被冻结。**例外:uninstall_app 是自包含的一键工具,不要为它开保活(它不需要悬浮窗),也不要给它套 phone_* 流程。**")
        append("\n【小窗文案·主动填写】凡是需要长时间在后台跑、或要操控用户手机完成一系列操作(打开其它应用、跨应用点按、连续跳转、长等待)的大任务,都要主动开启保活,并用 text 参数把小窗文案写成「你当前正在做的事」,让用户一眼知道你在忙什么,例如:正在操控手机 / 正在打开微信 / 正在逐个打开应用 / 等待 5 分钟后再继续 / 正在整理文件。文案要简短(建议不超过 8 个字),不要写句号、不要写客套话;没有特别想表达的才省略 text(此时显示默认的「AI 运行中」)。任务推进到新阶段时可以再次调用 keep_alive 并传入新的 text 更新文案(无需先关闭),例如从「正在打开微信」改成「正在发送消息」。")
        append("\n【操控手机·无障碍】用户要你操作手机里的其它应用(打开微信发消息、打开网页搜索、点按某个按钮、翻页滑动等)时,按这个流程走:(1) 先 phone_access 确认无障碍权限,没有就告诉用户去开启(工具会自动打开系统设置页),用户开好后继续;(2) keep_alive 开保活并用 text 汇报当前动作(要离开本应用时 background=true);(3) 动手前先 phone_screen 读屏,看清当前在哪个应用/界面、有哪些可点元素(元素都带 x/y 坐标);(4) 用 phone_tap(优先按文字点击,比坐标稳)/ phone_swipe / phone_type / phone_key 执行动作;(5) 每次操作后都要再 phone_screen 确认结果,再决定下一步,绝不能盲点坐标或凭空假设界面。每进入一个新阶段就用 keep_alive 更新 text,让用户在小窗里看到进度(例如:正在查看屏幕 → 正在打开微信 → 正在编辑消息 → 正在发送)。任务全部完成后,open_app 打开 Aurora Chat 回到本应用,再 keep_alive(on=false) 关闭保活,并把结果交付给用户。涉及支付、转账、删除数据等破坏性操作时,必须先向用户确认再执行。**重要例外:「卸载某个应用」用 uninstall_app 即可,它自包含地完成了「检查无障碍 → 唤起卸载 → 点确认」整条链路。不要为卸载先开 keep_alive(会平白弹出一个悬浮窗)、不要先调 phone_access、也不要先 phone_screen 读屏——直接调 uninstall_app 一步到位。**")
        append("\n【截屏并交给用户】当用户说「把我的屏幕截下来发我」「看看我现在这个页面」「截个图给我」「屏幕上这个是什么,截图我看看」这类需求时,直接用 capture_screen。它只有两种交付方式,二选一,不要混用:(1) **发图片(默认)**——用户想看到画面时,直接调用 capture_screen 不传额外参数,它会强制把截图作为图片消息发出去,用户立刻能在聊天里看到并点开大图,与生成图片走完全相同的渲染链路。这是最常用的方式,不要在之后重复调用 get_file 再发一次。(2) **发文件**——画面很长、用户想看原图细节或想下载时,传 deliver=file,以文件卡片形式发送。另可传 keep=true 额外在工作区留一份副本(默认不留档);若用户明确说「存起来」「留着」「一会儿还用」,就传 keep=true,截图会落在工作区,再用 list_files / get_file 操作。注意:支付、密码等敏感页面系统会阻止截屏,此时工具会返回失败,要如实告诉用户「这个页面截图被系统拦截了」,不要假装截到了;截屏前若界面可能还在加载,本工具已内置等待,无需你额外 sleep。")
        append("\n【卸载应用】uninstall_app 用来卸载手机上的应用，**任何应用都可以**，不限于本应用。**它是一个自包含的一键工具：内部已经把「检查无障碍 → 唤起系统卸载器 → 自动点确认框 → 校验目标包是否真的消失」整条链路做完了。所以不要为它先开 keep_alive（会平白弹出一个悬浮窗，卸载根本不需要）、不要先调 phone_access、也不要先 phone_screen 读屏或 phone_tap 点按——直接调 uninstall_app 一步到位，重复调用同一个卸载请求也只会得到同样的失败，不要重试超过一次。** **必须传 target**，写成应用名（如 微信、抖音）或包名（如 com.tencent.mm）；只有当用户明确要卸载 Aurora Chat 本身时，target 才传「自己」。**target 缺失工具会直接报错，绝不会默认卸载本应用**——所以用户说「卸载微信」时，target 必须是「微信」，不能留空、也不能传「自己」。若不确定应用的确切名称或包名，先问用户，不要猜。两种方式：(1) **mode=system（默认）**——唤起系统卸载器，弹出系统确认框由用户自己点「确定」。兼容性最好、任何机型都能用，用户没特别说明时一律用它，并告诉用户「已经在屏幕上弹出卸载确认框了，点一下确定就行」。(2) **mode=auto**——全程无障碍自动完成，用户完全不用动手（第三方应用走系统卸载器并自动点确定；本应用走「回桌面→长按图标→卸载→确定」）。**仅当用户明确要求「你自己删，别让我动手」「用无障碍全自动卸载」「我不要介入」时才用**；用之前要确认无障碍服务已开启（可用 phone_access 检查，没开就先引导用户开启）。另外：mode=auto 遇到系统预装应用会被拦下，需要用户明确确认风险后再传 force=true。无论哪种方式，调用前都要先跟用户确认一次并提醒：卸载会清除该应用的全部数据且无法恢复；如果卸的是本应用，聊天记录、登录态、本地文件会一并清除。注意 mode=auto 的成败判据是「目标包是否真的从系统里消失」,工具会自动校验;若返回失败,你要**如实**转述失败原因(如无障碍服务未连接、用户手动取消了确认框),绝不能谎称已经卸载成功。")
        append("\n【重要】工具必须通过 function calling 方式调用--直接在回复中输出 JSON 代码块或文本协议都不会被执行,必须使用标准工具调用机制。如果你不确定如何调用,请直接回复用户并说明。")
        append("\n【交流节奏】在动手干活前,先用通俗、亲切的话告诉用户你接下来要做什么(例如「好的,让我看一下这个目录」),执行过程中如需要可以简短同步进展;拿到结果后,再自然地告诉用户你看到了什么、结论是什么。让你的表达像对朋友说话一样自然,不要生硬、不要说官话套话。")
        append("\n【向用户提问】有些请求天生含糊——只说了「做什么」,没说清「做成什么样」。这种情况宁可先问一句,也不要猜着做完再返工:返工得整个重来一遍,比问一句话贵得多。遇到下面这些就先用 ask_user 把关键项一次问全(一次问清,不要挤牙膏式一轮问一点):① 新建类——用户说「新建一个文件/文档/文件夹」,却没说名称、内容或放在哪个目录;② 删除/改动类——用户说「删除文件」「改一下」「整理一下」,却没说具体是哪个对象(尤其是删除、覆盖这类会损坏数据的操作,必须先确认对象再动手,绝不能自己猜一个就删);③ 发送类——用户说「发个消息」「发给他」,却没说发给谁、发什么内容;④ 执行类——用户说「打开那个应用」「用某个工具」,却没说是哪个;⑤ 范围类——范围说不清、做多做少都可能白做(如「把文件都清理掉」)。提问时把每个关键项做成一个具体问题,选项要给能直接点选的具体值(例如文件名候选、目录候选),并把你的推荐值放在第一个选项;一次最多 5 个问题,能合并的合并成一题;multi=true 表示多选,other=true 附加「其他」自由填写。用户会逐题「上一步/下一步/跳过」,跳过的题(skipped=true)不要再追问,按你的合理默认继续。\n反过来,下面这些一律不要问:你自己能合理决定的事(文件格式、默认命名、显而易见的目录)、用户上一轮已经说清楚的事、能从当前上下文推断出来的事(例如刚讨论过那个文件)、纯闲聊,以及「要不要我继续」这类没营养的问题。原则是:一次问清、避免返工,而不是逢事必问。")
        append("\n【环境感知】用户想了解你的运行环境时,请调用 query_environment 获取真实、尽量完整的设备信息,并如实转述(型号、Android 版本、root 状态、当前访问模式等),不要编造。")
        // 软件自身身份(背景资料):让 AI 知道它所在软件的名称/官网/下载地址,用户问起时可直接据已知信息回答,无需联网搜索。
        append("\n【关于你所在的软件】你运行在一款名为 Aurora Chat 的即时通讯软件中,用户正是通过这款软件(移动端 App)与你交流。这款软件的资料如下,供你在用户问起时参考——官网:YOUR_SERVER_DOMAIN;下载地址:https://www.YOUR_SERVER_DOMAIN/backend/data/tools/AuroraChat.apk。这是你所在软件的已知信息:当用户问到这款软件叫什么、官网是什么、在哪里下载,或问你是什么软件里的 AI 时,你直接依据上述资料回答即可,不需要去联网搜索;当前安装的版本号以 query_environment 返回的 app_version 为准,不要编造。")
        if (rootAccess) {
            append("\n【外部/系统文件权限】当前为 root 访问模式,你有权限读取、修改、删除沙盒之外的系统、外部或其它应用文件。对删除、覆盖、系统关键目录等可能造成不可逆影响的改动,先向用户简要说明将执行什么操作并取得确认;禁止在未获用户明确同意的情况下执行破坏性系统操作。\n【AI 备份目录】无论何种访问模式,filesDir 下的 workspace_backups 目录(及其子内容)是应用的「备份与恢复」数据,与工作区 ai_files 完全隔离,严禁你读取、修改、删除或移动它——即便在 root 模式下也绝对禁止触碰。")
        } else {
            append("\n【外部/系统文件权限】若用户要求读取、修改或删除沙盒之外的系统、外部或其它应用文件,你必须如实说明无法访问沙盒外内容,并建议用户先用应用的「导入」功能把文件带进工作区,再操作;不得假装成功或编造内容。")
        }
        append("\n【访问控制】本应用提供「root 模式 / 完全访问 / 手动审批」三种访问控制语义(界面选择状态由应用处理):root 模式=用户设备已 root 且开启管理员模式,可跨应用更自由地访问;完全访问=直接自动执行工具;手动审批=在写入、删除等会改动内容的操作前,先向用户说明将执行的操作以取得确认。处于 root 模式时你也应尊重用户安全边界,不执行破坏性系统操作除非用户明确要求。")
        // 语义模式注入:Ask/Plan 需额外说明;Craft 为现状全功能,无需额外注入
        val agentMode = if (ctx != null) getAgentMode(ctx, userId) else MODE_CRAFT
        when (agentMode) {
            MODE_ASK -> append("\n【当前语义模式:Ask(仅对话)】你当前处于 Ask 模式:只能与用户对话交流,没有访问、修改、创建文件的工具权限。当用户要求调用工具或操作文件时,你不能直接执行;但你可以调用 set_agent_mode 帮用户把模式切换到 Craft 或 Plan 后再继续执行,或明确告诉用户需要手动切换。")
            MODE_PLAN -> append("\n【当前语义模式:Plan(先规划再执行)】当用户提出要做某件事时,先判断是否真的需要方案:如果确实需要,先在工作区(ai_files)内创建一份规划方案文档,方案百分百创建好后自动调用 set_agent_mode 工具把模式切换为 craft,再继续执行;如果用户只是闲聊则无需创建方案。")
            else -> { /* Craft:保持现有全功能提示,无需额外注入 */ }
        }
        // 交付契约(仅任务型会话注入,避免污染闲聊):把「能否结束」收回到编排层,见 AgentTaskCompletion
        if (ctx != null && AgentTaskCompletion.needsContract(ctx, userId)) {
            append(AgentTaskCompletion.buildContractPrompt(ctx, userId))
        }
        // 收尾总结(按需):只在确实完成「较重的多步任务」时才补一段,避免为走流程而强行总结。
        // 「总结」二字由前端渲染层加粗显示(见 MessageBubble 的 level==-1 分支),提示词只要求单独成行。
        if (agentMode != MODE_ASK) {
            append("\n【收尾总结(按需,严禁滥用)】只有本次确实完成了较重的多步任务(多次工具调用、创建或修改了文件、产出了方案等)时,才在回复最后用单独一行写「总结」作为标题,下一行紧跟一两句简洁的结论(做了什么、结果如何)。以下情况一律不要写「总结」:普通对话或简单问答、只调用了一两次工具、用户明确说了不需要总结、用户要求简短或直接回答。绝不允许为了走流程而强行加一段「总结」。")
            if (agentMode == MODE_PLAN) {
                append("\nPlan 模式下,只要你真的创建了方案文档,最后一步就必须调用 get_file 把该方案文档以文件卡片的形式发给用户,不要只口头说「方案已保存在某个文件」。")
            }
        } else {
            // Ask 纯对话:只需保证给出非空可见回复即可,无需固定「总结」标题。
            append("\n【最终必须输出可见内容】你在每一轮回复的最后都必须输出一段非空的、对用户可见的正文:哪怕只是「已处理完成」「好的,已发你」或一句简短说明也可以,但绝不允许只思考而不说话、绝不允许返回空内容。")
        }
        append("\n【输出格式:思考与正文】你的内部推理、推演、权衡等过程一律放在思考(reasoning)中,不要把它当作给用户的回复。而【对用户说的话】--包括最终结论、确认结果(例如「已创建文件」「完成」「好的,已处理」)、对结果的总结--必须完整、正式地写在正文(content)里作为正常回复输出。即使某个结论你在思考中已经得出,最后也必须在正文里重新明确地写出来;绝不允许把用户应当看到的结论只放在思考里而不输出正文。")
    }

    // ===== Agent 工具内部实现(全部限定在沙盒根内;失败返回 {ok:false,...},不抛异常) =====
    private fun toolOk(extra: JSONObject? = null): String {
        val o = JSONObject().put("ok", true)
        if (extra != null) { val keys = extra.keys(); while (keys.hasNext()) { val k = keys.next(); o.put(k, extra.get(k)) } }
        return o.toString()
    }
    private fun toolErr(msg: String): String = JSONObject().put("ok", false).put("error", msg).toString()
    private fun doListFiles(ctx: Context, userId: Long, root: File, rel: String): String {
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        val dir = if (rel.isBlank()) root else sandboxFile(root, rel)
        if (!dir.exists()) return toolErr("目录不存在:$rel")
        if (!dir.isDirectory) return toolErr("目标不是目录:$rel")
        val arr = JSONArray()
        (dir.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }.forEach { f ->
            arr.put(JSONObject()
                .put("name", f.name)
                .put("type", if (f.isDirectory) "dir" else "file")
                .put("size", if (f.isFile) f.length() else 0L))
        }
        return toolOk(JSONObject().put("path", rel).put("items", arr))
    }
    private fun doReadFile(ctx: Context, userId: Long, root: File, rel: String): String {
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        val f = sandboxFile(root, rel)
        if (!f.exists() || !f.isFile) return toolErr("文件不存在:$rel")
        return try {
            val content = f.readText(Charsets.UTF_8)
            toolOk(JSONObject().put("path", rel).put("content", content))
        } catch (e: Exception) { toolErr("读取失败:${e.message}") }
    }
    private fun doWriteFile(ctx: Context, userId: Long, root: File, rel: String, content: String): String {
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        if (rel.isBlank()) return toolErr("缺少文件名")
        val f = sandboxFile(root, rel)
        val oldText = if (f.exists() && f.isFile) try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" } else ""
        return try {
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            recordFileChange(rel, if (oldText.isNotEmpty()) "modified" else "created", oldText = oldText, newText = content)
            toolOk(JSONObject().put("path", rel).put("bytes", f.length()))
        } catch (e: Exception) { toolErr("写入失败:${e.message}") }
    }
    private fun doDeleteFile(ctx: Context, userId: Long, root: File, rel: String): String {
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        if (rel.isBlank()) return toolErr("缺少文件名")
        val f = sandboxFile(root, rel)
        if (!f.exists()) return toolErr("文件不存在:$rel")
        if (!f.isFile) return toolErr("目标不是文件:$rel")
        val oldText = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
        return if (f.delete()) { recordFileChange(rel, "deleted", oldText = oldText); toolOk(JSONObject().put("path", rel)) }
               else toolErr("删除失败:$rel")
    }
    private fun doCreateFolder(ctx: Context, userId: Long, root: File, rel: String): String {
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        if (rel.isBlank()) return toolErr("缺少目录名")
        val f = sandboxFile(root, rel)
        if (f.exists()) return toolErr("已存在:$rel")
        return if (f.mkdirs()) toolOk(JSONObject().put("path", rel))
               else toolErr("创建失败:$rel")
    }
    /** 是否为文件系统根(删除保护,防止 root 模式下 delete_folder 删到 "/") */
    private fun isFsRoot(f: File): Boolean {
        val p = f.absolutePath
        return p == "/" || p == "\\" || Regex("^[A-Za-z]:[\\\\/]?$").matches(p)
    }
    private fun doDeleteFolder(ctx: Context, userId: Long, root: File, rel: String): String {
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        if (rel.isBlank()) return toolErr("禁止删除沙盒根目录 ai_files")
        val f = sandboxFile(root, rel)
        if (isFsRoot(f)) return toolErr("禁止删除文件系统根目录")
        if (!f.exists()) return toolErr("目录不存在:$rel")
        if (!f.isDirectory) return toolErr("目标不是目录:$rel")
        return if (f.deleteRecursively()) { recordFileChange(rel, "deleted_folder"); toolOk(JSONObject().put("path", rel)) }
               else toolErr("删除失败:$rel")
    }
    private fun doRenameFile(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val rel = resolveAgentPath(ctx, userId, root, call.path)
        if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        val newName = sanitizeSandboxPath(argString(call, "new_name") ?: "")
        // 新名字必须是纯文件名:不含路径分隔符、不是 ".."、非空(newName 已过 sanitize,".." 会返回 NUL)
        if (newName.isBlank() || newName.contains('/') || newName == "\u0000") return toolErr("非法新名字:必须为不含目录的合法名称")
        val src = sandboxFile(root, rel)
        if (!src.exists()) return toolErr("不存在:$rel")
        val dst = File(src.parentFile, newName)
        if (dst.exists()) return toolErr("目标已存在:$newName")
        val parentRel = rel.substringBeforeLast('/').takeIf { it != rel } ?: ""
        val newRel = if (parentRel.isEmpty()) newName else "$parentRel/$newName"
        val oldText = if (src.isFile) try { src.readText(Charsets.UTF_8) } catch (_: Exception) { "" } else ""
        return if (src.renameTo(dst)) { recordFileChange(newRel, "renamed", fromPath = rel, oldText = oldText); toolOk(JSONObject().put("path", rel).put("new_name", newName).put("abs", dst.absolutePath)) }
               else toolErr("重命名失败:$rel → $newName")
    }
    private fun doCopyFile(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val srcRel = resolveAgentPath(ctx, userId, root, argString(call, "source") ?: "")
        val dstRel = resolveAgentPath(ctx, userId, root, argString(call, "target") ?: "")
        if (!isSafeRel(ctx, userId, srcRel) || srcRel.isBlank()) return toolErr("非法源路径")
        if (!isSafeRel(ctx, userId, dstRel) || dstRel.isBlank()) return toolErr("非法目标路径")
        val src = sandboxFile(root, srcRel)
        if (!src.exists()) return toolErr("源不存在:$srcRel")
        val dst = sandboxFile(root, dstRel)
        if (dst.exists()) return toolErr("目标已存在:$dstRel")
        return try {
            if (src.isDirectory) src.copyRecursively(dst, overwrite = false)
            else src.copyTo(dst, overwrite = false)
            val copiedText = if (dst.isFile) try { dst.readText(Charsets.UTF_8) } catch (_: Exception) { "" } else ""
            recordFileChange(dstRel, "copied", newText = copiedText)
            toolOk(JSONObject().put("source", srcRel).put("target", dstRel).put("bytes", if (dst.isFile) dst.length() else 0L))
        } catch (e: Exception) { toolErr("复制失败:${e.message}") }
    }

    /** extract_archive:解压工作区内 ZIP 压缩包。target 空则在同级目录下以压缩包名(去扩展名)创建子目录。 */
    private fun doExtractArchive(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val srcRel = resolveAgentPath(ctx, userId, root, argString(call, "source") ?: "")
        if (!isSafeRel(ctx, userId, srcRel) || srcRel.isBlank()) return toolErr("非法路径")
        val src = sandboxFile(root, srcRel)
        if (!src.exists() || !src.isFile) return toolErr("压缩包不存在:$srcRel")
        if (!src.name.lowercase().endsWith(".zip")) return toolErr("只支持 .zip 格式,当前:${src.name}")
        // 确定目标目录
        val targetArg = (argString(call, "target") ?: "").trim()
        val targetDir: File
        val targetRel: String
        if (targetArg.isEmpty()) {
            val folderName = src.name.substringBeforeLast('.')
            targetDir = File(src.parentFile, folderName)
            targetRel = if (src.parentFile == root) folderName else "${srcRel.substringBeforeLast('/')}/$folderName"
        } else {
            val tRel = resolveAgentPath(ctx, userId, root, targetArg)
            if (!isSafeRel(ctx, userId, tRel) || tRel.isBlank()) return toolErr("非法目标路径")
            targetDir = sandboxFile(root, tRel)
            targetRel = tRel
        }
        targetDir.mkdirs()
        if (!targetDir.isDirectory) return toolErr("无法创建目标目录:$targetRel")

        var extracted = 0
        var skipped = 0
        return try {
            src.inputStream().use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val raw = entry.name
                        // ZIP slip 防护:拒绝含 .. 或以 / 或盘符开头的条目
                        val normalized = raw.replace('\\', '/')
                        val dangerous = normalized.contains("..") || normalized.startsWith('/') ||
                            Regex("^[A-Za-z]:").containsMatchIn(normalized)
                        if (dangerous) { skipped++; entry = zis.nextEntry; continue }
                        val out = File(targetDir, raw)
                        // 二次防护:解压目标必须在 targetDir 内
                        if (!out.canonicalPath.startsWith(targetDir.canonicalPath)) { skipped++; entry = zis.nextEntry; continue }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                            extracted++
                        }
                        entry = zis.nextEntry
                    }
                }
            }
            toolOk(JSONObject()
                .put("source", srcRel)
                .put("target", targetRel)
                .put("extracted", extracted)
                .put("skipped", skipped)
                .put("bytes", src.length()))
        } catch (e: Exception) { toolErr("解压失败:${e.message}") }
    }

    /** move_dir_contents:一次性把源目录所有直接子项搬到目标目录。target 空=上一级目录。自动处理重名。 */
    private fun doMoveDirContents(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val srcRel = resolveAgentPath(ctx, userId, root, argString(call, "source") ?: "")
        if (!isSafeRel(ctx, userId, srcRel) || srcRel.isBlank()) return toolErr("非法源路径(禁止为空或越界)")
        val src = sandboxFile(root, srcRel)
        if (!src.exists() || !src.isDirectory) return toolErr("源目录不存在:$srcRel")
        // 目标解析:'.'/'root'/'~'/'/'=工作区根目录(平铺到 ai_files 根);空=源的上一级目录;其他=相对路径
        val targetArg = (argString(call, "target") ?: "").trim()
        val targetDir: File
        val targetRel: String
        val isRootTarget = targetArg.equals(".", true) || targetArg.equals("root", true) || targetArg == "~" || targetArg == "/"
        if (isRootTarget) {
            targetDir = root
            targetRel = ""
        } else if (targetArg.isEmpty()) {
            targetDir = src.parentFile ?: return toolErr("源已在根目录,无上一级可搬")
            if (!targetDir.exists() || !targetDir.isDirectory) return toolErr("上一级目录不可用")
            targetRel = if (targetDir == root) "" else relativeOf(root, targetDir)
        } else {
            val tRel = resolveAgentPath(ctx, userId, root, targetArg)
            if (!isSafeRel(ctx, userId, tRel) || tRel.isBlank()) return toolErr("非法目标路径")
            targetDir = sandboxFile(root, tRel)
            targetRel = tRel
        }
        if (src == targetDir) return toolErr("源目录和目标目录相同,无需搬移")
        // 子项不能越界:目标不能是源的子目录
        if (targetDir.canonicalPath.startsWith(src.canonicalPath + File.separator))
            return toolErr("目标目录不能是源目录的子目录")

        val children = src.listFiles() ?: emptyArray()
        if (children.isEmpty()) return toolOk(JSONObject().put("moved", 0).put("source", srcRel).put("target", targetRel.ifBlank { "(root)" }))

        var moved = 0
        var failed = 0
        val conflicts = JSONArray()
        for (child in children) {
            var dst = File(targetDir, child.name)
            // 重名自动加后缀 _1 _2 ...
            if (dst.exists()) {
                var n = 1
                while (File(targetDir, "${child.name}_$n").exists()) n++
                dst = File(targetDir, "${child.name}_$n")
                conflicts.put("${child.name} -> ${dst.name}")
            }
            try {
                if (child.renameTo(dst)) moved++
                else {
                    // renameTo 跨文件系统可能失败,回退 copy + delete
                    if (child.isDirectory) {
                        child.copyRecursively(dst, overwrite = false)
                        child.deleteRecursively()
                    } else {
                        child.copyTo(dst, overwrite = false)
                        child.delete()
                    }
                    moved++
                }
            } catch (e: Exception) {
                failed++
            }
        }
        val isRoot = targetRel.isEmpty()
        return toolOk(JSONObject()
            .put("source", srcRel)
            .put("target", if (isRoot) "(工作区根目录)" else targetRel)
            .put("moved", moved)
            .put("failed", failed)
            .put("success", failed == 0 && moved > 0)
            .put("conflicts_renamed", conflicts)
            .put("message", if (failed == 0) "成功将 $moved 个子项移动到${if (isRoot) "工作区根目录" else " $targetRel"}" else "移动 $moved 个,失败 $failed 个"))
    }

    // ══════════════════════════════════════════════════════════════════
    //  新增工具实现(2026-09 安全增量,不碰原有逻辑)
    // ══════════════════════════════════════════════════════════════════

    /** compress_archive:把工作区内文件/目录打成 ZIP。sources 为相对路径数组,target 为输出路径。 */
    private fun doCompressArchive(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val args = argsOf(call)
        val sourcesArr = args.optJSONArray("sources")
            ?: return toolErr("sources 必须是路径数组")
        val targetArg = args.optString("target", "").trim()
        if (targetArg.isBlank()) return toolErr("缺少 target")
        val targetRel = resolveAgentPath(ctx, userId, root, targetArg)
        if (!isSafeRel(ctx, userId, targetRel)) return toolErr("非法 target 路径")
        val target = sandboxFile(root, targetRel)
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        try {
            target.outputStream().use { fos ->
                ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                    for (i in 0 until sourcesArr.length()) {
                        val srcRel = sourcesArr.getString(i).trim()
                        if (srcRel.isBlank()) continue
                        if (!isSafeRel(ctx, userId, srcRel)) continue
                        val src = sandboxFile(root, srcRel)
                        if (!src.exists()) continue
                        val baseName = args.optString("root", "").ifBlank { null }
                        addToZip(zos, src, baseName ?: src.name)
                    }
                }
            }
            return toolOk(JSONObject().put("target", targetRel).put("bytes", target.length()))
        } catch (e: Exception) { return toolErr("压缩失败:${e.message}") }
    }
    /** 递归把 File(目录或文件)加进 ZipOutputStream。baseName 是根节点在 zip 内的相对前缀。 */
    private fun addToZip(zos: ZipOutputStream, f: File, baseName: String) {
        if (f.isDirectory) {
            zos.putNextEntry(ZipEntry("$baseName/"))
            zos.closeEntry()
            (f.listFiles() ?: emptyArray()).forEach { addToZip(zos, it, "$baseName/${it.name}") }
        } else {
            val entry = ZipEntry(baseName)
            zos.putNextEntry(entry)
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    /** archive_list:列出 zip 内所有条目(不解压)。 */
    private fun doArchiveList(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val rel = resolveAgentPath(ctx, userId, root, argString(call, "path") ?: "")
        if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) return toolErr("非法路径")
        val f = sandboxFile(root, rel)
        if (!f.exists() || !f.isFile) return toolErr("zip 不存在:$rel")
        val entries = JSONArray()
        return try {
            f.inputStream().use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    var count = 0
                    while (entry != null && count < 5000) {
                        entries.put(JSONObject()
                            .put("name", entry.name)
                            .put("type", if (entry.isDirectory) "dir" else "file")
                            .put("size", entry.size))
                        count++
                        entry = zis.nextEntry
                    }
                    if (count >= 5000) entries.put("...截断,超过 5000 条")
                }
            }
            toolOk(JSONObject().put("path", rel).put("count", entries.length()).put("entries", entries))
        } catch (e: Exception) { toolErr("读取 zip 失败:${e.message}") }
    }

    /** archive_read:读取 zip 内单个条目作为文本(限 256KB,超出截断)。 */
    private fun doArchiveRead(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val args = argsOf(call)
        val rel = resolveAgentPath(ctx, userId, root, argString(call, "path") ?: "")
        if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) return toolErr("非法路径")
        val zip = sandboxFile(root, rel)
        if (!zip.exists() || !zip.isFile) return toolErr("zip 不存在")
        val entryName = args.optString("entry", "").trim()
        if (entryName.isBlank()) return toolErr("缺少 entry")
        val MAX = 256L * 1024L
        return try {
            zip.inputStream().use { fis ->
                ZipInputStream(fis).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (e.name == entryName) {
                            if (e.isDirectory) return toolErr("条目是目录,不是文件")
                            val bufSize = minOf(e.size.takeIf { it > 0 } ?: MAX, MAX + 1).toInt()
                            val buf = ByteArray(bufSize)
                            var read = 0; var n: Int
                            while (zis.read(buf, read, buf.size - read).also { n = it } > 0) { read += n }
                            val truncated = read > MAX
                            val text = String(buf.copyOf(minOf(read.toLong(), MAX).toInt()), Charsets.UTF_8)
                            return toolOk(JSONObject()
                                .put("entry", entryName)
                                .put("text", text)
                                .put("bytes", read)
                                .put("truncated", truncated))
                        }
                        e = zis.nextEntry
                    }
                    toolErr("zip 内未找到条目:$entryName")
                }
            }
        } catch (e: Exception) { toolErr("读取失败:${e.message}") }
    }

    /** kill_process:杀进程。root 直接 kill -9,非 root 只允许杀本应用。 */
    private fun doKillProcess(ctx: Context, userId: Long, call: AgentToolCall): String {
        val pidStr = argString(call, "pid") ?: return toolErr("缺少 pid")
        val pid = pidStr.toLongOrNull() ?: return toolErr("pid 必须是数字")
        val signal = argString(call, "signal")?.uppercase() ?: "KILL"
        if (isRootAccess(ctx, userId)) {
            val cmd = when (signal) {
                "TERM" -> "kill -TERM $pid"
                "INT"  -> "kill -INT $pid"
                else   -> "kill -9 $pid"
            }
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val ok = proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0
                return if (ok) toolOk(JSONObject().put("pid", pid).put("signal", signal))
                       else toolErr("kill 失败(进程可能已退出)")
            } catch (e: Exception) { return toolErr("kill 失败:${e.message}") }
        } else {
            // 非 root:只能杀自己
            val myPid = android.os.Process.myPid()
            if (pid != myPid.toLong()) return toolErr("非 root 模式不能杀其他进程(当前仅允许本应用 pid=$myPid)")
            try {
                android.os.Process.killProcess(pid.toInt())
                return toolOk(JSONObject().put("pid", pid).put("message", "已请求 killProcess"))
            } catch (e: Exception) { return toolErr("kill 失败:${e.message}") }
        }
    }

    /** stop_app:force-stop 指定包名。非 root 只允许停自己。 */
    private fun doStopApp(ctx: Context, userId: Long, call: AgentToolCall): String {
        val pkg = argString(call, "target") ?: return toolErr("缺少包名")
        val myPkg = ctx.packageName
        if (!isRootAccess(ctx, userId) && pkg != myPkg)
            return toolErr("非 root 模式只能停止本应用($myPkg)")
        val cmd = "am force-stop $pkg"
        return try {
            if (isRootAccess(ctx, userId)) {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val ok = proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0
                if (!ok) return toolErr("force-stop 失败(包名可能不存在)")
            } else {
                // 非 root 且停自己:直接 finish() + killProcess
                (ctx as? android.app.Activity)?.finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            toolOk(JSONObject().put("target", pkg))
        } catch (e: Exception) { toolErr("停止失败:${e.message}") }
    }

    /** upload_file:HTTP POST multipart 上传工作区文件到 URL。 */
    private fun doUploadFile(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val args = argsOf(call)
        val rel = resolveAgentPath(ctx, userId, root, argString(call, "path") ?: "")
        if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) return toolErr("非法 path")
        val urlStr = argString(call, "url") ?: return toolErr("缺少 url")
        val field = args.optString("field", "file").ifBlank { "file" }
        val headers = try { JSONObject(args.optString("headers", "{}")) } catch (_: Exception) { JSONObject() }
        val f = sandboxFile(root, rel)
        if (!f.exists() || !f.isFile) return toolErr("文件不存在:$rel")
        val boundary = "----AuroraBoundary${System.currentTimeMillis()}"
        return try {
            val url = java.net.URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                headers.keys().forEach { k -> setRequestProperty(k, headers.optString(k)) }
            }
            conn.outputStream.buffered().use { out ->
                // 分隔头
                out.write(("--$boundary\r\n").toByteArray())
                out.write(("Content-Disposition: form-data; name=\"$field\"; filename=\"${f.name}\"\r\n").toByteArray())
                out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                // 文件内容
                f.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val resp = runCatching {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrDefault("")
            conn.disconnect()
            toolOk(JSONObject()
                .put("path", rel)
                .put("url", urlStr)
                .put("http_code", code)
                .put("response", resp.take(2000)))
        } catch (e: Exception) { toolErr("上传失败:${e.message}") }
    }

    /** ftp_transfer:通过 FTPS(FTP over TLS)在本地工作区与远程 FTP 服务器间上传/下载/列举文件。
     *  action: upload/download/list(必填);host 必填;port 默认 21;user/password 凭据(留空匿名);
     *  remote_path 服务器路径(上传/下载必填,list 省略=根目录);local_path 工作区相对路径;
     *  mode: explicit(默认 FTPES)/implicit(隐式 FTPS);passive 默认 true。
     *  默认走 TLS 证书校验(安全);显式/隐式分别用 FTPSClient(false/true)。 */
    private fun doFtpTransfer(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val args = argsOf(call)
        val action = args.optString("action", "").trim().lowercase()
        if (action !in setOf("upload", "download", "list")) return toolErr("action 须为 upload/download/list")
        val host = args.optString("host", "").trim()
        if (host.isBlank()) return toolErr("缺少 host")
        val port = args.optInt("port", 21).coerceIn(1, 65535)
        val user = args.optString("user", "").ifBlank { "anonymous" }
        val password = args.optString("password", "")
        val remotePath = args.optString("remote_path", "").trim()
        val mode = args.optString("mode", "explicit").trim().lowercase()
        val implicit = when (mode) {
            "implicit" -> true
            "explicit" -> false
            else -> return toolErr("mode 仅支持 explicit(默认)/implicit")
        }
        val passive = args.optBoolean("passive", true)
        if (action != "list" && remotePath.isBlank()) return toolErr("上传/下载必须提供 remote_path")

        val client = try {
            val c = org.apache.commons.net.ftp.FTPSClient(implicit)
            c.connectTimeout = 30_000
            c.defaultTimeout = 30_000
            c.setDataTimeout(java.time.Duration.ofSeconds(60))
            c.connect(host, port)
            c.execPBSZ(0)
            c.execPROT("P")
            if (passive) c.enterLocalPassiveMode()
            val loginOk = if (user == "anonymous") c.login("anonymous", "") else c.login(user, password)
            if (!loginOk) { runCatching { c.disconnect() }; return toolErr("登录失败:用户名或密码错误") }
            c
        } catch (e: Exception) {
            return toolErr("连接/登录失败:${e.message}")
        }

        return try {
            when (action) {
                "list" -> {
                    val target = remotePath.ifBlank { "." }
                    val files = client.listFiles(target)
                    val arr = org.json.JSONArray()
                    files.forEach { f ->
                        arr.put(JSONObject()
                            .put("name", f.name)
                            .put("size", f.size)
                            .put("type", if (f.isDirectory) "dir" else if (f.isFile) "file" else "unknown")
                            .put("timestamp", f.timestamp?.time?.time ?: 0))
                    }
                    runCatching { client.logout() }; runCatching { client.disconnect() }
                    toolOk(JSONObject()
                        .put("action", "list").put("host", host).put("remote_path", target)
                        .put("count", files.size).put("files", arr))
                }
                "upload" -> {
                    val rel = resolveAgentPath(ctx, userId, root, args.optString("local_path", ""))
                    if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) { runCatching { client.disconnect() }; return toolErr("非法 local_path") }
                    val f = sandboxFile(root, rel)
                    if (!f.exists() || !f.isFile) { runCatching { client.disconnect() }; return toolErr("本地文件不存在:$rel") }
                    val ok = f.inputStream().use { client.storeFile(remotePath, it) }
                    runCatching { client.logout() }; runCatching { client.disconnect() }
                    if (ok) toolOk(JSONObject().put("action", "upload").put("host", host)
                        .put("local_path", rel).put("remote_path", remotePath).put("bytes", f.length()))
                    else toolErr("上传失败(服务器拒绝或路径无效):${client.replyString?.trim() ?: ""}")
                }
                "download" -> {
                    val rel = resolveAgentPath(ctx, userId, root, args.optString("local_path", ""))
                    if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) { runCatching { client.disconnect() }; return toolErr("非法 local_path") }
                    val f = sandboxFile(root, rel)
                    f.parentFile?.mkdirs()
                    val ok = f.outputStream().use { client.retrieveFile(remotePath, it) }
                    runCatching { client.logout() }; runCatching { client.disconnect() }
                    if (ok) toolOk(JSONObject().put("action", "download").put("host", host)
                        .put("remote_path", remotePath).put("local_path", rel).put("bytes", f.length()))
                    else toolErr("下载失败(文件不存在或权限不足):${client.replyString?.trim() ?: ""}")
                }
                else -> { runCatching { client.disconnect() }; toolErr("未知 action") }
            }
        } catch (e: Exception) {
            runCatching { client.logout() }; runCatching { client.disconnect() }
            toolErr("传输异常:${e.message}")
        }
    }

    /** delayed_task:后台异步延迟执行一段 Lua 脚本(内嵌 LuaJ,无需依赖)。task_id 可后续扩展取消/查询。 */
    private fun doDelayedTask(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = argsOf(call)
        val delaySec = args.optLong("delay", 0).toInt()
        val code = args.optString("code", "").trim()
        val taskId = args.optString("task_id", "").ifBlank { "task_${System.currentTimeMillis()}" }
        val timeout = args.optLong("timeout", 60).coerceIn(5, 300).toInt()
        if (delaySec !in 1..86400) return toolErr("delay 须在 1~86400 秒之间")
        if (code.isBlank()) return toolErr("缺少 code")
        appScope.launch {
            runCatching { TimeUnit.SECONDS.sleep(delaySec.toLong()) }
            val result = runCatching { doExecuteLua(ctx, userId, AgentToolCall("execute_lua", "", "",
                JSONObject().put("code", code).put("timeout", timeout).toString())) }.getOrElse {
                toolErr("延迟任务执行异常:${it.message}")
            }
            Log.i("AuroraDelayedTask", "[$taskId] 结果:$result")
        }
        return toolOk(JSONObject().put("task_id", taskId).put("delay_seconds", delaySec)
            .put("note", "任务已在后台调度;应用进程存活时才会执行,被系统杀死会丢失"))
    }

    /** work_log:查看 / 清空工作日志。日志目录存应用私有沙盒 /work_logs/,大小硬限 2MB。 */
    private fun doWorkLog(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = argsOf(call)
        val mode = args.optString("mode", "list").trim().lowercase()
        val logsDir = File(ctx.filesDir, "work_logs").apply { mkdirs() }
        val indexFile = File(logsDir, "index.json")
        // 日志总大小硬限 2MB,超了自动淘汰最旧条目
        fun enforceQuota() {
            val maxBytes = 2 * 1024 * 1024L
            var total = logsDir.listFiles()?.sumOf { it.length() } ?: 0L
            if (total > maxBytes) {
                logsDir.listFiles()?.filter { it.name != "index.json" }
                    ?.sortedBy { it.lastModified() }?.forEach { f ->
                        if (total <= maxBytes) return@forEach
                        total -= f.length(); f.delete()
                    }
            }
        }
        enforceQuota()
        // 记录本次工具调用到日志
        val entryFile = File(logsDir, "log_${System.currentTimeMillis()}.txt")
        try {
            val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) +
                "  uid=$userId  tool=${call.tool}  args=${call.argsJson.take(300)}"
            entryFile.writeText(line, Charsets.UTF_8)
        } catch (_: Exception) {}

        return when (mode) {
            "clear" -> {
                logsDir.listFiles()?.forEach { f -> if (f.name != "index.json") f.delete() }
                toolOk(JSONObject().put("cleared", true))
            }
            else -> {
                val limit = args.optLong("limit", 50).coerceIn(1, 500).toInt()
                val logs = JSONArray()
                logsDir.listFiles()?.filter { it.name.startsWith("log_") }
                    ?.sortedByDescending { it.lastModified() }?.take(limit)?.forEach { f ->
                        val content = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
                        logs.put(content)
                    }
                val totalSize = logsDir.listFiles()?.sumOf { it.length() } ?: 0L
                toolOk(JSONObject().put("mode", "list").put("total", logs.length())
                    .put("total_bytes", totalSize).put("entries", logs))
            }
        }
    }

    /** batch_replace:跨一批工作区文件做查找替换(纯字符串,非正则,避免灾难性回溯)。一次调用 N 文件。 */
    private fun doBatchReplace(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val args = argsOf(call)
        val pathsArr = args.optJSONArray("paths") ?: return toolErr("paths 必须是路径数组")
        val pattern = args.optString("pattern", "")
        val replacement = args.optString("replacement", "")
        if (pattern.isEmpty()) return toolErr("pattern 不能为空")
        val results = JSONArray()
        var totalModified = 0
        var totalChanges = 0
        for (i in 0 until pathsArr.length()) {
            val rel = pathsArr.getString(i).trim()
            if (rel.isBlank() || !isSafeRel(ctx, userId, rel)) {
                results.put(JSONObject().put("path", rel).put("ok", false).put("error", "非法路径"))
                continue
            }
            val f = sandboxFile(root, rel)
            if (!f.exists() || !f.isFile) {
                results.put(JSONObject().put("path", rel).put("ok", false).put("error", "不存在"))
                continue
            }
            if (f.name.endsWith(".zip") || f.name.endsWith(".apk") || f.name.endsWith(".png")) {
                results.put(JSONObject().put("path", rel).put("ok", false).put("error", "二进制文件跳过"))
                continue
            }
            try {
                val content = f.readText(Charsets.UTF_8)
                val newContent = content.replace(pattern, replacement)
                val changes = content.length - newContent.length + (pattern.length * (content.split(pattern).size - 1) - replacement.length * (newContent.split(replacement).size - 1))
                val count = content.split(pattern).size - 1
                if (count == 0) {
                    results.put(JSONObject().put("path", rel).put("ok", true).put("changes", 0))
                    continue
                }
                f.writeText(newContent, Charsets.UTF_8)
                totalChanges += count
                totalModified++
                results.put(JSONObject().put("path", rel).put("ok", true).put("changes", count))
            } catch (e: Exception) {
                results.put(JSONObject().put("path", rel).put("ok", false).put("error", e.message))
            }
        }
        return toolOk(JSONObject()
            .put("files_modified", totalModified)
            .put("total_replacements", totalChanges)
            .put("results", results))
    }

    /** dns_lookup:对域名做 DNS 解析,返回所有 A/AAAA 地址与耗时。 */
    private fun doDnsLookup(call: AgentToolCall): String {
        val host = argString(call, "host") ?: return toolErr("缺少 host")
        return try {
            val start = System.currentTimeMillis()
            val addrs = InetAddress.getAllByName(host)
            val ms = System.currentTimeMillis() - start
            val arr = JSONArray()
            addrs.forEach { a -> arr.put(a.hostAddress ?: a.hostName) }
            toolOk(JSONObject().put("host", host).put("ips", arr).put("count", arr.length()).put("ms", ms))
        } catch (e: Exception) { toolErr("解析失败:${e.message}") }
    }

    /** port_scan:TCP connect 扫描指定端口范围,返回开放/关闭。最多 1024 端口,单端口超时≤5s。 */
    private fun doPortScan(call: AgentToolCall): String {
        val host = argString(call, "host") ?: return toolErr("缺少 host")
        val args = argsOf(call)
        val timeout = args.optLong("timeout", 2).coerceIn(1, 5).toInt() * 1000
        val portsArg = args.optString("ports", "1-1024").ifBlank { "1-1024" }
        val ports = mutableListOf<Int>()
        runCatching {
            if (portsArg.contains('-')) {
                val (s, e) = portsArg.split('-').map { it.trim().toInt() }
                for (p in s..e) ports += p
            } else if (portsArg.startsWith('[') || portsArg.contains(',')) {
                ports.addAll(portsArg.replace("[", "").replace("]", "").split(',').map { it.trim().toInt() })
            } else {
                ports += portsArg.trim().toInt()
            }
        }.getOrElse { return toolErr("ports 格式错误,如 [22,80,443] 或 1-1024") }
        val capped = ports.filter { it in 1..65535 }.distinct().take(1024)
        if (capped.isEmpty()) return toolErr("无有效端口")
        val results = JSONArray()
        var openCount = 0
        for (p in capped) {
            val ok = runCatching {
                Socket().use { s -> s.connect(java.net.InetSocketAddress(host, p), timeout); true }
            }.getOrDefault(false)
            if (ok) openCount++
            results.put(JSONObject().put("port", p).put("open", ok)
                .put("service", wellKnownService(p)))
        }
        return toolOk(JSONObject().put("host", host).put("scanned", capped.size)
            .put("open", openCount).put("results", results))
    }
    private fun wellKnownService(p: Int): String = when (p) {
        21 -> "ftp"; 22 -> "ssh"; 23 -> "telnet"; 25 -> "smtp"; 53 -> "dns"
        80 -> "http"; 110 -> "pop3"; 143 -> "imap"; 443 -> "https"; 445 -> "smb"
        993 -> "imaps"; 995 -> "pop3s"; 3306 -> "mysql"; 3389 -> "rdp"
        5432 -> "postgres"; 6379 -> "redis"; 8080 -> "http-alt"; 8443 -> "https-alt"
        27017 -> "mongodb"; else -> ""
    }

    /** traceroute:ICMP/UDP 路由追踪。root 下发 UDP socket,非 root 用 DatagramSocket 发。 */
    private fun doTraceroute(ctx: Context, userId: Long, call: AgentToolCall): String {
        val host = argString(call, "host") ?: return toolErr("缺少 host")
        val args = argsOf(call)
        val maxHops = args.optLong("max_hops", 30).coerceIn(1, 60).toInt()
        val hopTimeout = args.optLong("timeout", 2).coerceIn(1, 5).toInt() * 1000
        val target = try { InetAddress.getByName(host) } catch (e: Exception) { return toolErr("host 无法解析:${e.message}") }
        val hops = JSONArray()
        return try {
            if (isRootAccess(ctx, userId)) {
                val cmd = "traceroute -m $maxHops -w ${hopTimeout / 1000} -n ${target.hostAddress}"
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val out = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } +
                    proc.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                proc.waitFor(15, TimeUnit.SECONDS)
                toolOk(JSONObject().put("host", host).put("ip", target.hostAddress).put("raw", out.take(5000)))
            } else {
                // 非 root:DatagramSocket 递增 TTL + UDP 探测;可能被防火墙丢弃
                val maxProbes = 3
                var unreachableAfter = -1
                for (ttl in 1..maxHops) {
                    var thisHop: JSONObject? = null
                    for (probe in 0 until maxProbes) {
                        val socket = DatagramSocket().apply { soTimeout = hopTimeout }
                        try {
                            socket.broadcast = false
                            val sendData = "AuroraTraceProbe".toByteArray()
                            socket.connect(target, 33434)
                            socket.send(DatagramPacket(sendData, sendData.size, target, 33434))
                            // 阻塞等回复(超时则空)
                            val buf = ByteArray(512)
                            val recv = DatagramPacket(buf, buf.size)
                            val start = System.currentTimeMillis()
                            runCatching { socket.receive(recv) }
                            val rtt = System.currentTimeMillis() - start
                            if (thisHop == null) thisHop = JSONObject()
                                .put("hop", ttl).put("rtt_ms", rtt).put("probe", probe + 1)
                            Thread.sleep(100)
                        } catch (_: Exception) { /* 超时=防火墙拦了,正常 */ }
                        finally { socket.close() }
                    }
                    if (thisHop == null) thisHop = JSONObject().put("hop", ttl).put("timeout", true)
                    hops.put(thisHop)
                    // 到达目标后停止
                    if (runCatching { InetAddress.getByName(host).hostAddress == target.hostAddress }.getOrDefault(false)) break
                }
                toolOk(JSONObject().put("host", host).put("ip", target.hostAddress)
                    .put("hops", hops).put("note", "非 root 模式可能被防火墙丢弃,仅能探活不保证完整"))
            }
        } catch (e: Exception) { toolErr("traceroute 失败:${e.message}") }
    }

    /** bandwidth_test:下载公开资源测速下行带宽。默认用 Cloudflare speedtest 镜像。 */
    private fun doBandwidthTest(call: AgentToolCall): String {
        val args = argsOf(call)
        val sizeMb = args.optLong("size_mb", 10).coerceIn(1, 100).toInt()
        val url = args.optString("url", "").ifBlank {
            "https://speed.cloudflare.com/__down?bytes=${sizeMb * 1024L * 1024L}"
        }
        return try {
            val u = URL(url)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 60_000
            }
            if (conn.responseCode != 200) return toolErr("测速 URL 返回 HTTP ${conn.responseCode}")
            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: (sizeMb * 1024L * 1024L)
            var downloaded = 0L
            val start = System.currentTimeMillis()
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (ins.read(buf).also { n = it } > 0) downloaded += n
            }
            val elapsedMs = maxOf(1, System.currentTimeMillis() - start)
            val mbps = downloaded * 8.0 / (elapsedMs / 1000.0) / 1_000_000.0
            toolOk(JSONObject()
                .put("bytes_downloaded", downloaded)
                .put("elapsed_ms", elapsedMs)
                .put("mbps", "%.2f".format(mbps))
                .put("server_ip", conn.url.host))
        } catch (e: Exception) { toolErr("测速失败:${e.message}") }
    }

    /** send_notification:系统通知栏发提醒。Android 13+ 需 POST_NOTIFICATIONS 权限,自动请求。 */
    private fun doSendNotification(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = argsOf(call)
        val title = args.optString("title", "").trim()
        val body = args.optString("body", "").trim()
        if (title.isBlank()) return toolErr("缺少 title")
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "aurora_ai_alerts"
        // Android 8.0+ 必须先建 channel
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(channelId)
            if (existing == null) {
                val channel = NotificationChannel(channelId, "AI 通知提醒", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "AI 工具自动发出的通知" }
                nm.createNotificationChannel(channel)
            }
        }
        val pendingIntent = runCatching {
            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            PendingIntent.getActivity(ctx, System.currentTimeMillis().toInt(), launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }.getOrNull()
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification.Builder(ctx, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(ctx)
        }
        builder.setContentTitle(title).setContentText(body.ifBlank { "(AI 提醒)" })
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true).setPriority(Notification.PRIORITY_HIGH)
        pendingIntent?.let { builder.setContentIntent(it) }
        val id = System.currentTimeMillis().toInt()
        nm.notify(id, builder.build())
        return toolOk(JSONObject().put("id", id).put("title", title).put("note", "通知已发送"))
    }

    /** apply_patch:把 unified diff 补丁应用到工作区内的目标文件。支持 strict/loose 两种模式。 */
    private fun doApplyPatch(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val args = argsOf(call)
        val filesArr = args.optJSONArray("files") ?: return toolErr("files 必须是路径数组")
        val patch = args.optString("patch", "").trim()
        val strict = args.optBoolean("strict", true)
        if (patch.isEmpty()) return toolErr("缺少 patch")
        val results = JSONArray()
        for (i in 0 until filesArr.length()) {
            val rel = filesArr.getString(i).trim()
            if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) {
                results.put(JSONObject().put("path", rel).put("ok", false).put("error", "非法路径"))
                continue
            }
            val f = sandboxFile(root, rel)
            if (!f.exists() || !f.isFile) {
                results.put(JSONObject().put("path", rel).put("ok", false).put("error", "目标文件不存在"))
                continue
            }
            try {
                val original = f.readText(Charsets.UTF_8)
                val patched = applyUnifiedDiff(original, patch, strict)
                if (patched == null) {
                    results.put(JSONObject().put("path", rel).put("ok", false)
                        .put("error", if (strict) "严格模式上下文不匹配,可设 strict=false 重试" else "补丁应用失败"))
                } else if (patched == original) {
                    results.put(JSONObject().put("path", rel).put("ok", true)
                        .put("hunks_applied", 0).put("note", "补丁已存在"))
                } else {
                    f.writeText(patched, Charsets.UTF_8)
                    results.put(JSONObject().put("path", rel).put("ok", true).put("hunks_applied", 1))
                }
            } catch (e: Exception) {
                results.put(JSONObject().put("path", rel).put("ok", false).put("error", e.message))
            }
        }
        return toolOk(JSONObject().put("results", results))
    }
    /** 简化版 unified diff 应用:解析 @@ -a,b +c,d @@ hunk,strict=true 校验上下文。 */
    private fun applyUnifiedDiff(original: String, patch: String, strict: Boolean): String? {
        val origLines = original.lines().toMutableList()
        val patchLines = patch.lines()
        if (patchLines.size < 2 || !patchLines[0].startsWith("---") || !patchLines[1].startsWith("+++")) return null
        var i = 2
        while (i < patchLines.size) {
            val line = patchLines[i]
            if (line.startsWith("@@")) {
                // 解析 @@ -a,b +c,d @@
                val m = Regex("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@").find(line) ?: return null
                val (oldStart, _, newStart, _) = m.destructured
                val oStart = oldStart.toInt() - 1; val nStart = newStart.toInt() - 1
                // 收集 hunk 内容
                i++; var idx = oStart
                val toRemove = mutableListOf<String>(); val toAdd = mutableListOf<String>(); var ctxIdx = oStart
                while (i < patchLines.size) {
                    val h = patchLines[i]
                    if (h.startsWith("@@")) break
                    when {
                        h.startsWith('-') -> { toRemove += h.substring(1); i++ }
                        h.startsWith('+') -> { toAdd += h.substring(1); i++ }
                        h.startsWith(' ') -> {
                            if (strict) {
                                val expect = h.substring(1)
                                if (idx >= origLines.size || origLines[idx] != expect) return null
                            }
                            idx++; i++
                        }
                        else -> i++
                    }
                }
                // 校验 removed 行
                if (strict) {
                    if (idx + toRemove.size > origLines.size) return null
                    for ((k, r) in toRemove.withIndex()) {
                        if (origLines[idx + k] != r) return null
                    }
                }
                // 应用:先删再加
                val removeRange = idx until (idx + toRemove.size).coerceAtMost(origLines.size)
                if (removeRange.first <= removeRange.last) origLines.subList(removeRange.first, removeRange.last + 1).clear()
                origLines.addAll(removeRange.first, toAdd)
                idx = removeRange.first + toAdd.size
            } else i++
        }
        return origLines.joinToString("\n")
    }

    /** qr_decode:识别工作区内二维码图片返回内容。用 ZXing core(用户 gradle 已引入)。 */
    private fun doQrDecode(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val rel = resolveAgentPath(ctx, userId, root, argString(call, "path") ?: "")
        if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) return toolErr("非法路径")
        val f = sandboxFile(root, rel)
        if (!f.exists() || !f.isFile) return toolErr("图片不存在:$rel")
        return try {
            val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                ?: return toolErr("无法解码图片(格式可能不支持)")
            val w = bmp.width; val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            bmp.recycle()
            val hints = java.util.Hashtable<com.google.zxing.DecodeHintType, Any>().apply {
                put(com.google.zxing.DecodeHintType.CHARACTER_SET, "UTF-8")
            }
            val rgbSrc = com.google.zxing.RGBLuminanceSource(w, h, pixels)
            val binary = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(rgbSrc))
            val result = com.google.zxing.MultiFormatReader().decode(binary, hints)
            toolOk(JSONObject().put("content", result.text).put("format", result.barcodeFormat.name))
        } catch (e: Exception) {
            if (e.message?.contains("NotFoundException", true) == true) toolErr("未识别到二维码")
            else toolErr("识别失败:${e.message}")
        }
    }

    /** crypto_encrypt:AES 256-CBC 加密。key 任意长度 SHA-256 标准化为 32 字节;IV 随机 16 字节拼在密文前。 */
    private fun doCryptoEncrypt(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val args = argsOf(call)
        val key = args.optString("key", "")
        if (key.isEmpty()) return toolErr("缺少 key")
        val destArg = args.optString("dest", "").trim()
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).copyOf(32)
        val iv = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val sourceData: ByteArray = when {
            args.has("content") && !args.isNull("content") -> args.optString("content", "").toByteArray(Charsets.UTF_8)
            args.has("path") && !args.isNull("path") -> {
                val rel = resolveAgentPath(ctx, userId, root, args.optString("path", ""))
                if (!isSafeRel(ctx, userId, rel)) return toolErr("非法 path")
                val f = sandboxFile(root, rel)
                if (!f.exists() || !f.isFile) return toolErr("源文件不存在")
                f.readBytes()
            }
            else -> return toolErr("需要 path 或 content 其一")
        }
        val encrypted = cipher.doFinal(sourceData)
        val full = iv + encrypted // 前 16 字节为 IV
        return if (destArg.isBlank()) {
            toolOk(JSONObject().put("ciphertext_base64", Base64.getEncoder().encodeToString(full))
                .put("algo", "AES-256-CBC+SHA256Key"))
        } else {
            if (!isSafeRel(ctx, userId, resolveAgentPath(ctx, userId, root, destArg)))
                return toolErr("非法 dest")
            val dest = sandboxFile(root, resolveAgentPath(ctx, userId, root, destArg))
            dest.parentFile?.mkdirs()
            dest.writeBytes(full)
            toolOk(JSONObject().put("dest", destArg).put("bytes", full.size))
        }
    }

    /** crypto_decrypt:AES 256-CBC 解密。密文前 16 字节为 IV(加密时拼上的)。 */
    private fun doCryptoDecrypt(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val args = argsOf(call)
        val key = args.optString("key", "")
        if (key.isEmpty()) return toolErr("缺少 key")
        val destArg = args.optString("dest", "").trim()
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).copyOf(32)
        val sourceData: ByteArray = when {
            args.has("content") && !args.isNull("content") -> {
                Base64.getDecoder().decode(args.optString("content", ""))
            }
            args.has("path") && !args.isNull("path") -> {
                val rel = resolveAgentPath(ctx, userId, root, args.optString("path", ""))
                if (!isSafeRel(ctx, userId, rel)) return toolErr("非法 path")
                val f = sandboxFile(root, rel)
                if (!f.exists() || !f.isFile) return toolErr("密文文件不存在")
                f.readBytes()
            }
            else -> return toolErr("需要 path 或 content 其一")
        }
        if (sourceData.size < 17) return toolErr("密文过短")
        val iv = sourceData.copyOfRange(0, 16)
        val ciphertext = sourceData.copyOfRange(16, sourceData.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val plain = cipher.doFinal(ciphertext)
        return if (destArg.isBlank()) {
            // 尝试当文本返回,失败则 base64
            val text = String(plain, Charsets.UTF_8)
            val looksText = plain.none { it == 0.toByte() }
            if (looksText) toolOk(JSONObject().put("plaintext", text))
            else toolOk(JSONObject().put("plaintext_base64", Base64.getEncoder().encodeToString(plain)))
        } else {
            if (!isSafeRel(ctx, userId, resolveAgentPath(ctx, userId, root, destArg)))
                return toolErr("非法 dest")
            val dest = sandboxFile(root, resolveAgentPath(ctx, userId, root, destArg))
            dest.parentFile?.mkdirs()
            dest.writeBytes(plain)
            toolOk(JSONObject().put("dest", destArg).put("bytes", plain.size))
        }
    }

    private fun doAppendFile(ctx: Context, userId: Long, root: File, rel: String, content: String): String {
        if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        val f = sandboxFile(root, rel)
        val oldText = if (f.exists() && f.isFile) try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" } else ""
        return try {
            if (!f.exists()) f.createNewFile()
            if (!f.isFile) return toolErr("目标不是文件:$rel")
            f.appendText(content, Charsets.UTF_8)
            recordFileChange(rel, "modified", oldText = oldText, newText = oldText + content)
            toolOk(JSONObject().put("path", rel).put("bytes", f.length()))
        } catch (e: Exception) { toolErr("追加失败:${e.message}") }
    }
    private fun doStatFile(ctx: Context, userId: Long, root: File, rel: String): String {
        if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        val f = sandboxFile(root, rel)
        if (!f.exists()) return toolErr("不存在:$rel")
        return toolOk(JSONObject()
            .put("path", rel)
            .put("name", f.name)
            .put("type", if (f.isDirectory) "dir" else "file")
            .put("size", if (f.isFile) f.length() else 0L)
            .put("modified", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(f.lastModified())))
            .put("abs", f.absolutePath))
    }
    private fun doFindFiles(ctx: Context, userId: Long, root: File, call: AgentToolCall): String {
        val keyword = (argString(call, "keyword") ?: "").trim()
        if (keyword.isBlank()) return toolErr("缺少搜索关键字")
        val baseRel = resolveAgentPath(ctx, userId, root, argString(call, "path") ?: "")
        if (!isSafeRel(ctx, userId, baseRel)) return toolErr("非法路径")
        val base = if (baseRel.isBlank()) root else sandboxFile(root, baseRel)
        if (!base.exists() || !base.isDirectory) return toolErr("目录不存在:$baseRel")
        val arr = JSONArray()
        val kw = keyword.lowercase()
        val isRoot = isRootAccess(ctx, userId)
        fun walk(d: File) {
            (d.listFiles() ?: return).forEach { f ->
                if (f.name.lowercase().contains(kw)) {
                    arr.put(JSONObject().put("path", if (isRoot) f.absolutePath else relativeOf(root, f)).put("name", f.name).put("type", if (f.isDirectory) "dir" else "file"))
                }
                if (f.isDirectory) walk(f)
            }
        }
        walk(base)
        return toolOk(JSONObject().put("keyword", keyword).put("count", arr.length()).put("items", arr))
    }
    /** 计算相对沙盒根的路径(用于 find 结果返回) */
    private fun relativeOf(root: File, f: File): String {
        val rp = root.absolutePath.trimEnd('/')
        val fp = f.absolutePath
        return if (fp.startsWith(rp)) fp.substring(rp.length).trimStart('/') else f.name
    }

    // ════════════════════════════════════════════════════════════════════
    //  备份工具：只读(list/read) + 恢复审批(restore)。备份目录 workspace_backups
    //  与工作区 ai_files 物理隔离,这些工具不经由沙盒路径解析,而是直接、严格限定在
    //  workspace_backups 内做只读访问;恢复必须经用户手动批准(见 BackupRestoreBus)。
    // ════════════════════════════════════════════════════════════════════

    /** 列出全部备份(只读):时间/范围/大小/文件数。不含备份文件内容。 */
    private suspend fun doListBackups(ctx: Context): String {
        val list = com.aurora.chat.ui.chat.listBackups(ctx)
        val arr = JSONArray()
        for (b in list) {
            arr.put(JSONObject()
                .put("name", b.dir.name)
                .put("createdAt", b.createdAt)
                .put("type", b.type)
                .put("sourceRel", b.sourceRel)
                .put("sizeBytes", b.sizeBytes)
                .put("fileCount", b.fileCount))
        }
        return toolOk(JSONObject().put("backups", arr).put("count", arr.length())
            .put("note", "这些是工作区之外的只读备份。AI 可读取其内容(list_backups/read_backup_file),但修改备份或恢复到工作区均需用户手动批准,AI 无权直接改动。"))
    }

    /**
     * 读取某个备份内的一个文件(只读)。路径严格限定在 workspace_backups/<backup>/data 内,
     * 任何 attempt 用 ".." 或绝对路径越界都会被拒绝——确保 AI 只能看备份,绝对碰不到备份之外的文件。
     */
    private suspend fun doReadBackupFile(ctx: Context, call: AgentToolCall): String {
        val args = argsOf(call)
        val name = args.optString("backup", "").trim()
        val rel = args.optString("path", "").trim()
        if (name.isEmpty() || rel.isEmpty()) return toolErr("需要 backup(备份目录名,如 backup_1690000000000) 与 path(备份内相对路径)")
        val root = com.aurora.chat.ui.chat.backupRootDir(ctx)
        val dataDir = File(File(root, name), "data")
        if (!dataDir.exists() || !dataDir.isDirectory) return toolErr("备份不存在或目录损坏:$name(可用 list_backups 查看可用备份)")
        // 标准化相对路径:禁止以 / 开头、禁止 .. 上跳,只允许向内的相对路径
        val cleanRel = rel.replace('\\', '/').trimStart('/')
        if (cleanRel.startsWith("/") || cleanRel.split('/').any { it == ".." } || cleanRel == "..")
            return toolErr("越界:只能读取备份目录内的相对路径,禁止绝对路径或 .. 上跳")
        val target = File(dataDir, cleanRel)
        val canon = try { target.canonicalPath } catch (_: Exception) { return toolErr("路径解析失败") }
        val rootCanon = try { root.canonicalPath } catch (_: Exception) { return toolErr("根目录解析失败") }
        if (!canon.startsWith(rootCanon + File.separator) && canon != rootCanon)
            return toolErr("越界:只能读取备份目录内的文件(workspace_backups 之外不可访问)")
        if (!target.exists() || !target.isFile) return toolErr("文件不存在:$cleanRel")
        val len = target.length()
        if (len > 4_000_000L) return toolErr("文件过大(${len}B,>4MB),请用更精确的 path 或让用户在备份界面查看")
        val text = runCatching { target.readText() }.getOrElse { return toolErr("读取失败(可能不是文本文件或编码异常)") }
        val truncated = if (text.length > 200_000) text.take(200_000) + "\n...[已截断,仅显示前 200000 字符]" else text
        return toolOk(JSONObject().put("path", cleanRel).put("bytes", len).put("content", truncated))
    }

    /**
     * 发起"恢复到该备份"的请求。注意:本函数【绝不直接修改任何文件】。
     * 它只把恢复意图交给 BackupRestoreBus 挂起等待用户手动批准;用户批准后由 UI 侧调用
     * BackupScreen.restoreBackup 把备份内容写回工作区(这才会侧面改动工作区),备份本身始终不被 AI 触碰。
     * 任何访问模式(手动/完全访问/root)下都必须走这层审批。
     */
    private suspend fun doRestoreBackup(ctx: Context, call: AgentToolCall): String {
        val args = argsOf(call)
        val name = args.optString("backup", "").trim()
        if (name.isEmpty()) return toolErr("缺少 backup(备份目录名,如 backup_1690000000000);可用 list_backups 查看")
        val meta = com.aurora.chat.ui.chat.listBackups(ctx).firstOrNull { it.dir.name == name }
            ?: return toolErr("未找到该备份(目录名:$name)。可用 list_backups 查看可用备份。")
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        if (!BackupRestoreBus.post(BackupRestoreBus.Request(meta = meta, deferred = deferred)))
            return toolErr("已有待批准恢复请求在进行中,请等待用户处理完再发起。")
        val approved = try { deferred.await() } catch (_: Exception) { false }
        return if (approved) {
            toolOk(JSONObject().put("approved", true)
                .put("message", "用户已批准,已将备份 ${name} 恢复到工作区。注意:这仅把备份内容写回工作区(侧面改动工作区),备份本身未被改动,AI 仍不可修改备份。"))
        } else {
            toolOk(JSONObject().put("approved", false)
                .put("message", "用户已拒绝恢复到该备份,工作区与备份均未被改动。"))
        }
    }

    /**
     * 查询设备运行环境信息:型号/厂商/Android 版本/存储/应用版本/root 状态/当前访问模式/是否已 root。
     * 用于让 AI 了解用户真实运行环境(「root 模式」下尤为适用)。
     */
    private fun doQueryEnvironment(ctx: Context, userId: Long): String {
        val info = JSONObject()
        info.put("root", isDeviceRooted())
        info.put("message", if (isDeviceRooted()) "设备已 root(管理员环境)" else "设备未 root(普通权限)")
        info.put("model", android.os.Build.MODEL)
        info.put("manufacturer", android.os.Build.MANUFACTURER)
        info.put("brand", android.os.Build.BRAND)
        info.put("device", android.os.Build.DEVICE)
        info.put("product", android.os.Build.PRODUCT)
        info.put("hardware", android.os.Build.HARDWARE)
        info.put("board", android.os.Build.BOARD)
        info.put("host", android.os.Build.HOST)
        info.put("bootloader", android.os.Build.BOOTLOADER)
        info.put("fingerprint", android.os.Build.FINGERPRINT)
        info.put("supportedAbis", JSONArray().apply { (android.os.Build.SUPPORTED_ABIS ?: emptyArray()).forEach { put(it) } })
        info.put("android_release", android.os.Build.VERSION.RELEASE)
        info.put("sdk_int", android.os.Build.VERSION.SDK_INT)
        info.put("security_patch", android.os.Build.VERSION.SECURITY_PATCH)
        info.put("incremental", android.os.Build.VERSION.INCREMENTAL)
        // 应用信息
        info.put("app_version", try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" })
        info.put("app_package", ctx.packageName)
        info.put("app_files_dir", ctx.filesDir.absolutePath)
        // 本软件身份信息：仅作背景说明，供 AI 在用户询问环境/官网/下载时参考，不作强制指令
        info.put("app_name", "Aurora Chat")
        info.put("website", "YOUR_SERVER_DOMAIN")
        info.put("download_url", "https://www.YOUR_SERVER_DOMAIN/backend/data/tools/AuroraChat.apk")
        // 存储空间
        try {
            val stat = android.os.StatFs(ctx.filesDir.absolutePath)
            val total = stat.totalBytes
            val avail = stat.availableBytes
            info.put("storage_total_bytes", total)
            info.put("storage_available_bytes", avail)
            info.put("storage_total_mb", total / 1024 / 1024)
            info.put("storage_available_mb", avail / 1024 / 1024)
        } catch (_: Exception) { info.put("storage", "unavailable") }
        // 内存
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mem = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mem)
            info.put("mem_available_bytes", mem.availMem)
            info.put("mem_total_bytes", mem.totalMem)
        } catch (_: Exception) {}
        // 访问模式 + 语义模式
        info.put("access_mode", getAccessMode(ctx, userId))
        info.put("agent_mode", getAgentMode(ctx, userId))
        info.put("workspace", agentRoot(ctx).absolutePath)
        info.put("path_policy", if (isRootAccess(ctx, userId))
            "root:文件工具支持绝对路径与 .. 上跳,可直接访问设备真实目录;相对路径仍锚定 ai_files 工作区;请尊重用户安全边界"
            else "sandbox:文件工具仅限 ai_files 工作区内相对路径,禁止绝对路径与 ..")
        return toolOk(JSONObject().put("environment", info))
    }

    /** 粗判设备是否 root:常见 su 路径存在或 Magisk(无 root 权限也可读探测) */
    fun isDeviceRooted(): Boolean {
        val suPaths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/app/Superuser.apk", "/system/bin/.ext/.su",
            "/data/local/bin/su", "/system/etc/init.d/99SuperSUDaemon",
            "/system/app/Magisk.apk", "/data/adb/magisk"
        )
        if (suPaths.any { File(it).exists() }) return true
        return try {
            android.os.Build.TAGS != null && android.os.Build.TAGS.contains("test-keys")
        } catch (_: Exception) { false }
    }

    /**
     * 执行关/重启电源命令(shutdown_device→reboot -p 关机,reboot_device→reboot 重启)。
     * 需要 root:通过 su 执行;无 root 或 su 拒绝时返回失败,交由 AI 如实向用户说明--本方法不做强限制。
     */
    private fun doPowerCommand(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            // 等待命令执行;su 不存在或无权限时进程会立即返回非 0 / 快速失败
            val exit = p.waitFor()
            if (exit == 0) toolOk(JSONObject().put("cmd", cmd).put("requested", "true"))
            else toolErr("电源命令未能执行(exit=$exit);当前环境可能不具备 root 权限,请如实告知用户")
        } catch (e: Exception) {
            toolErr("电源命令执行失败:缺少 root 权限或系统不支持(${e.message ?: e.javaClass.simpleName})")
        }
    }

    /**
     * 执行 Python 代码:优先探测设备上已安装的 Python 解释器(Termux 路径与 PATH 中的 python3/python),
     * 找到后把代码写入临时脚本,以工作区 ai_files 为工作目录执行(代码中相对路径文件落在工作区内)。
     *
     * 访问控制(与文件工具保持一致):
     * - 非 root 模式:拒绝代码中的 subprocess / os.system / os.popen 等命令执行调用,
     *   拒绝绝对路径与 ".." 上跳写法,确保 Python 无法绕过沙盒读写工作区外的文件;
     * - root 模式:不做静态限制,交由用户自行承担(与文件工具 root 模式语义一致)。
     *
     * 未检测到 Python 环境时返回失败并如实告知。超时 30 秒;stdout/stderr 过长会截断返回。
     */
    private fun doExecutePython(ctx: Context, userId: Long, call: AgentToolCall): String {
        val candidates = listOf(
            "/data/data/com.termux/files/usr/bin/python",
            "/data/data/com.termux/files/usr/bin/python3",
            "python3",
            "python"
        )
        val pythonBin = candidates.firstOrNull { bin ->
            try {
                val probe = Runtime.getRuntime().exec(arrayOf(bin, "-c", "import sys"))
                val ok = probe.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!ok) { probe.destroyForcibly(); false } else probe.exitValue() == 0
            } catch (_: Exception) { false }
        }
        if (pythonBin == null) {
            return toolErr(
                "设备上未检测到可用的 Python 解释器(已尝试 Termux 路径与 python3/python)。" +
                "若设备装有 Termux,请先安装 python(pkg install python)后重试。"
            )
        }
        val code = agentArgs(call).optString("code").ifBlank { call.content }
        if (code.isBlank()) return toolErr("缺少 Python 代码(code 参数为空)")
        // 任何访问模式都禁止 Python 代码触碰备份目录(与工作区硬隔离):代码一旦提到备份目录名或绝对路径即拒绝
        val backupCanon = File(ctx.filesDir, BACKUP_ROOT).canonicalPath
        if (code.contains(BACKUP_ROOT) || code.contains(backupCanon)) {
            return toolErr("禁止在 AI 工具中访问备份目录(workspace_backups 与工作区相互隔离,AI 不应触碰)。")
        }
        // 非 root 模式:静态拦截越界写法,防止 Python 成为绕过沙盒的后门
        // (工作目录虽锚定 ai_files,但代码内的绝对路径与 subprocess 不受工作目录约束)
        if (!isRootAccess(ctx, userId)) {
            val hit = PY_SANDBOX_DENY.firstOrNull { it.second.containsMatchIn(code) }
            if (hit != null) {
                return toolErr(
                    "当前为非 root 模式,Python 代码中禁止使用 ${hit.first}。" +
                    "相对路径文件请落在工作区(ai_files)内;如需完全访问请切换到 root 访问模式。"
                )
            }
        }
        // 缺陷B:脚本类工具执行前后局部快照 diff 补录(同 doShellExec),捕获脚本内新建又删除的盲区
        val snapBeforeScript = if (activeFileChangeRecorder != null) snapshotSandboxFiles(ctx, userId) else HashMap<String, SandboxSnap>()
        var script: java.io.File? = null
        return try {
            script = java.io.File(ctx.cacheDir, "agent_py_${System.currentTimeMillis()}_${(0..9999).random()}.py")
            script!!.writeText(code)
            val wd = agentRoot(ctx)
            val p = Runtime.getRuntime().exec(arrayOf(pythonBin, script!!.absolutePath), null, wd)
            val outSb = StringBuilder()
            val errSb = StringBuilder()
            val tOut = Thread { p.inputStream.bufferedReader().use { outSb.append(it.readText()) } }.apply { start() }
            val tErr = Thread { p.errorStream.bufferedReader().use { errSb.append(it.readText()) } }.apply { start() }
            val finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            tOut.join(2000); tErr.join(2000)
            if (!finished) {
                p.destroyForcibly()
                toolErr("Python 执行超时(30 秒),已强制终止")
            } else {
                val exit = p.exitValue()
                val out = outSb.toString().trim()
                val err = errSb.toString().trim()
                val outClip = if (out.length > 4000) out.take(4000) + "\n...(输出过长已截断,总长 ${out.length})" else out
                val errClip = if (err.length > 2000) err.take(2000) + "\n...(错误过长已截断,总长 ${err.length})" else err
                val j = JSONObject().put("exit", exit)
                if (out.isNotBlank()) j.put("stdout", outClip)
                if (err.isNotBlank()) j.put("stderr", errClip)
                if (exit == 0) toolOk(j) else toolErr("Python 执行退出码 $exit${if (err.isNotBlank()) ":$errClip" else ""}")
            }
        } catch (e: Exception) {
            toolErr("Python 执行异常:${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { script?.delete() } catch (_: Exception) {}
            // 脚本执行前后局部快照 diff 补录(缺陷B):捕获脚本内新建又删除等全量快照盲区
            mergeLocalScriptDiff(ctx, userId, snapBeforeScript)
        }
    }

    /**
     * 执行 Lua 代码(应用内嵌 LuaJ)。
     * - 优先执行 args.path 指定的 .lua 文件(相对 ai_files 工作区)
     * - 否则执行 args.code 中的内存代码
     * print() 输出通过回调收集为 stdout;脚本最终返回值作为额外字段返回。
     */
    private fun doExecuteLua(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = agentArgs(call)
        val code = args.optString("code").ifBlank { call.content }
        val relPath = args.optString("path", "").trim()
        val scriptFile = if (relPath.isNotBlank()) {
            val root = agentRoot(ctx)
            val rel = resolveAgentPath(ctx, userId, root, relPath)
            if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
            sandboxFile(root, rel).takeIf { it.isFile }
        } else null
        if (scriptFile == null && code.isBlank()) return toolErr("缺少 Lua 代码(code 参数为空且未提供有效 path)")

        val outSb = StringBuilder()
        val root = agentRoot(ctx)
        // 工作区传入 LuaRunner：io.open / os.remove 等全部被约束到这里，
        // 相对路径直接落根、绝对路径剥成文件名再拼回根，".." 上跳全移掉。
        // 缺陷B:脚本类工具执行前后局部快照 diff 补录(同 doShellExec),捕获脚本内新建又删除的盲区
        val snapBeforeScript = if (activeFileChangeRecorder != null) snapshotSandboxFiles(ctx, userId) else HashMap<String, SandboxSnap>()
        val runner = com.aurora.chat.lua.LuaRunner(workspace = root, printLine = { line ->
            outSb.appendLine(line)
        })
        return try {
            val result = if (scriptFile != null) runner.execFile(scriptFile) else runner.exec(code)
            val out = outSb.toString().trim()
            val outClip = if (out.length > 4000) out.take(4000) + "\n...(输出过长已截断,总长 ${out.length})" else out
            result.fold(
                onSuccess = { ret ->
                    val j = JSONObject().put("exit", 0)
                    if (out.isNotBlank()) j.put("stdout", outClip)
                    if (ret.isNotBlank() && ret != "nil") j.put("return", ret.take(1000))
                    toolOk(j)
                },
                onFailure = { e ->
                    toolErr("Lua 执行异常:${e.message ?: e.javaClass.simpleName}${if (out.isNotBlank()) "\n$out" else ""}")
                }
            )
        } catch (e: Exception) {
            toolErr("Lua 执行异常:${e.message ?: e.javaClass.simpleName}")
        } finally {
            // 脚本执行前后局部快照 diff 补录(缺陷B):捕获脚本内新建又删除等全量快照盲区
            mergeLocalScriptDiff(ctx, userId, snapBeforeScript)
        }
    }

    // ===== 娱乐类工具参数解析(从调用方传入的完整参数 JSON 读取;无则回退 path/content 字段) =====
    private fun agentArgs(call: AgentToolCall): JSONObject =
        try { if (call.argsJson.isNotBlank()) JSONObject(call.argsJson) else JSONObject() } catch (_: Exception) { JSONObject() }

    private fun argString(call: AgentToolCall, key: String): String? {
        val o = agentArgs(call)
        if (o.has(key) && !o.isNull(key)) return o.optString(key)
        return when (key) {
            "path" -> call.path
            "content", "text", "expr" -> call.content.ifBlank { call.path }
            else -> null
        }
    }
    private fun argLong(call: AgentToolCall, key: String, def: Long): Long =
        try { argString(call, key)?.trim()?.toLong() ?: def } catch (_: Exception) { def }
    private fun argInt(call: AgentToolCall, key: String, def: Int): Int =
        try { argString(call, key)?.trim()?.toInt() ?: def } catch (_: Exception) { def }

    /** 取出工作区文件发给用户:把它复制到 filesDir/downloads(聊天文件下载落点),返回给模型的结果含文件信息与绝对路径。
     *  asFile=true 时强制以「文件卡片」呈现(即使内容是图片/视频)。 */
    private fun doGetFile(ctx: Context, userId: Long, root: File, rel: String, asFile: Boolean = false): String {
        com.aurora.chat.ErrorReporter.debug("AI_GetFile", "get_file 调用 rel='$rel' asFile=$asFile")
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        if (rel.isBlank()) return toolErr("缺少文件名")
        val f = sandboxFile(root, rel)
        com.aurora.chat.ErrorReporter.debug(
            "AI_GetFile",
            "源文件 exists=${f.exists()} isFile=${if (f.exists()) f.isFile else false} size=${if (f.exists()) f.length() else -1} (bytes)"
        )
        if (!f.exists() || !f.isFile) return toolErr("文件不存在:$rel")
        if (f.length() > 40L * 1024 * 1024)
            return toolErr("文件过大(${formatFileSizeForTool(f.length())}),超出 40MB 限制,无法作为文件发送")
        return try {
            val downloads = File(ctx.filesDir, "downloads").apply { if (!exists()) mkdirs() }
            val out = File(downloads, f.name)
            f.inputStream().use { ins -> out.outputStream().use { ous -> ins.copyTo(ous) } }
            com.aurora.chat.ErrorReporter.debug(
                "AI_GetFile",
                "复制完成 abs='${out.absolutePath}' 存在=${out.exists()} size=${out.length()} 源size=${f.length()}"
            )
            // 媒体格式强制当媒体发（覆盖 AI 传入的 as_file=true），避免把 mp4 硬塞成文件卡片
            val ext = f.name.substringAfterLast('.', "").lowercase()
            val imgExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
            val vidExts = setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "3gpp", "mpg", "mpeg")
            val forceAsMedia = ext in imgExts || ext in vidExts
            val forceFile = if (forceAsMedia) false else asFile
            toolOk(JSONObject()
                .put("path", rel)
                .put("name", f.name)
                .put("size", f.length())
                .put("sent", true)
                .put("abs", out.absolutePath)
                .put("force_file_card", forceFile)
                .put("message", if (forceFile)
                    "文件已取出,并已作为「文件卡片」发送给用户。"
                else if (forceAsMedia)
                    "文件已取出并发送给用户:图片/视频会以对应媒体消息呈现。"
                else
                    "文件已取出并发送给用户:图片会以图片消息呈现,其它文件以文件卡片呈现。"))
        } catch (e: Exception) { com.aurora.chat.ErrorReporter.error("AI_GetFile", "取出文件失败: ${e.message}"); toolErr("取出文件失败:${e.message}") }
    }
    private fun formatFileSizeForTool(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024f)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
        else -> String.format("%.1f GB", bytes / (1024f * 1024f * 1024f))
    }

    /** generate_media 工具:按 AI 传入的 type/prompt 生成图片或视频,把媒体落盘/上传后返回 mediaUrl(UI 据此插入媒体消息)。
     *  prompt 默认直接用用户原话;仅当用户要求优化提示词、或描述太简略/不严谨时,AI 才先优化成严谨提示词再传入。 */
    private suspend fun doGenerateMedia(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val type = args.optString("type", "").trim().lowercase()
        var prompt = args.optString("prompt", "").trim()
        if (prompt.isBlank()) prompt = call.content.trim().ifBlank { call.path.trim() }
        if (type != "image" && type != "video") return toolErr("generate_media 的 type 参数必须为 image 或 video(当前:$type)")
        if (prompt.isBlank()) return toolErr("generate_media 缺少 prompt 参数(用户想生成的内容描述)")
        val keep = args.optBoolean("keep", false) // true 时把生成结果额外落工作区 ai_files,供后续 list_files/get_file 等工具处理(默认 false)
        return try {
            val size = args.optString("size", "1024x1024").trim().ifBlank { "1024x1024" }
            com.aurora.chat.ErrorReporter.debug("AiGen", "generate_media type=$type size=$size prompt=${prompt.take(160)}")
            val res = if (type == "video") generateVideo(ctx, userId, prompt) else generateImage(ctx, userId, prompt, size)
            // 与发送链路生成逻辑一致:media_path 直用 /chat-media;b64 解码;url 下载;均失败则本地落盘
            val mediaPathUrl = res.mediaPath?.takeIf { it.isNotBlank() }
            val mediaBytes: ByteArray = when {
                mediaPathUrl != null -> ByteArray(0)
                res.b64 != null -> android.util.Base64.decode(res.b64, android.util.Base64.DEFAULT)
                res.url != null -> com.aurora.chat.data.api.HttpClient.downloadBytes(res.url)
                else -> ByteArray(0)
            }
            if (mediaPathUrl == null && mediaBytes.isEmpty()) {
                throw RuntimeException("生成服务未返回数据" + (res.taskId?.let { " (任务id=$it)" } ?: ""))
            }
            val mediaUrl: String = if (mediaPathUrl != null) {
                mediaPathUrl
            } else if (mediaBytes.isEmpty()) {
                ""
            } else if (type == "video") {
                val dir = java.io.File(ctx.filesDir, "ai_chat"); if (!dir.exists()) dir.mkdirs()
                val f = java.io.File(dir, "${System.currentTimeMillis()}_${(0..9999).random()}.mp4")
                f.writeBytes(mediaBytes)
                "file://${f.absolutePath}"
            } else {
                val up = com.aurora.chat.data.api.AuroraApi.uploadChatMedia(
                    mediaBytes, "ai_gen_${System.currentTimeMillis()}_${(0..9999).random()}.jpg", "image/jpeg"
                )
                if (up.success && up.data != null) {
                    up.data.optString("url", "")
                } else {
                    val dir = java.io.File(ctx.filesDir, "ai_chat"); if (!dir.exists()) dir.mkdirs()
                    val f = java.io.File(dir, "${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
                    f.writeBytes(mediaBytes)
                    "file://${f.absolutePath}"
                }
            }
            // keep=true 时额外把生成结果落盘到工作区 ai_files,供后续工具处理(不覆盖已发出给用户的媒体)
            var keptRel: String? = null
            if (keep) {
                val ext = if (type == "video") "mp4" else "jpg"
                val keepName = "ai_gen_${System.currentTimeMillis()}_${(0..9999).random()}.$ext"
                val keepFile = java.io.File(agentRoot(ctx), keepName)
                val ok = try {
                    if (mediaPathUrl != null) {
                        // 服务端图:下载后落盘
                        val bytes = com.aurora.chat.data.api.HttpClient.downloadBytes(mediaPathUrl)
                        if (bytes.isEmpty()) false else { keepFile.writeBytes(bytes); true }
                    } else if (mediaBytes.isNotEmpty()) {
                        keepFile.writeBytes(mediaBytes); true
                    } else false
                } catch (_: Exception) { false }
                if (ok && keepFile.exists() && keepFile.length() > 0L) keptRel = keepName
            }
            toolOk(JSONObject()
                .put("type", type)
                .put("mediaUrl", mediaUrl)
                .put("prompt", prompt)
                .put("kept_in_workspace", if (keptRel != null) keptRel else JSONObject.NULL)
                .put("message", "已生成${if (type == "video") "视频" else "图片"},并作为${if (type == "video") "视频" else "图片"}消息直接发送给用户。" + if (keptRel != null) "生成结果已额外存到工作区(相对路径 $keptRel),可用工具继续处理。" else ""))
        } catch (e: Exception) {
            com.aurora.chat.ErrorReporter.warn("AiGen", "generate_media 失败: ${e.message}", e)
            toolErr("生成${if (type == "video") "视频" else "图片"}失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  虚拟主机工具实现:站点服务器文件空间,与工作区文件工具原理一致。
    //  全部复用 HostFiles 真实实现(path 为主机内相对路径,服务端已限定 B/C 级目录边界)。
    // ════════════════════════════════════════════════════════════════════
    /** 把参数里的主机相对路径解析为分段列表:空串=根目录;含 ".." 视为非法返回 null。 */
    private fun hostRel(call: AgentToolCall, key: String = "path"): List<String>? {
        val raw = (argString(call, key) ?: "").trim().replace('\\', '/').trimStart('/')
        if (raw.isEmpty()) return emptyList()
        val parts = raw.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) return null
        return parts
    }
    private fun hostLoginErr(): String =
        "未连接到虚拟主机:请先在 App 的「虚拟主机」面板用卡密登录。虚拟主机是站点服务器目录,与设备 root 模式无关,不需要 root 权限。"

    private suspend fun doHostStatus(ctx: Context, userId: Long): String = toolOk(JSONObject()
        .put("connected", HostSession.loggedIn.value)
        .put("perm", HostSession.perm.value)
        .put("in_host_view", HostSession.inHostMode.value)
        .put("note", if (HostSession.loggedIn.value)
            "已连接虚拟主机,可用 host_* 工具读写主机内容(与工作区文件工具原理一致);B/C 级仅限各自站点根目录。"
            else hostLoginErr()))

    private suspend fun doHostList(ctx: Context, call: AgentToolCall): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        val parts = hostRel(call) ?: return toolErr("非法路径:禁止使用 .. 上跳或绝对路径")
        return try {
            val items = HostFiles.list(parts)
            val arr = JSONArray()
            items.forEach { f -> arr.put(JSONObject().put("name", f.name).put("type", if (f.isDir) "dir" else "file").put("size", f.size)) }
            toolOk(JSONObject().put("path", parts.joinToString("/")).put("count", arr.length()).put("items", arr))
        } catch (e: Exception) { toolErr("列出失败:${e.message}") }
    }
    private suspend fun doHostRead(ctx: Context, call: AgentToolCall): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        val parts = hostRel(call) ?: return toolErr("非法路径")
        if (parts.isEmpty()) return toolErr("需要 path(文件相对路径)")
        return try { toolOk(JSONObject().put("path", parts.joinToString("/")).put("content", HostFiles.read(parts))) }
        catch (e: Exception) { toolErr("读取失败:${e.message}") }
    }
    private suspend fun doHostWrite(ctx: Context, call: AgentToolCall): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        val parts = hostRel(call) ?: return toolErr("非法路径")
        if (parts.isEmpty()) return toolErr("需要 path(含文件名)")
        val content = argString(call, "content") ?: call.content
        return try { HostFiles.save(parts, content); toolOk(JSONObject().put("path", parts.joinToString("/")).put("bytes", content.length)) }
        catch (e: Exception) { toolErr("写入失败:${e.message}") }
    }
    private suspend fun doHostAppend(ctx: Context, call: AgentToolCall): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        val parts = hostRel(call) ?: return toolErr("非法路径")
        if (parts.isEmpty()) return toolErr("需要 path(含文件名)")
        val add = argString(call, "content") ?: call.content
        return try {
            val old = runCatching { HostFiles.read(parts) }.getOrDefault("")
            HostFiles.save(parts, old + add)
            toolOk(JSONObject().put("path", parts.joinToString("/")))
        } catch (e: Exception) { toolErr("追加失败:${e.message}") }
    }
    private suspend fun doHostDelete(ctx: Context, call: AgentToolCall): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        val parts = hostRel(call) ?: return toolErr("非法路径")
        if (parts.isEmpty()) return toolErr("需要 path")
        return try { HostFiles.delete(parts); toolOk(JSONObject().put("path", parts.joinToString("/"))) }
        catch (e: Exception) { toolErr("删除失败:${e.message}") }
    }
    private suspend fun doHostMkdir(ctx: Context, call: AgentToolCall): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        val parts = hostRel(call) ?: return toolErr("非法路径")
        if (parts.isEmpty()) return toolErr("需要 path(含目录名)")
        val name = parts.last(); val parent = parts.dropLast(1)
        return try { HostFiles.mkdir(parent, name); toolOk(JSONObject().put("path", parts.joinToString("/"))) }
        catch (e: Exception) { toolErr("创建目录失败:${e.message}") }
    }
    private suspend fun doHostRename(ctx: Context, call: AgentToolCall): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        val parts = hostRel(call) ?: return toolErr("非法路径")
        if (parts.isEmpty()) return toolErr("需要 path")
        val newName = (argString(call, "new_name") ?: "").trim()
        if (newName.isBlank() || newName.contains('/') || newName == "..") return toolErr("非法新名字:必须为不含目录的合法名称")
        return try { HostFiles.rename(parts, newName); toolOk(JSONObject().put("path", parts.joinToString("/")).put("new_name", newName)) }
        catch (e: Exception) { toolErr("重命名失败:${e.message}") }
    }
    private suspend fun doHostCopy(ctx: Context, call: AgentToolCall): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        val src = hostRel(call, "source") ?: return toolErr("非法源路径")
        val dst = hostRel(call, "target") ?: return toolErr("非法目标路径")
        if (src.isEmpty() || dst.isEmpty()) return toolErr("需要 source 与 target")
        return try {
            val content = HostFiles.read(src)
            HostFiles.save(dst, content)
            toolOk(JSONObject().put("source", src.joinToString("/")).put("target", dst.joinToString("/")))
        } catch (e: Exception) { toolErr("复制失败(目录复制暂不支持):${e.message}") }
    }
    private suspend fun doHostStat(ctx: Context, call: AgentToolCall): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        val parts = hostRel(call) ?: return toolErr("非法路径")
        if (parts.isEmpty()) return toolErr("需要 path")
        val parent = parts.dropLast(1); val name = parts.last()
        return try {
            val item = HostFiles.list(parent).firstOrNull { it.name == name }
                ?: return toolErr("不存在:${parts.joinToString("/")}")
            toolOk(JSONObject().put("path", parts.joinToString("/")).put("name", item.name)
                .put("type", if (item.isDir) "dir" else "file").put("size", item.size)
                .put("modified", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.modifiedMillis))))
        } catch (e: Exception) { toolErr("查看失败:${e.message}") }
    }
    private suspend fun doHostFind(ctx: Context, call: AgentToolCall): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        val kw = (argString(call, "keyword") ?: "").trim().lowercase()
        if (kw.isBlank()) return toolErr("缺少搜索关键字")
        val base = hostRel(call) ?: return toolErr("非法路径")
        val arr = JSONArray(); var capped = false
        val maxItems = 200
        return try {
            suspend fun walk(p: List<String>) {
                if (capped) return
                for (f in HostFiles.list(p)) {
                    if (arr.length() >= maxItems) { capped = true; return }
                    if (f.name.lowercase().contains(kw))
                        arr.put(JSONObject().put("path", (p + f.name).joinToString("/")).put("name", f.name).put("type", if (f.isDir) "dir" else "file"))
                    if (f.isDir) walk(p + f.name)
                }
            }
            walk(base)
            toolOk(JSONObject().put("keyword", kw).put("count", arr.length()).put("items", arr).put("capped", capped))
        } catch (e: Exception) { toolErr("搜索失败:${e.message}") }
    }
    private suspend fun doHostBackupList(ctx: Context): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        return try {
            val bs = HostFiles.listBackups()
            val arr = JSONArray()
            bs.forEach { b -> arr.put(JSONObject().put("name", b.name).put("size", b.size).put("time", b.time)) }
            toolOk(JSONObject().put("backups", arr).put("count", arr.length()))
        } catch (e: Exception) { toolErr("获取备份失败:${e.message}") }
    }
    private suspend fun doHostBackupCreate(ctx: Context): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        return try { val b = HostFiles.createBackup(); toolOk(JSONObject().put("name", b.name).put("size", b.size)) }
        catch (e: Exception) { toolErr("备份失败:${e.message}") }
    }
    private suspend fun doHostBackupRestore(ctx: Context, call: AgentToolCall): String {
        if (!HostSession.loggedIn.value) return toolErr(hostLoginErr())
        val name = (argString(call, "name") ?: "").trim()
        if (name.isBlank()) return toolErr("需要 name(备份名称,先用 host_backup_list 取得)")
        return try { val n = HostFiles.restoreBackup(name); toolOk(JSONObject().put("name", name).put("restored", n)) }
        catch (e: Exception) { toolErr("恢复失败:${e.message}") }
    }

    /** 执行一个工具调用,返回结果 JSON 字符串;整个过程在 IO 调度器上运行,内部捕获异常不会抛出 */
    suspend fun executeAgentTool(ctx: Context, userId: Long, call: AgentToolCall, appActionHandler: (suspend (AppAction) -> AppActionResult)? = null): String = withContext(Dispatchers.IO) {
        try {
            val root = agentRoot(ctx)
            val rel = resolveAgentPath(ctx, userId, root, call.path)
            when (call.tool) {
                "list_files" -> doListFiles(ctx, userId, root, rel)
                "read_file" -> doReadFile(ctx, userId, root, rel)
                "write_file" -> doWriteFile(ctx, userId, root, rel, call.content)
                "delete_file" -> doDeleteFile(ctx, userId, root, rel)
                "create_folder" -> doCreateFolder(ctx, userId, root, rel)
                "delete_folder" -> doDeleteFolder(ctx, userId, root, rel)
                "rename_file" -> doRenameFile(ctx, userId, root, call)
                "copy_file" -> doCopyFile(ctx, userId, root, call)
                "append_file" -> doAppendFile(ctx, userId, root, rel, call.content)
                "stat_file" -> doStatFile(ctx, userId, root, rel)
                "find_files" -> doFindFiles(ctx, userId, root, call)
                "extract_archive" -> doExtractArchive(ctx, userId, root, call)
                "move_dir_contents" -> doMoveDirContents(ctx, userId, root, call)
                TOOL_COMPRESS_ARCHIVE -> doCompressArchive(ctx, userId, root, call)
                TOOL_ARCHIVE_LIST -> doArchiveList(ctx, userId, root, call)
                TOOL_ARCHIVE_READ -> doArchiveRead(ctx, userId, root, call)
                TOOL_KILL_PROCESS -> doKillProcess(ctx, userId, call)
                TOOL_STOP_APP -> doStopApp(ctx, userId, call)
                TOOL_UPLOAD_FILE -> doUploadFile(ctx, userId, root, call)
                TOOL_DELAYED_TASK -> doDelayedTask(ctx, userId, call)
                TOOL_WORK_LOG -> doWorkLog(ctx, userId, call)
                TOOL_BATCH_REPLACE -> doBatchReplace(ctx, userId, root, call)
                TOOL_DNS_LOOKUP -> doDnsLookup(call)
                TOOL_PORT_SCAN -> doPortScan(call)
                TOOL_TRACEROUTE -> doTraceroute(ctx, userId, call)
                TOOL_BANDWIDTH_TEST -> doBandwidthTest(call)
                TOOL_SEND_NOTIFICATION -> doSendNotification(ctx, userId, call)
                TOOL_APPLY_PATCH -> doApplyPatch(ctx, userId, root, call)
                TOOL_QR_DECODE -> doQrDecode(ctx, userId, root, call)
                TOOL_CRYPTO_ENCRYPT -> doCryptoEncrypt(ctx, userId, root, call)
                TOOL_CRYPTO_DECRYPT -> doCryptoDecrypt(ctx, userId, root, call)
                "query_environment" -> doQueryEnvironment(ctx, userId)
                // 虚拟主机工具(站点服务器文件空间,底层复用 HostFiles 真实实现)
                "host_status" -> doHostStatus(ctx, userId)
                "host_list" -> doHostList(ctx, call)
                "host_read" -> doHostRead(ctx, call)
                "host_write" -> doHostWrite(ctx, call)
                "host_append" -> doHostAppend(ctx, call)
                "host_delete_file" -> doHostDelete(ctx, call)
                "host_create_folder" -> doHostMkdir(ctx, call)
                "host_delete_folder" -> doHostDelete(ctx, call)
                "host_rename" -> doHostRename(ctx, call)
                "host_copy" -> doHostCopy(ctx, call)
                "host_stat" -> doHostStat(ctx, call)
                "host_find" -> doHostFind(ctx, call)
                "host_backup_list" -> doHostBackupList(ctx)
                "host_backup_create" -> doHostBackupCreate(ctx)
                "host_backup_restore" -> doHostBackupRestore(ctx, call)
                "shutdown_device" -> doPowerCommand("reboot -p")
                "reboot_device" -> doPowerCommand("reboot")
                "get_file" -> doGetFile(ctx, userId, root, rel, argsOf(call).optBoolean("as_file", false))
                "generate_media" -> doGenerateMedia(ctx, userId, call)
                TOOL_EXECUTE_PYTHON -> doExecutePython(ctx, userId, call)
                TOOL_EXECUTE_LUA -> doExecuteLua(ctx, userId, call)
                TOOL_FETCH_URL -> doFetchUrl(ctx, userId, call)
                TOOL_SHELL_EXEC -> doShellExec(ctx, userId, call)
                TOOL_RUN_ADB -> doRunAdb(ctx, userId, call)
                TOOL_HTTP_REQUEST -> doHttpRequest(ctx, userId, call)
                TOOL_PROCESS_LIST -> doProcessList(ctx, userId, call)
                TOOL_PING -> doPing(ctx, userId, call)
                TOOL_SEARCH_WEB -> doSearchWeb(ctx, userId, call)
                TOOL_AGENT_REVIEW -> doAgentReview(ctx, userId, call)
                TOOL_AGENT_PLAN -> doAgentPlan(ctx, userId, call)
                TOOL_AGENT_FORK -> doAgentFork(ctx, userId, call)
                TOOL_ASK_USER -> doAskUser(ctx, userId, call)
                TOOL_PLUGIN_SUBMIT -> doPluginSubmit(ctx, call)
                TOOL_PLUGIN_LIST -> doPluginList(ctx, userId, call)
                TOOL_PLUGIN_CALL -> doPluginCall(ctx, userId, call)
                TOOL_GIT -> doGit(ctx, userId, call)
                TOOL_DIFF -> doDiff(ctx, userId, call)
        TOOL_DOWNLOAD_FILE -> doDownloadFile(ctx, userId, call)
        TOOL_INSTALL_APK -> doInstallApk(ctx, userId, call)
                TOOL_FTP_TRANSFER -> doFtpTransfer(ctx, userId, root, call)
                TOOL_SWITCH_TAB -> doSwitchTab(call, appActionHandler)
                TOOL_SEND_MESSAGE -> doSendMessage(call)
                TOOL_RECALL_MESSAGE -> doRecallMessage(call)
                TOOL_LIST_RECENT_MESSAGES -> doListRecentMessages(userId, call)
                TOOL_SEND_FRIEND_REQUEST -> doSendFriendRequest(call)
                TOOL_CREATE_POST -> doCreatePost(call)
                TOOL_LIST_CONTACTS -> doListMyContacts()
                TOOL_LIST_GROUPS -> doListMyGroups()
                TOOL_JSON_PROCESS -> doJsonProcess(call)
                TOOL_BATCH_LOOP -> doBatchLoop(ctx, userId, call, appActionHandler)
                TOOL_LIST_BACKUPS -> doListBackups(ctx)
                TOOL_READ_BACKUP -> doReadBackupFile(ctx, call)
                TOOL_RESTORE_BACKUP -> doRestoreBackup(ctx, call)
                TOOL_REGEX_TEST -> doRegexTest(call)
                TOOL_ENCODE_CONVERT -> doEncodeConvert(call)
                TOOL_HASH_DIGEST -> doHashDigest(call)
                TOOL_TIMESTAMP_CONVERT -> doTimestampConvert(call)
                TOOL_TEXT_STATS -> doTextStats(call)
                TOOL_SIZE_CONVERT -> doSizeConvert(call)
                TOOL_TAKE_SCREENSHOT -> doTakeScreenshot(ctx, userId, call)
                TOOL_CAPTURE_SCREEN -> doCaptureScreen(ctx, userId, call)
                TOOL_OPEN_APP -> doOpenApp(ctx, call)
                TOOL_EXIT_APP -> doExitApp(ctx, call)
                TOOL_UNINSTALL_APP -> doUninstallApp(ctx, userId, call)
                TOOL_WAIT -> doWait(call)
                TOOL_KEEP_ALIVE -> doKeepAlive(ctx, call)
                TOOL_PHONE_ACCESS -> doPhoneAccess(ctx)
                TOOL_PHONE_SCREEN -> doPhoneScreen(call)
                TOOL_PHONE_TAP -> doPhoneTap(call)
                TOOL_PHONE_SWIPE -> doPhoneSwipe(call)
                TOOL_PHONE_TYPE -> doPhoneType(call)
                TOOL_PHONE_KEY -> doPhoneKey(call)
                "set_agent_mode" -> {
                    val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                    val mode = args.optString("mode", "").trim().lowercase()
                    when (mode) {
                        MODE_ASK, MODE_CRAFT, MODE_PLAN -> {
                            setAgentMode(ctx, userId, mode)
                            toolOk(JSONObject().put("mode", mode).put("message", "已切换为 $mode 模式"))
                        }
                        else -> toolErr("非法模式:$mode(可选 ask / craft / plan)")
                    }
                }
                else -> toolErr("未知工具:${call.tool}(可用:list_files/read_file/write_file/delete_file/create_folder/delete_folder/rename_file/copy_file/append_file/stat_file/find_files/query_environment/shutdown_device/reboot_device/get_file/execute_python/fetch_webpage/shell_exec/run_adb/http_request/process_list/ping_host/search_web/git_command/diff_text/download_file/install_apk/switch_tab/send_message/send_friend_request/create_community_post/list_my_contacts/list_my_groups/batch_loop/json_process/regex_test/encode_convert/hash_digest/timestamp_convert/text_stats/size_convert/take_screenshot/capture_screen/open_app/exit_app/uninstall_app/wait/keep_alive/set_agent_mode/ftp_transfer)")
            }
        } catch (e: Exception) {
            toolErr("执行异常:${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ===== App 内部业务工具实现 =====
    private fun argsOf(call: AgentToolCall): JSONObject =
        try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }

    /** batch_loop 内禁止批量调用的工具:有会话/验收副作用,批量执行没有意义甚至有害 */
    private val BATCH_FORBIDDEN_TOOLS = setOf(
        TOOL_BATCH_LOOP, TOOL_AGENT_REVIEW, TOOL_AGENT_FORK, TOOL_AGENT_PLAN, TOOL_ASK_USER
    )

    /**
     * batch_loop:批量循环执行同一个工具 N 次。
     * args 模板里任意字符串值的 {i} 会被替换为当前循环序号(支持 {i:03d} 补零),
     * 每次迭代在内部构造 AgentToolCall 递归走 executeAgentTool——外层只占 1 个工具轮次。
     * 连续失败 5 次自动中止;stop_on_error=true 时首次失败即停。结果逐条回报(超 200 条只留前 200 条明细)。
     */
    private suspend fun doBatchLoop(
        ctx: Context, userId: Long, call: AgentToolCall,
        appActionHandler: (suspend (AppAction) -> AppActionResult)?
    ): String {
        val args = argsOf(call)
        val innerTool = args.optString("tool", "").trim()
        val count = args.optInt("count", 0)
        val start = args.optInt("start", 1).coerceAtLeast(0)
        val intervalMs = args.optInt("interval_ms", 0).coerceIn(0, 10_000)
        val stopOnError = args.optBoolean("stop_on_error", false)
        // 模板兼容两种传法:真正的 JSON 对象,或对象字符串
        val template: JSONObject? = when (val raw = args.opt("args")) {
            is JSONObject -> raw
            is String -> try { JSONObject(raw) } catch (_: Exception) { null }
            else -> null
        }
        if (innerTool.isBlank()) return toolErr("缺少 tool:要重复调用的工具名(如 write_file / send_message)")
        if (innerTool in BATCH_FORBIDDEN_TOOLS) return toolErr("工具 $innerTool 不允许批量调用(嵌套/交互类工具批量执行没有意义)")
        if (count < 1 || count > MAX_BATCH_LOOP_COUNT) return toolErr("count 必须在 1~$MAX_BATCH_LOOP_COUNT 之间")
        if (template == null || template.length() == 0) {
            return toolErr("缺少 args:内层工具参数模板(JSON 对象,字符串值里可用 {i} 占位符)")
        }

        // {i} 与 {i:03d} 占位符:替换为当前序号(补零按冒号后的位数)
        val idxRegex = Regex("\\{i(?::0(\\d+)d)?\\}")
        fun render(v: Any?, i: Int): Any? = when (v) {
            is String -> idxRegex.replace(v) { m ->
                val pad = m.groupValues[1]
                if (pad.isEmpty()) i.toString() else i.toString().padStart(pad.length, '0')
            }
            is JSONObject -> {
                val o = JSONObject(); val ks = v.keys()
                while (ks.hasNext()) { val k = ks.next(); o.put(k, render(v.get(k), i)) }; o
            }
            is org.json.JSONArray -> {
                val a = org.json.JSONArray()
                for (j in 0 until v.length()) a.put(render(v.get(j), i)); a
            }
            else -> v
        }

        val items = JSONArray()
        var success = 0; var fail = 0; var consecutiveFails = 0
        var aborted = false; var abortReason = ""; var firstError = ""
        for (idx in start until start + count) {
            val rendered = render(template, idx) as JSONObject
            val innerPath = rendered.optString("path", "")
            val innerContent = rendered.optString("content", "")
            val innerArgs = JSONObject()
            val ks = rendered.keys()
            while (ks.hasNext()) { val k = ks.next(); if (k != "path" && k != "content") innerArgs.put(k, rendered.get(k)) }
            val innerCall = AgentToolCall(innerTool, innerPath, innerContent, innerArgs.toString())
            val result = executeAgentTool(ctx, userId, innerCall, appActionHandler)
            val ok = try { JSONObject(result).optBoolean("ok", false) } catch (_: Exception) { false }
            if (ok) { success++; consecutiveFails = 0 } else {
                fail++; consecutiveFails++
                if (firstError.isEmpty()) {
                    firstError = (try { JSONObject(result).optString("error", "") } catch (_: Exception) { "" })
                        .takeIf { it.isNotEmpty() } ?: result.take(150)
                }
                if (stopOnError) { aborted = true; abortReason = "stop_on_error=true,首次失败即停止(剩余未执行)" }
                else if (consecutiveFails >= 5) { aborted = true; abortReason = "连续失败 5 次,自动中止(剩余未执行)" }
            }
            if (items.length() < 200) {
                items.put(JSONObject().put("i", idx).put("ok", ok)
                    .put("result", if (result.length > 160) result.take(160) + "…" else result))
            }
            if (aborted) break
            if (intervalMs > 0) kotlinx.coroutines.delay(intervalMs.toLong())
        }
        val summary = JSONObject()
            .put("tool", innerTool)
            .put("requested", count)
            .put("executed", success + fail)
            .put("success", success)
            .put("fail", fail)
        if (aborted) { summary.put("aborted", true).put("abort_reason", abortReason) }
        if (firstError.isNotEmpty()) summary.put("first_error", firstError)
        summary.put("items", items)
        return toolOk(summary)
    }

    /** 把板块 tab 的歧义写法(中文/别名/英文)归一化为标准枚举;无法识别返回 null。降低 AI 传参难度。 */
    fun normalizeTab(raw: String): String? = when (raw.trim().lowercase()) {
        "chat", "message", "消息", "聊天", "私聊", "会话" -> "chat"
        "contacts", "contact", "friend", "friends", "联系人", "好友", "通讯录" -> "contacts"
        "community", "社区", "广场", "帖子", "动态", "discover" -> "community"
        "profile", "me", "mine", "我的", "我", "个人", "设置" -> "profile"
        else -> null
    }

    /** 切换主界面底部板块:需上抛 UI 层(执行 on 主线程改 currentTab 并关闭 AI 对话层)。 */
    private suspend fun doSwitchTab(call: AgentToolCall, appActionHandler: (suspend (AppAction) -> AppActionResult)?): String {
        if (appActionHandler == null) return toolErr("未配置 UI 动作处理器,无法切换板块")
        val args = argsOf(call)
        val normalized = normalizeTab(args.optString("tab", ""))
            ?: return toolErr("无法识别的板块:${args.optString("tab", "")}(可用英文 chat/contacts/community/profile 或中文 聊天/联系人/社区/我的)")
        // 把归一化后的标准 tab 写回 args,保证 MainActivity 映射能精确匹配
        args.put("tab", normalized)
        return try {
            val r = appActionHandler(AppAction(AppActionType.SWITCH_TAB, args))
            if (r.ok) toolOk(JSONObject().put("tab", r.extra?.optString("tab") ?: normalized).put("message", r.message))
            else toolErr(r.message)
        } catch (e: Exception) {
            toolErr("切换板块失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 打开(跳转)设备上已安装的其它应用。
     * target 支持包名(含「.」)或应用名称:先按包名精确解析,失败则用应用名在已安装应用里模糊匹配。
     * 匹配到多个同名应用时不擅自选一个,而是返回候选包名让用户确认。
     */
    private suspend fun doOpenApp(ctx: Context, call: AgentToolCall): String {
        val target = argsOf(call).optString("target", "").trim()
        if (target.isEmpty()) return toolErr("缺少 target:请提供要打开的应用包名(如 com.tencent.mm)或应用名称(如 微信)")
        val pm = ctx.packageManager
        fun labelOf(pkg: String): String = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        // 1) 看起来像包名:直接按包名解析
        var pkg: String? = null
        if (target.contains('.') && !target.contains(' ')) {
            pkg = try { if (pm.getLaunchIntentForPackage(target) != null) target else null } catch (_: Exception) { null }
            if (pkg == null) return toolErr("未找到包名为 $target 的应用,请确认包名是否正确(可用包名如 com.tencent.mm)")
        }
        // 2) 否则按应用名称匹配已安装应用(应用清单权限已在 Manifest 声明)
        if (pkg == null) {
            val q = target.lowercase()
            val apps = try { pm.getInstalledApplications(0) } catch (_: Exception) { emptyList() }
            val candidates = apps.filter { ai ->
                try {
                    val l = pm.getApplicationLabel(ai).toString().lowercase()
                    (l == q || l.contains(q)) && pm.getLaunchIntentForPackage(ai.packageName) != null
                } catch (_: Exception) { false }
            }
            if (candidates.isEmpty()) {
                return toolErr("未找到名称为「$target」的应用,请确认应用名,或直接告诉我包名(如 com.tencent.mm)")
            }
            if (candidates.size > 1) {
                val exact = candidates.filter { try { pm.getApplicationLabel(it).toString().lowercase() == q } catch (_: Exception) { false } }
                if (exact.size != 1) {
                    val list = candidates.take(8).joinToString("、") { "${labelOf(it.packageName)}(${it.packageName})" }
                    return toolErr("「$target」匹配到多个应用,请用包名精确指定:$list")
                }
                pkg = exact.first().packageName
            } else {
                pkg = candidates.first().packageName
            }
        }
        return try {
            val intent = pm.getLaunchIntentForPackage(pkg)
                ?: return toolErr("应用 $pkg 没有可启动的界面")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            toolOk(JSONObject().put("package", pkg).put("app_name", labelOf(pkg)).put("message", "已打开「${labelOf(pkg)}」"))
        } catch (e: Exception) {
            toolErr("打开应用失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 退出当前应用:mode=background(默认)只退到后台、进程保留;mode=kill 才彻底结束进程。 */
    private suspend fun doExitApp(ctx: Context, call: AgentToolCall): String {
        val mode = argsOf(call).optString("mode", "").trim().lowercase()
        val kill = mode in setOf("kill", "exit", "close", "force", "彻底", "关闭", "结束", "杀掉")
        if (!kill) {
            // 只离开前台:退到后台,进程/长连接/后台任务全部保留(等价于用户按 Home 键)
            val moved = withContext(Dispatchers.Main) {
                val a = ctx as? android.app.Activity
                if (a != null) { a.moveTaskToBack(true); true } else false
            }
            if (!moved) {
                withContext(Dispatchers.Main) {
                    try {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_MAIN)
                                .addCategory(android.content.Intent.CATEGORY_HOME)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {}
                }
            }
            return toolOk(JSONObject()
                .put("mode", "background")
                .put("message", "已把应用退到后台,进程保留(消息推送、后台任务不受影响)"))
        }
        return try {
            withContext(Dispatchers.Main) { (ctx as? android.app.Activity)?.finishAffinity() }
            Thread {
                try { Thread.sleep(1500) } catch (_: Exception) {}
                android.os.Process.killProcess(android.os.Process.myPid())
            }.start()
            toolOk(JSONObject().put("mode", "kill").put("message", "正在彻底退出 Aurora Chat"))
        } catch (e: Exception) {
            toolErr("退出失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 卸载应用（本应用或任意第三方应用）。
     *
     * **必须有 target。** target 会被解析成确切的包名，后续所有动作只针对这个包名——
     * 早期版本没有 target，把包名写死成 ctx.packageName，结果「卸载微信」也会把
     * Aurora Chat 自己删掉。那个事故不能再复现，所以这里 target 为空时**直接报错返回**，
     * 不做任何兜底猜测，更不会退化成「卸载本应用」。
     *
     * - mode=system（默认）：唤起系统卸载器，用户自己点「确定」。兼容性最好，不需要无障碍。
     * - mode=auto：全程自动，用户不介入。root 访问模式下对第三方应用会优先尝试
     *   `pm uninstall` 静默卸载（不弹任何框）；否则走无障碍点确认框。
     *
     * 任何一步失败都如实返回原因，绝不谎报成功。
     */
    private suspend fun doUninstallApp(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = argsOf(call)
        // 兼容几种常见写法；但**任何一个为空都不会落到「本应用」上**
        val rawTarget = args.optString("target", "")
            .ifBlank { args.optString("app", "") }
            .ifBlank { args.optString("name", "") }
            .ifBlank { args.optString("package", "") }
            .trim()
        if (rawTarget.isEmpty()) {
            return toolErr("缺少 target：请说明要卸载哪个应用（应用名如 微信，或包名如 com.tencent.mm）。" +
                "若要卸载本应用本身，请显式传 target=自己。本工具不会在 target 缺失时默认卸载本应用。")
        }

        val mode = args.optString("mode", "").trim().lowercase()
        val auto = mode in setOf("auto", "accessibility", "a11y", "全自动", "无障碍", "自动")
        if (mode.isNotBlank() && !auto &&
            mode !in setOf("system", "manual", "user", "系统", "手动", "让用户")) {
            return toolErr("uninstall_app 的 mode 只能是 system(默认,用户自己确认) 或 auto(无障碍全程自动),当前:$mode")
        }

        val selfPkg = ctx.packageName

        // 解析目标 → 唯一确定的包名
        val matches = com.aurora.chat.PhoneControl.resolveInstalledApps(ctx, rawTarget)
        if (matches.isEmpty()) {
            return toolErr("手机上没找到「$rawTarget」这个应用，无法卸载。请确认应用名是否正确，" +
                "或直接提供包名（如 com.tencent.mm）。若用户想卸载的是本应用，请用 target=自己。")
        }
        if (matches.size > 1) {
            val list = matches.take(8).joinToString("、") { "${it.label}(${it.packageName})" }
            return toolErr("「$rawTarget」匹配到多个应用，请用包名精确指定：$list")
        }
        val target = matches.first()
        val pkg = target.packageName
        val isSelf = pkg == selfPkg
        com.aurora.chat.ErrorReporter.debug(
            "AI_Uninstall",
            "target='$rawTarget' → pkg=$pkg label=${target.label} isSelf=$isSelf " +
                "systemApp=${target.isSystemApp} mode=${mode.ifBlank { "system" }}"
        )

        // 系统预装应用：静默卸载可能把手机搞坏，默认拦下，需要 force 才放行
        val force = args.optBoolean("force", false)
        if (auto && target.isSystemApp && !force) {
            return toolErr("「${target.label}」是系统预装应用，静默卸载它可能导致手机功能异常。" +
                "如用户确实要卸载，请先明确告知风险，再由用户确认后用 force=true 重试。")
        }

        if (!auto) {
            // 方式二：唤起系统卸载器，由用户点最后一下
            val launch = com.aurora.chat.PhoneControl.openSystemUninstaller(ctx, pkg)
            if (launch.error != null) {
                com.aurora.chat.ErrorReporter.warn("AI_Uninstall", "唤起系统卸载器失败 pkg=$pkg: ${launch.error}")
                return toolErr(launch.error)
            }
            // verified=false 时说明无障碍没连上、我们无法探测卸载器是否真的到了前台，
            // 此时如实措辞，不拍胸脯说「已弹出」。
            val body = if (launch.verified) {
                "「${target.label}」的系统卸载确认框已弹出，请用户在屏幕上点「确定」完成卸载。"
            } else {
                "已尝试为「${target.label}」唤起系统卸载框，但当前无法自动确认它是否显示。" +
                    "请用户看一眼屏幕：若出现「要卸载此应用吗」就点「确定」；若没有出现，请手动到「设置 → 应用管理」里卸载。"
            }
            return toolOk(JSONObject()
                .put("mode", "system")
                .put("package", pkg)
                .put("app_name", target.label)
                .put("is_self", isSelf)
                .put("launch_verified", launch.verified)
                .put("message", if (isSelf) body + "本应用的所有本地数据会一并清除。" else body))
        }

        // 方式一：全程自动
        // 第三方应用 + root 访问模式 → 优先静默卸载（不弹框，且必定针对目标包名）
        if (!isSelf && isRootAccess(ctx, userId)) {
            val r = com.aurora.chat.PhoneControl.uninstallByRoot(ctx, pkg)
            if (r == null) {
                com.aurora.chat.ErrorReporter.debug("AI_Uninstall", "root 静默卸载成功 pkg=$pkg")
                return toolOk(JSONObject()
                    .put("mode", "auto")
                    .put("via", "root")
                    .put("package", pkg)
                    .put("app_name", target.label)
                    .put("is_self", false)
                    .put("message", "已通过 root 静默卸载「${target.label}」，屏幕上不会出现任何确认框。"))
            }
            com.aurora.chat.ErrorReporter.warn("AI_Uninstall", "root 静默卸载失败 pkg=$pkg: $r，改用无障碍")
        }

        // 无障碍自动完成。
        // 注意：这里用 awaitReady 而不是 isReady——刚重启/刚重装时服务可能还没连上，
        // 直接判「未开启」会把用户白白甩去设置页（他明明开着，只会觉得莫名其妙）。
        if (!com.aurora.chat.PhoneControl.awaitReady(3000L)) {
            // 关键：不要把「服务没连上」说成「无障碍未开启」——那是两件事。
            // 系统里其实开着、只是服务还没绑定回来时，说「未开启」就是在误导用户。
            val enabledInSystem = com.aurora.chat.PhoneControl.isEnabledInSystem(ctx)
            val why = if (enabledInSystem)
                "无障碍已在系统里开启，但服务还没连接上（刚重启/刚重装时服务正在重新绑定）。"
            else
                "无障碍服务未开启。"
            // 降级为系统卸载器，让用户至少能手动完成（目标仍然是 pkg，不会变成自己）
            val launch = com.aurora.chat.PhoneControl.openSystemUninstaller(ctx, pkg)
            if (launch.error == null) {
                return toolErr("$why 已改为尝试弹出「${target.label}」的系统卸载框：" +
                    "请用户点屏幕上的「确定」完成卸载；若没看到弹框，等几秒后让我重试一次。")
            }
            return toolErr("$why 唤起系统卸载器也失败了：${launch.error}")
        }
        val reason = com.aurora.chat.PhoneControl.uninstallByAccessibility(ctx, pkg, target.label)
        if (reason == null) {
            com.aurora.chat.ErrorReporter.debug("AI_Uninstall", "无障碍自动卸载已触发 pkg=$pkg")
            return toolOk(JSONObject()
                .put("mode", "auto")
                .put("via", "accessibility")
                .put("package", pkg)
                .put("app_name", target.label)
                .put("is_self", isSelf)
                .put("message", if (isSelf)
                    "已通过无障碍完成自动卸载（回桌面→长按图标→卸载→确定），本应用正在被移除。"
                else
                    "已通过无障碍完成对「${target.label}」的卸载。"))
        }
        com.aurora.chat.ErrorReporter.warn("AI_Uninstall", "无障碍自动卸载未完成 pkg=$pkg: $reason")
        return toolErr("自动卸载「${target.label}」未完成：$reason")
    }

    /** 延迟等待:等到指定时间后再继续。单次封顶 MAX_WAIT_SECONDS 秒,更久由 AI 分多次调用。 */
    private suspend fun doWait(call: AgentToolCall): String {
        val args = argsOf(call)
        val seconds = args.optLong("seconds", 0L)
        val minutes = args.optDouble("minutes", 0.0)
        val requested = if (seconds > 0) seconds else (minutes * 60).toLong()
        if (requested <= 0) return toolErr("请提供大于 0 的 seconds 或 minutes")
        val actual = requested.coerceAtMost(MAX_WAIT_SECONDS)
        return try {
            kotlinx.coroutines.delay(actual * 1000L)
            toolOk(JSONObject()
                .put("waited_seconds", actual)
                .put("requested_seconds", requested)
                .put("capped", actual < requested)
                .put("message", "已等待 $actual 秒" + if (actual < requested) "(单次上限 ${MAX_WAIT_SECONDS} 秒,剩余时间请再次调用 wait)" else ""))
        } catch (e: Exception) {
            toolErr("等待被中断:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 后台保活:挂/撤悬浮窗(可见小窗 / 隐形窗);可选同时把应用退到后台。 */
    private suspend fun doKeepAlive(ctx: Context, call: AgentToolCall): String {
        val args = argsOf(call)
        val on = if (args.has("on")) args.optBoolean("on", true) else true
        if (!on) {
            com.aurora.chat.AiKeepAliveService.stop(ctx)
            return toolOk(JSONObject().put("keep_alive", false).put("message", "已关闭后台保活"))
        }
        // 默认用可见小窗:可见窗口优先级最高、保活最稳;invisible 仅在用户明确要求时使用
        val mode = args.optString("mode", "").trim().lowercase()
        val visible = mode != com.aurora.chat.AiKeepAliveService.MODE_INVISIBLE
        // 位置:position 预设锚点,或 x/y 自定义坐标(dp);都不给则不改位置
        val anchorRaw = args.optString("position", "").trim()
        val posX = args.optInt("x", -1)
        val posY = args.optInt("y", -1)
        // 小窗文案:AI 可用它说明「正在做什么」,不给则沿用/显示默认「AI 运行中」
        val textRaw = args.optString("text", "").trim()
        // 优先走「原地更新」通道:应用退到后台后 startService 会被系统拦截(Android 8+ 后台服务限制),
        // 导致 onStartCommand 不执行、小窗文案卡在上一轮。原地更新直接改 labelView,不受后台限制。
        val anchorOrNull = anchorRaw.ifBlank { null }
        val textOrNull = textRaw.ifBlank { null }
        val background = args.optBoolean("background", false)
        if (com.aurora.chat.AiKeepAliveService.isRunning()) {
            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val dispatched = com.aurora.chat.AiKeepAliveService.updateInPlace(
                anchorOrNull, posX, posY, textOrNull
            ) { ok -> deferred.complete(ok) }
            if (dispatched) {
                kotlinx.coroutines.withTimeoutOrNull(2000L) { deferred.await() }
                // 更新成功(或超时但窗口仍在)即按成功返回,不再走 startService
                if (com.aurora.chat.AiKeepAliveService.isRunning()) {
                    // 原地更新路径同样要处理 background:首次开启保活已退到后台时,
                    // 后续更新文案无需重复退后台(moveTaskToBack 幂等,调一次无副作用)
                    if (background) {
                        withContext(Dispatchers.Main) { (ctx as? android.app.Activity)?.moveTaskToBack(true) }
                    }
                    return buildKeepAliveResult(visible, backgroundRequested = background,
                        textRaw = textRaw, anchorRaw = anchorRaw, posX = posX, posY = posY, updated = true)
                }
            }
        }
        // 窗口尚未挂载:走原有 startService 路径创建
        // 悬浮窗由 AiKeepAliveService 在主线程挂载(协程 IO 线程没有 Looper,不能直接 addView)
        val err = com.aurora.chat.AiKeepAliveService.start(
            ctx, visible, anchorOrNull, posX, posY, textOrNull
        )
        if (err != null) {
            if (!com.aurora.chat.AiKeepAliveService.hasOverlayPermission(ctx)) {
                com.aurora.chat.AiKeepAliveService.requestOverlayPermission(ctx)
                return toolErr("$err。已为你打开系统设置页,请开启「显示在其他应用上层」后再次调用 keep_alive。")
            }
            return toolErr(err)
        }
        // background 已在函数开头解析,此处直接复用
        if (background) {
            withContext(Dispatchers.Main) { (ctx as? android.app.Activity)?.moveTaskToBack(true) }
        }
        return buildKeepAliveResult(visible, backgroundRequested = background,
            textRaw = textRaw, anchorRaw = anchorRaw, posX = posX, posY = posY, updated = false)
    }

    /** 组装 keep_alive 成功回执（创建与原地更新共用，保证返回字段一致） */
    private fun buildKeepAliveResult(
        visible: Boolean,
        backgroundRequested: Boolean,
        textRaw: String,
        anchorRaw: String,
        posX: Int,
        posY: Int,
        updated: Boolean
    ): String {
        val modeUsed = if (visible) com.aurora.chat.AiKeepAliveService.MODE_VISIBLE else com.aurora.chat.AiKeepAliveService.MODE_INVISIBLE
        // 位置描述(仅可见小窗有意义)
        val posDesc = when {
            posX >= 0 || posY >= 0 -> "位置:(${if (posX >= 0) posX else "默认"}dp, ${if (posY >= 0) posY else "默认"}dp)"
            anchorRaw.isNotBlank() -> "位置:" + com.aurora.chat.AiKeepAliveService.anchorLabel(
                com.aurora.chat.AiKeepAliveService.normalizeAnchor(anchorRaw) ?: com.aurora.chat.AiKeepAliveService.ANCHOR_TOP_RIGHT
            )
            else -> ""
        }
        val shownText = if (textRaw.isNotBlank()) textRaw else com.aurora.chat.AiKeepAliveService.DEFAULT_TEXT
        return toolOk(JSONObject()
            .put("keep_alive", true)
            .put("mode", modeUsed)
            .put("updated", updated)
            .put("background", backgroundRequested)
            .put("text", shownText)
            .put("message", (if (updated) "已更新小窗文案为「$shownText」" else "已开启后台保活(" + (if (visible) "可见小窗「$shownText」" else "隐形窗") + ")") +
                    (if (posDesc.isNotEmpty()) "," + posDesc else "") +
                    if (backgroundRequested) ",应用已退到后台" else ""))
    }

    // ===== 无障碍操控手机类工具实现 =====

    /**
     * 无障碍权限未就绪时的统一处理。
     *
     * **关键：必须区分「系统里没开」和「开了、只是服务还没连上」。**
     * 前者才跳系统设置页；后者只是进程刚启动 / 服务刚被重新绑定（冷启动、崩溃重启、
     * **重装后第一次使用**都会遇到），等一会儿就好。此前不加区分一律跳设置页，
     * 用户明明已经开好了却被反复甩去设置页，非常莫名其妙。
     */
    private suspend fun phoneNotReady(ctx: Context): String {
        // 先给它一点时间连上来——这一步能消化掉绝大多数「明明开了却没就绪」
        if (com.aurora.chat.PhoneControl.awaitReady(2500L)) {
            return toolErr("无障碍服务刚刚连接成功，请直接重试刚才的操作（这次不会再跳设置页了）。")
        }
        if (com.aurora.chat.PhoneControl.isEnabledInSystem(ctx)) {
            // 系统开关是开着的，只是服务实例还没连上 → 绝不跳设置页
            com.aurora.chat.ErrorReporter.warn("A11y", "系统已启用本无障碍服务，但服务尚未连接（不跳设置页）")
            return toolErr(
                "系统「无障碍」里「Aurora Chat」是已开启状态，但服务还没连接上来" +
                    "（常见于刚重启、刚重装、或系统刚回收过服务）。" +
                    "请让用户把本应用切到后台再切回前台，或稍等几秒后直接重试——" +
                    "**不要去系统设置里重复关闭再开启**，那不会解决问题。"
            )
        }
        com.aurora.chat.PhoneControl.openSettings(ctx)
        return toolErr(
            "${com.aurora.chat.PhoneControl.NOT_READY}。已为用户打开系统「无障碍」设置页:" +
                    "请让用户在列表里找到「Aurora Chat」并开启,开启后再次调用即可直接执行。"
        )
    }

    private suspend fun doPhoneAccess(ctx: Context): String {
        val ready = com.aurora.chat.PhoneControl.isReady()
        if (ready) {
            return toolOk(JSONObject().put("ready", true).put("message", "无障碍服务已开启,可以直接操控手机"))
        }
        return phoneNotReady(ctx)
    }

    private suspend fun doPhoneScreen(call: AgentToolCall): String {
        if (!com.aurora.chat.PhoneControl.isReady()) return toolErr(com.aurora.chat.PhoneControl.NOT_READY)
        val args = argsOf(call)
        val maxNodes = args.optInt("max_nodes", 80).coerceIn(10, 200)
        // 默认等待界面稳定：点击/跳转后目标应用可能仍在启动，直接读屏会拿到上一个界面的节点树。
        // 若 AI 明确要「立刻看当前瞬间」可传 wait_stable=false。
        val waitStable = args.optBoolean("wait_stable", true)
        val raw = com.aurora.chat.PhoneControl.readScreenStable(maxNodes, waitStable)
        if (raw.isEmpty()) return toolErr("读屏失败:拿不到当前界面内容(可能是敏感页面,如支付/密码界面)")
        return toolOk(JSONObject(raw))
    }

    private suspend fun doPhoneTap(call: AgentToolCall): String {
        if (!com.aurora.chat.PhoneControl.isReady()) return toolErr(com.aurora.chat.PhoneControl.NOT_READY)
        val args = argsOf(call)
        val text = args.optString("text", "").trim()
        val long = args.optBoolean("long", false)
        if (text.isNotEmpty()) {
            val hit = com.aurora.chat.PhoneControl.tapByText(text, long)
                ?: return toolErr("没找到文字为「$text」的可点元素,请先 phone_screen 读屏确认真实文字")
            return toolOk(JSONObject().put("clicked", text).put("x", hit.optInt("x")).put("y", hit.optInt("y"))
                .put("message", (if (long) "已长按" else "已点击") + "「$text」"))
        }
        if (!args.has("x") || !args.has("y")) return toolErr("请提供 text,或同时提供 x / y 坐标")
        val x = args.optInt("x", -1)
        val y = args.optInt("y", -1)
        if (x < 0 || y < 0) return toolErr("坐标非法:x=$x, y=$y")
        if (!com.aurora.chat.PhoneControl.tap(x, y, long)) return toolErr("点击失败:手势未被系统接受")
        return toolOk(JSONObject().put("x", x).put("y", y).put("message", (if (long) "已长按" else "已点击") + "($x, $y)"))
    }

    private suspend fun doPhoneSwipe(call: AgentToolCall): String {
        if (!com.aurora.chat.PhoneControl.isReady()) return toolErr(com.aurora.chat.PhoneControl.NOT_READY)
        val args = argsOf(call)
        val x1 = args.optInt("from_x", -1)
        val y1 = args.optInt("from_y", -1)
        val x2 = args.optInt("to_x", -1)
        val y2 = args.optInt("to_y", -1)
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return toolErr("请提供 from_x / from_y / to_x / to_y 四个坐标")
        val dur = args.optInt("duration", 300).coerceIn(50, 3000)
        if (!com.aurora.chat.PhoneControl.swipe(x1, y1, x2, y2, dur)) return toolErr("滑动失败:手势未被系统接受")
        return toolOk(JSONObject().put("message", "已从($x1, $y1)滑动到($x2, $y2),耗时 ${dur}ms"))
    }

    private suspend fun doPhoneType(call: AgentToolCall): String {
        if (!com.aurora.chat.PhoneControl.isReady()) return toolErr(com.aurora.chat.PhoneControl.NOT_READY)
        val text = argsOf(call).optString("text", "")
        if (text.isEmpty()) return toolErr("请提供要写入的 text")
        val hit = com.aurora.chat.PhoneControl.setText(text)
            ?: return toolErr("没有找到可写入的输入框:请先用 phone_tap 点一下目标输入框,再调用本工具")
        return toolOk(JSONObject().put("length", text.length)
            .put("message", "已写入 ${text.length} 个字符到输入框(${hit.optInt("x")}, ${hit.optInt("y")})"))
    }

    private suspend fun doPhoneKey(call: AgentToolCall): String {
        if (!com.aurora.chat.PhoneControl.isReady()) return toolErr(com.aurora.chat.PhoneControl.NOT_READY)
        val key = argsOf(call).optString("key", "").trim()
        if (key.isEmpty()) return toolErr("请提供 key(back / home / recents / notifications / quick_settings)")
        if (!com.aurora.chat.PhoneControl.globalAction(key)) return toolErr("按键「$key」无效或未被系统接受")
        return toolOk(JSONObject().put("key", key).put("message", "已发送按键:$key"))
    }

    /** 以当前用户身份给好友(私聊)或群(群聊)发消息。 */
    private suspend fun doSendMessage(call: AgentToolCall): String {
        val args = argsOf(call)
        val targetType = args.optString("target_type", "").trim().lowercase()
        val targetId = args.optLong("target_id", 0)
        val content = args.optString("content", "").trim()
        if (targetType !in setOf("friend", "group")) return toolErr("target_type 只能是 friend 或 group")
        if (targetId == 0L) return toolErr("target_id 非法")
        if (content.isEmpty()) return toolErr("content 不能为空")
        return try {
            val res = if (targetType == "group") AuroraApi.sendGroupMessage(targetId, content)
                      else AuroraApi.sendMessage(targetId, content)
            if (res.success) toolOk(JSONObject().put("target_type", targetType).put("target_id", targetId)
                .put("message_id", res.data ?: 0L).put("message", "已${if (targetType == "group") "给群(ID=$targetId)" else "给用户(ID=$targetId)"}发送消息"))
            else toolErr(res.message)
        } catch (e: Exception) {
            toolErr("发送失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 撤回消息:权限规则由服务端 /api/messages/recall 强制执行(与手动撤回完全同一条链路):
     * - 开发者(ID=1):可撤回任何人的消息,不限时间;
     * - 普通用户:只能撤回自己发的消息,且发出后 2 分钟内,超时服务端返回「消息已超过2分钟,无法撤回」。
     * 客户端不做二次判定,直接透传服务端结果,避免双端规则漂移。
     */
    /** 查看会话最近消息(含 message_id):撤回前定位目标消息的主要手段。
     *  服务端 /api/messages 按 id 升序 LIMIT/OFFSET 分页(从最旧开始),故拉大窗口后取尾部 count 条。 */
    private suspend fun doListRecentMessages(userId: Long, call: AgentToolCall): String {
        val args = argsOf(call)
        val targetType = args.optString("target_type", "friend").trim().lowercase()
        val targetId = args.optLong("target_id", 0)
        val count = args.optInt("count", 20).coerceIn(1, 50)
        val keyword = args.optString("keyword", "").trim()
        if (targetId == 0L) return toolErr("target_id 缺失:私聊传好友用户 ID(正数),群聊传群会话 ID(负数,如官方群 -1001)")
        val msgs = try {
            when (targetType) {
                "group" -> {
                    if (targetId >= 0) return toolErr("群聊的 target_id 必须为负数群会话 ID(如官方群 -1001)")
                    AuroraApi.getGroupMessages(targetId, limit = 500, offset = 0)
                }
                "friend", "user" -> {
                    if (targetId < 0) return toolErr("私聊的 target_id 必须为好友用户 ID(正数)")
                    if (targetId == userId) return toolErr("target_id 是你自己:私聊查的是「你和好友」的对话,请传好友的用户 ID")
                    AuroraApi.getMessages(userId, targetId, limit = 500, offset = 0)
                }
                else -> return toolErr("target_type 必须为 friend(私聊)或 group(群聊)")
            }
        } catch (e: Exception) {
            return toolErr("获取消息失败:${e.message ?: e.javaClass.simpleName}")
        }
        if (!msgs.success) return toolErr("获取消息失败:${msgs.message}")
        val fetched = (msgs.data ?: emptyList())
        if (fetched.isEmpty()) return toolOk(JSONObject().put("messages", JSONArray()).put("count", 0).put("message", "该会话还没有消息记录"))
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        // 关键词过滤(在最近窗口内),再按 id 升序取尾部 count 条 = 最近的 count 条
        val hit = if (keyword.isEmpty()) fetched else fetched.filter { it.content.contains(keyword, true) }
        val recent = hit.sortedBy { it.id }.takeLast(count)
        val arr = JSONArray()
        for (m in recent) {
            val o = JSONObject()
                .put("message_id", m.id)
                .put("from", if (m.fromUserName.isNotBlank()) m.fromUserName else "用户${m.fromUserId}")
                .put("from_id", m.fromUserId)
                .put("time", fmt.format(java.util.Date(m.createdAt * 1000)))
                .put("content", if (m.content.length > 200) m.content.take(200) + "…" else m.content)
            if (m.isRevoked == 1) o.put("revoked", true)
            if (m.mediaType.isNotBlank()) o.put("media_type", m.mediaType)
            arr.put(o)
        }
        return toolOk(JSONObject()
            .put("messages", arr)
            .put("count", arr.length())
            .put("message", "共返回 ${arr.length()} 条(时间从旧到新)。要撤回某条时把它的 message_id 传给 recall_message;已带 revoked=true 的是已被撤回的消息。"))
    }

    private suspend fun doRecallMessage(call: AgentToolCall): String {
        val args = argsOf(call)
        val messageId = args.optLong("message_id", 0)
        if (messageId <= 0) return toolErr("message_id 非法(须为正整数;send_message 的返回结果里带 message_id)")
        return try {
            val res = AuroraApi.recallMessage(messageId)
            if (res.success) {
                toolOk(JSONObject().put("message_id", messageId).put("message", "消息已撤回"))
            } else {
                // 服务端会返回具体原因:只能撤回自己的消息 / 消息已超过2分钟,无法撤回 / 消息已被撤回 等
                toolErr(res.message)
            }
        } catch (e: Exception) {
            toolErr("撤回失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 按用户 ID 发好友申请:现有接口按 email,故先用 getUserInfo 解析出邮箱再发送。 */
    private suspend fun doSendFriendRequest(call: AgentToolCall): String {
        val args = argsOf(call)
        val userId = args.optLong("user_id", 0)
        if (userId <= 0) return toolErr("user_id 非法(须为正整数)")
        return try {
            val infoRes = AuroraApi.getUserInfo(userId)
            if (!infoRes.success) return toolErr("无法获取用户(ID=$userId)信息:${infoRes.message}")
            val u = infoRes.data ?: return toolErr("无法获取用户(ID=$userId)信息")
            val email = u.email.trim()
            if (email.isEmpty()) return toolErr("目标用户(ID=$userId, ${u.username})未公开邮箱,无法发送好友申请;请改用该用户的注册邮箱")
            val greeting = args.optString("greeting", "")
            val req = AuroraApi.sendFriendRequest(email, greeting)
            if (req.success) toolOk(JSONObject().put("to_user_id", userId).put("to_email", email).put("message", "已向用户(ID=$userId)发送好友申请"))
            else toolErr(req.message)
        } catch (e: Exception) {
            toolErr("好友申请失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 以当前用户身份在社区发布一条帖子。 */
    private suspend fun doCreatePost(call: AgentToolCall): String {
        val args = argsOf(call)
        val title = args.optString("title", "").trim()
        val content = args.optString("content", "").trim()
        if (title.isEmpty()) return toolErr("title 不能为空")
        if (content.isEmpty()) return toolErr("content 不能为空")
        return try {
            val res = AuroraApi.createCommunityPost(title, content, args.optString("post_type", "post"))
            if (res.success) toolOk(JSONObject().put("message", "社区帖子《$title》发布成功"))
            else toolErr(res.message)
        } catch (e: Exception) {
            toolErr("发帖失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 列出当前用户的全部好友(数量 + 每个好友的 ID/用户名/签名)。 */
    private suspend fun doListMyContacts(): String {
        return try {
            val res = AuroraApi.getFriends()
            if (!res.success) return toolErr(res.message)
            val list = res.data ?: emptyList()
            val arr = JSONArray()
            for (u in list) {
                arr.put(JSONObject().apply {
                    put("user_id", u.id)
                    put("username", u.username)
                    put("signature", u.signature)
                    put("email", u.email)
                })
            }
            toolOk(JSONObject().put("count", list.size).put("friends", arr))
        } catch (e: Exception) {
            toolErr("获取好友列表失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 列出当前用户加入的全部群聊(群会话 ID 为负数)。 */
    private suspend fun doListMyGroups(): String {
        return try {
            val res = AuroraApi.getConversations()
            if (!res.success) return toolErr(res.message)
            val all = res.data ?: emptyList()
            // 群会话 id < 0(如 -1001);私聊 id > 0
            val groups = all.filter { it.id < 0 }
            val arr = JSONArray()
            for (g in groups) {
                arr.put(JSONObject().apply {
                    put("group_conv_id", g.id)
                    put("group_name", g.username.ifEmpty { g.email })
                    put("last_message", g.lastMessage)
                    put("last_time", g.lastTime)
                })
            }
            toolOk(JSONObject().put("count", groups.size).put("groups", arr))
        } catch (e: Exception) {
            toolErr("获取群聊列表失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ===== 开发调试类工具实现 =====

    /** JSON 校验/美化/压缩/按点分路径取值。 */
    private fun doJsonProcess(call: AgentToolCall): String {
        val args = argsOf(call)
        val input = args.optString("input", "").trim()
        if (input.isEmpty()) return toolErr("input 为空")
        val action = args.optString("action", "beautify").trim().lowercase()
        return try {
            val obj: Any = try { JSONObject(input) } catch (_: Exception) { JSONArray(input) }
            when (action) {
                "minify" -> toolOk(JSONObject().put("minified", obj.toString()))
                "validate" -> toolOk(JSONObject().put("valid", true).put("type", if (obj is JSONObject) "object" else "array"))
                "get" -> {
                    val path = args.optString("path", "").trim()
                    if (path.isEmpty()) toolErr("get 模式需要提供 path")
                    else {
                        val v = resolveJsonPath(obj, path)
                        toolOk(JSONObject().put("path", path).put("value", v.toString()))
                    }
                }
                else -> toolOk(JSONObject().put("pretty", if (obj is JSONObject) obj.toString(2) else (obj as JSONArray).toString(2)))
            }
        } catch (e: Exception) {
            toolErr("JSON 非法:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 按点分路径(如 a.b.0.c)从 JSON 对象中取值。 */
    private fun resolveJsonPath(v: Any, path: String): Any {
        var cur: Any = v
        for (seg in path.split('.').filter { it.isNotEmpty() }) {
            cur = when {
                seg.toIntOrNull() != null && cur is JSONArray -> {
                    val idx = seg.toInt(); if (idx < 0 || idx >= cur.length()) throw IllegalArgumentException("索引越界:$idx") else cur.get(idx)
                }
                cur is JSONObject -> cur.opt(seg)
                else -> throw IllegalArgumentException("无法沿路径推进:$seg(当前类型 ${cur.javaClass.simpleName})")
            }
        }
        return when (cur) {
            null, org.json.JSONObject.NULL -> "<null>"
            else -> cur
        }
    }

    /** 正则测试:返回是否匹配、匹配片段与捕获组。 */
    private fun doRegexTest(call: AgentToolCall): String {
        val args = argsOf(call)
        val pattern = args.optString("pattern", "")
        val text = args.optString("text", "")
        if (pattern.isEmpty()) return toolErr("pattern 为空")
        if (text.isEmpty()) return toolErr("text 为空")
        val flags = args.optString("flags", "")
        return try {
            val opts = buildSet {
                if (flags.contains('i')) add(RegexOption.IGNORE_CASE)
                if (flags.contains('m')) add(RegexOption.MULTILINE)
                if (flags.contains('s')) add(RegexOption.DOT_MATCHES_ALL)
            }
            val regex = if (opts.isEmpty()) Regex(pattern) else Regex(pattern, opts)
            val matches = regex.findAll(text).toList()
            val arr = JSONArray()
            var total = 0
            for ((i, m) in matches.withIndex()) {
                if (i >= 50) break
                val jm = JSONObject().put("match", m.value)
                if (m.groupValues.size > 1) {
                    val g = JSONArray()
                    for (gi in 1 until m.groupValues.size) g.put(m.groupValues[gi])
                    jm.put("groups", g)
                }
                arr.put(jm)
                total++
            }
            toolOk(JSONObject()
                .put("matched", matches.isNotEmpty())
                .put("match_count", matches.size)
                .put("shown", total)
                .put("matches", arr))
        } catch (e: Exception) {
            toolErr("正则非法:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 编码 / 解码转换:base64 / hex / url。 */
    private fun doEncodeConvert(call: AgentToolCall): String {
        val args = argsOf(call)
        val action = args.optString("action", "").trim().lowercase()
        val text = args.optString("text", "")
        if (text.isEmpty()) return toolErr("text 为空")
        val inputBytes: ByteArray
        try { inputBytes = text.toByteArray(charset("UTF-8")) } catch (e: Exception) {
            return toolErr("无法编码:${e.message}")
        }
        return try {
            val out: String = when (action) {
                "base64_encode" -> Base64.getEncoder().encodeToString(inputBytes)
                "base64_decode" -> {
                    val dec = Base64.getDecoder().decode(text.trim()); String(dec, charset("UTF-8"))
                }
                "hex_encode" -> inputBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                "hex_decode" -> {
                    val s = text.replace(" ", "").replace(":", "")
                    if (s.length % 2 != 0) return toolErr("hex 长度须为偶数")
                    val bytes = ByteArray(s.length / 2)
                    for (i in bytes.indices) bytes[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                    String(bytes, charset("UTF-8"))
                }
                "url_encode" -> URLEncoder.encode(text, "UTF-8")
                "url_decode" -> URLDecoder.decode(text, "UTF-8")
                else -> return toolErr("action 非法:可用 base64_encode/base64_decode/hex_encode/hex_decode/url_encode/url_decode")
            }
            toolOk(JSONObject().put("result", out))
        } catch (e: Exception) {
            toolErr("转换失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 计算 md5 / sha1 / sha256 摘要(十六进制)。 */
    private fun doHashDigest(call: AgentToolCall): String {
        val args = argsOf(call)
        val algorithm = args.optString("algorithm", "").trim().lowercase().replace("-", "")
        val text = args.optString("text", "")
        if (text.isEmpty()) return toolErr("text 为空")
        val algo = when (algorithm) { "md5" -> "MD5"; "sha1" -> "SHA-1"; "sha256" -> "SHA-256"; else -> return toolErr("algorithm 非法:可用 md5 / sha1 / sha256") }
        return try {
            val md = MessageDigest.getInstance(algo)
            val digest = md.digest(text.toByteArray(charset("UTF-8")))
            val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            toolOk(JSONObject().put("algorithm", algorithm).put("digest", hex).put("length", hex.length))
        } catch (e: Exception) {
            toolErr("摘要计算失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Unix 时间戳与日期互转。 */
    private fun doTimestampConvert(call: AgentToolCall): String {
        val args = argsOf(call)
        val direction = args.optString("direction", "").trim().lowercase()
        val format = args.optString("format", "").trim().ifEmpty { "yyyy-MM-dd HH:mm:ss" }
        val value = args.optString("value", "").trim()
        if (value.isEmpty()) return toolErr("value 为空")
        return try {
            if (direction == "ts_to_date") {
                val raw = value.replace(",", "")
                val ts = raw.toLongOrNull() ?: return toolErr("value 不是合法时间戳: $value")
                val millis = if (raw.length >= 13) ts else ts * 1000L
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                toolOk(JSONObject().put("date", sdf.format(Date(millis))).put("epoch_millis", millis))
            } else if (direction == "date_to_ts") {
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                val date = sdf.parse(value) ?: return toolErr("无法按格式[$format]解析: $value")
                val secs = date.time / 1000
                toolOk(JSONObject().put("timestamp_seconds", secs).put("timestamp_millis", date.time))
            } else toolErr("direction 非法:可用 ts_to_date / date_to_ts")
        } catch (e: Exception) {
            toolErr("时间转换失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 文本统计分析。 */
    private fun doTextStats(call: AgentToolCall): String {
        val args = argsOf(call)
        val text = args.optString("text", "")
        if (text.isEmpty()) return toolErr("text 为空")
        val chars = text.length
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val lines = text.split('\n').size
        val bytes = text.toByteArray(charset("UTF-8")).size
        return toolOk(JSONObject().apply {
            put("chars", chars)
            put("words", words)
            put("lines", lines)
            put("bytes_utf8", bytes)
            put("has_multiline", lines > 1)
            put("is_blank", text.isBlank())
        })
    }

    /** 字节数 <-> 人眼可读大小互转。 */
    private fun doSizeConvert(call: AgentToolCall): String {
        val args = argsOf(call)
        val direction = args.optString("direction", "").trim().lowercase()
        val value = args.optString("value", "").trim()
        if (value.isEmpty()) return toolErr("value 为空")
        return try {
            if (direction == "bytes_to_human") {
                val bytes = value.replace(",", "").toLongOrNull() ?: return toolErr("value 不是合法字节数: $value")
                val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
                var v = bytes.toDouble(); var u = 0
                while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
                toolOk(JSONObject().put("human", "%.2f %s".format(v, units[u])).put("bytes", bytes))
            } else if (direction == "human_to_bytes") {
                val m = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(B|KB|MB|GB|TB|PB)?""").find(value.trim().uppercase()) ?: return toolErr("无法解析大小: $value")
                var n = m.groupValues[1].toDouble()
                val unit = m.groupValues[2]
                val mult = when (unit) { "KB" -> 1024.0; "MB" -> 1024.0 * 1024; "GB" -> 1024.0 * 1024 * 1024; "TB" -> 1024.0 * 1024 * 1024 * 1024; "PB" -> 1024.0 * 1024 * 1024 * 1024 * 1024; else -> 1.0 }
                val bytes = (n * mult).toLong()
                toolOk(JSONObject().put("bytes", bytes))
            } else toolErr("direction 非法:可用 bytes_to_human / human_to_bytes")
        } catch (e: Exception) {
            toolErr("大小转换失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 对屏幕截图并保存为 PNG 到 AI 工作区(root/adb shell screencap)。 */
    private suspend fun doTakeScreenshot(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = argsOf(call)
        val rootFile = agentRoot(ctx)
        val name = args.optString("name", "").trim().takeWhile { it.isLetterOrDigit() || it == '_' }
        val base = name.ifEmpty { "screenshot_${System.currentTimeMillis()}" }
        val target = File(rootFile, "$base.png")
        // ① 优先走无障碍截屏(Android 11+ 免 root,普通用户即可用)
        if (com.aurora.chat.PhoneControl.isReady() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val err = com.aurora.chat.PhoneControl.screenshot(target)
            if (err == null) return shotOk(rootFile, target)
        }
        // ② 退回 screencap(root / adb shell)
        val cmd = "screencap -p ${target.absolutePath}"
        return try {
            val proc = try { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)) }
            catch (_: Exception) { Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)) }
            proc.inputStream.bufferedReader().use { it.readText() }
            proc.errorStream.bufferedReader().use { it.readText() }
            proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!target.exists() || target.length() == 0L) {
                val hint = if (com.aurora.chat.PhoneControl.isReady()) ""
                else "。也可以开启无障碍服务后重试(Android 11+ 支持免 root 截屏)"
                return toolErr("截图失败:未生成有效文件(screencap 需要 root 或 adb shell 权限)$hint")
            }
            shotOk(rootFile, target)
        } catch (e: Exception) {
            toolErr("截图异常:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 截屏成功后的统一回执(工作区相对路径 + 大小) */
    private fun shotOk(rootFile: File, target: File): String {
        val rel = try {
            target.absolutePath.removePrefix(rootFile.absolutePath.trimEnd('/') + "/")
        } catch (_: Exception) { target.name }
        return toolOk(JSONObject().apply {
            put("path", rel)
            put("file_name", target.name)
            put("size_bytes", target.length())
            put("size_human", "%.2f KB".format(target.length() / 1024.0))
        })
    }

    /**
     * 捕捉屏幕内容并交付给用户。
     *
     * 只有两种明确的交付方式(deliver 参数):
     *  - "image"(默认):强制把截到的图**作为图片消息直接发给用户**,与 generate_media 生成的图片
     *    走完全相同的渲染链路,用户能立刻在聊天里看到并点开大图。
     *  - "file":作为**文件卡片**发给用户(适合画面很长、需要下载原文件)。
     *
     * 另可传 keep=true 额外在工作区留一份副本,供后续 list_files / get_file 继续处理;
     * 默认 false —— 交给用户的文件一律落在沙盒外目录(ai_chat_media / downloads),
     * 避免被 AI 自己的清理操作误删。
     *
     * 截屏优先级:无障碍截屏(Android 11+ 免 root)→ screencap(root / adb shell)。
     * 发图用 JPEG(默认 85 画质)压体积,发文件保留 PNG 原图。
     */
    private suspend fun doCaptureScreen(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = argsOf(call)
        // 兼容旧参数名:mode/as_file 仍可用,但语义收敛为「发图 / 发文件」两种
        val deliverRaw = args.optString("deliver", "").trim().lowercase()
        val modeRaw = args.optString("mode", "").trim().lowercase()
        val asFileFlag = args.optBoolean("as_file", false)
        val asFile = when {
            deliverRaw == "file" -> true
            deliverRaw == "image" -> false
            deliverRaw.isNotBlank() -> return toolErr("capture_screen 的 deliver 只能是 image 或 file(当前:$deliverRaw)")
            else -> asFileFlag                 // 未传 deliver 时,回退到旧的 as_file / mode 兼容
        }
        // keep=true 时额外在工作区留一份原图;旧 mode=save/both 也映射到 keep
        val keep = args.optBoolean("keep", false) || modeRaw == "save" || modeRaw == "both"
        val quality = args.optInt("quality", 85).coerceIn(1, 100)
        val rawName = args.optString("name", "").trim().takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
        val base = rawName.ifEmpty { "screenshot_${System.currentTimeMillis()}" }
        val root = agentRoot(ctx)

        // 交付前需先等界面稳定,避免截到过渡态(如刚点击后的黑屏/旧界面)
        if (com.aurora.chat.PhoneControl.isReady()) {
            runCatching { com.aurora.chat.PhoneControl.waitForStableScreen(quietMs = 250L, timeoutMs = 1500L) }
        }

        // ① 无障碍截屏(免 root)。发图场景用 JPEG 压体积,发文件保留 PNG 原图
        val fmt = if (!asFile) android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG
        val ext = if (!asFile) "jpg" else "png"
        val target = File(root, "$base.$ext")
        var shotErr: String? = null
        if (com.aurora.chat.PhoneControl.isReady() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            shotErr = com.aurora.chat.PhoneControl.screenshot(target, fmt, if (!asFile) quality else 100)
        } else {
            shotErr = "无障碍服务未就绪"
        }

        // ② 退回 screencap(root / adb shell)
        if (shotErr != null) {
            val pngTarget = File(root, "$base.png")
            val cmd = "screencap -p ${pngTarget.absolutePath}"
            val viaShell = try {
                val proc = try { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)) }
                catch (_: Exception) { Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)) }
                proc.inputStream.bufferedReader().use { it.readText() }
                proc.errorStream.bufferedReader().use { it.readText() }
                proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                pngTarget.exists() && pngTarget.length() > 0L
            } catch (_: Exception) { false }
            if (viaShell) {
                // screencap 只能出 PNG;发图时转成 JPEG 压体积
                if (!asFile) {
                    val jpg = File(root, "$base.jpg")
                    val converted = withContext(Dispatchers.IO) {
                        runCatching {
                            val bmp = android.graphics.BitmapFactory.decodeFile(pngTarget.absolutePath)
                            jpg.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }
                            bmp.recycle()
                            jpg.exists() && jpg.length() > 0L
                        }.getOrDefault(false)
                    }
                    return if (converted) {
                        if (!keep) runCatching { pngTarget.delete() }
                        deliverCapture(ctx, root, jpg, keep, asFile, targetName = jpg.name)
                    } else {
                        // 转码失败就退而用 PNG 发送(体积大但能用)
                        deliverCapture(ctx, root, pngTarget, keep, asFile, targetName = pngTarget.name)
                    }
                } else {
                    return deliverCapture(ctx, root, pngTarget, keep, asFile, targetName = pngTarget.name)
                }
            }
            val hint = if (com.aurora.chat.PhoneControl.isReady()) ""
            else "。也可以开启无障碍服务后重试(Android 11+ 支持免 root 截屏)"
            return toolErr("截屏失败:${shotErr}${if (shotErr == "无障碍服务未就绪") "" else ","}$hint".replace(",,", ","))
        }

        return deliverCapture(ctx, root, target, keep, asFile, targetName = target.name)
    }

    /**
     * 统一交付:把截屏结果复制到最终消费目录,并把可直接使用的 URL 写进结果 JSON。
     *
     * 设计要点(重要):复制动作在**这里**完成,失败时明确返回错误;上层
     * emitCaptureDelivery() 只做「读字段 → 回调 UI」,不做任何复制或校验,
     * 因此不存在"中间某步静默失败导致聊天里什么都没发生"的情况。
     *
     * - asFile=false → 复制到 filesDir/ai_chat_media/(沙盒外,AI 碰不到),作为图片消息交付
     * - asFile=true  → 复制到 filesDir/downloads/(聊天文件落点),作为文件卡片交付
     * - keep=true    → 额外在工作区保留原文件副本,供后续工具处理
     */
    private suspend fun deliverCapture(
        ctx: Context,
        root: File,
        file: File,
        keep: Boolean,
        asFile: Boolean,
        targetName: String
    ): String {
        if (!file.exists() || file.length() == 0L) return toolErr("截屏文件无效:${file.absolutePath}")
        val rel = try {
            file.absolutePath.removePrefix(root.absolutePath.trimEnd('/') + "/")
        } catch (_: Exception) { file.name }
        val sizeHuman = "%.2f KB".format(file.length() / 1024.0)

        // ===== 交付准备:在工具内部就把文件复制到最终目录,拿到可直接渲染的 URL =====
        var deliveredUrl = ""
        var deliveredName = targetName
        val destDir = if (asFile) {
            File(ctx.filesDir, "downloads")
        } else {
            File(ctx.filesDir, "ai_chat_media")   // 沙盒外的图片落点,AI 无法访问/删除
        }
        if (!destDir.exists()) destDir.mkdirs()
        val dst = try {
            withContext(Dispatchers.IO) {
                val baseName = file.name.substringBeforeLast('.', file.name).ifBlank { "shot" }
                val extName = file.name.substringAfterLast('.', if (asFile) "png" else "jpg").ifBlank { "jpg" }
                // 文件名加时间戳:避免同名覆盖导致旧消息卡片指向新文件
                val stamp = System.currentTimeMillis()
                val candidate = if (asFile) {
                    File(destDir, if (File(destDir, "$baseName.$extName").exists()) "${baseName}_$stamp.$extName"
                                     else "$baseName.$extName")
                } else {
                    File(destDir, "cap_${stamp}_$baseName.$extName")
                }
                file.inputStream().use { ins -> candidate.outputStream().use { ous -> ins.copyTo(ous) } }
                if (candidate.isFile && candidate.length() > 0L) candidate else null
            }
        } catch (e: Exception) {
            com.aurora.chat.ErrorReporter.error("AI_Capture", "交付复制失败: ${e.message}")
            null
        }
        if (dst == null) {
            // 复制失败:明确报错,不再"假装成功"(这正是之前聊天里什么都没有的根源)
            return toolErr("截屏已成功,但交付时复制文件失败,请重试(可用模式:image / file)")
        }
        deliveredUrl = "file://${dst.absolutePath}"
        deliveredName = dst.name
        // 不需要留档时删掉工作区原件,避免 AI 后续清理把它删了导致消息失效
        if (!keep) withContext(Dispatchers.IO) { runCatching { file.delete() } }
        com.aurora.chat.ErrorReporter.debug(
            "AI_Capture", "交付文件已就绪 url='$deliveredUrl' size=${dst.length()} asFile=$asFile keep=$keep"
        )

        val msg = if (asFile) "已截屏并作为文件发送给用户" else "已截屏并作为图片发送给用户"
        return toolOk(JSONObject().apply {
            put("path", rel)
            put("file_name", deliveredName)
            put("size_bytes", dst.length())
            put("size_human", sizeHuman)
            put("delivered", if (asFile) "file" else "image")
            put("in_workspace", keep)
            // 交付 URL:上层直接读这两个字段回调 UI,不做二次处理
            put("delivered_url", deliveredUrl)
            put("delivered_is_media", !asFile)
            put("message", msg + if (!keep) "(原文件未保留在工作区)" else "")
        })
    }

    // ===== Agent 工具轮询循环(复用:普通发送 / 继续 都走此共享循环;确认发言另用内联实现) =====

    /** 单轮流式调用的结果(由 round lambda 返回) */
    data class AgentRoundResult(
        val done: Boolean,
        val fullText: String = "",
        val usage: Long = 0,
        val thinkingSeconds: Long = 0,
        val totalSeconds: Long = 0,
        /** 本轮产生的思考片段(reasoning_content),供共享循环跨轮累积为多段思考 */
        val reasoning: String = "",
        /** 上游结束原因(stop/length/tool_calls):length 表示输出被截断,编排层据此自动续写 */
        val finishReason: String = "",
        /** 本轮是否因可重试错误而失败(网络/5xx/断流),编排层据此原地重试而非终结任务 */
        val retriable: Boolean = false
    )

    /** 整个工具循环的最终结果 */
    data class AgentLoopOutcome(
        /** 多轮累积后的纯对话文本(已剥离工具块,界面最终展示用) */
        val accumulated: String,
        /** 实际执行过的工具轮数 */
        val toolRounds: Int,
        /** 是否正常收尾(未被手动停止 / 出错打断) */
        val clean: Boolean,
        val usage: Long,
        val thinkingSeconds: Long,
        val totalSeconds: Long,
        /** 本次会话 AI 改动过的文件记录(供「查看所有改动」全屏界面展示逐行 diff) */
        val fileChanges: List<FileChange> = emptyList(),
        /** 本次会话实际处理过的工具调用次数(含失败/被拒;供底部信息行展示) */
        val toolCallCount: Int = 0,
        /** 每次工具调用的明细(工具名/成败/发起时刻毫秒),供「工具调用」全屏清单展示 */
        val toolCalls: List<ChatMsg.ToolStageRecord> = emptyList()
    )

    /**
     * 单条文件改动记录:供「查看所有改动」全屏界面展示。
     * - path:沙盒内相对路径(如 notes/todo.txt);op 见 OP_* 常量
     * - fromPath:仅重命名时用(旧路径)
     * - oldText/newText:改动前后的文本内容(用于逐行 diff;超过上限会被截断,仅保留摘要)
     */
    data class FileChange(
        val path: String,
        val op: String,                 // created / modified / deleted / renamed / copied / deleted_folder
        val fromPath: String = "",
        val oldText: String = "",
        val newText: String = "",
        /** 该文件改动是否已被用户「退回」:true 后界面禁用退回按钮,并持久化回消息 JSON 防止重进丢失 */
        val reverted: Boolean = false
    ) {
        fun toJson() = JSONObject().apply {
            put("path", path); put("op", op)
            put("from", fromPath); put("old", oldText); put("new", newText)
            put("reverted", reverted)
        }
        companion object {
            fun fromJson(o: JSONObject) = FileChange(
                o.optString("path", ""), o.optString("op", "modified"),
                o.optString("from", ""), o.optString("old", ""), o.optString("new", ""),
                o.optBoolean("reverted", false)
            )
        }
    }

    /** old/new 文本截断上限(单文件):超过则仅保留开头,避免 AI 大文件改动撑爆消息 JSON */
    private const val FC_TEXT_CAP = 100_000

    /** 当前正在执行的 AI 会话的文件改动收集器(模块级,带 save/restore 支持嵌套 agent 循环)。
     *  由 runAgentChatLoop 在开始处设入、finally 还原;文件类工具成功后在内部追加记录。 */
    internal var activeFileChangeRecorder: MutableList<FileChange>? = null

    /** 追加一条文件改动记录(仅当收集器处于激活态时) */
    private fun recordFileChange(path: String, op: String, fromPath: String = "", oldText: String = "", newText: String = "") {
        val rec = activeFileChangeRecorder ?: return
        rec.add(FileChange(path, op, fromPath,
            if (oldText.length > FC_TEXT_CAP) oldText.take(FC_TEXT_CAP) + "\n…(已截断)" else oldText,
            if (newText.length > FC_TEXT_CAP) newText.take(FC_TEXT_CAP) + "\n…(已截断)" else newText))
    }

    // ===== 嵌套 agent 循环(agent_review/agent_plan/agent_fork)的改动/计数合并(缺陷A) =====
    // 每个嵌套循环内部会新建自己的 runAgentChatLoop 与文件改动收集器(fileChanges),
    // 其 fileChanges/toolCallCount/toolCalls 若不合并回外层,外层最终写回消息时
    // 只有本层自己那几次「agent_review/agent_plan/agent_fork 调用」的记录,
    // 子循环内真实发生的工具调用(读文件/执行脚本/删文件)全部丢失,导致删除类任务
    // 「查看所有改动」为空、「调用 N 个工具」计数为 0 而按钮不出现。

    /** 嵌套 agent 循环的工具计数与调用明细暂存:内层收尾时写入,外层 executeAgentTool 返回后消费合并。
     *  与 activeFileChangeRecorder 同属模块级单槽:同一时刻只有一条嵌套链在跑,顺序消费安全。 */
    private var nestedPendingCallCount = 0
    private val nestedPendingToolCalls = mutableListOf<ChatMsg.ToolStageRecord>()

    /** 嵌套 agent 循环收尾:把本层 fileChanges 合并回父层收集器,计数/明细暂存供父层消费。
     *  递归式合并:任意嵌套深度,最外层最终拿到全部子循环的改动与调用。
     *  prevRecorder 为进入本循环前保存的父层收集器(finally 会还原回它)。 */
    private fun absorbNestedLoopIntoParent(
        prevRecorder: MutableList<FileChange>?,
        fileChanges: List<FileChange>,
        toolCallCount: Int,
        toolCalls: List<ChatMsg.ToolStageRecord>
    ) {
        if (prevRecorder != null && fileChanges.isNotEmpty()) {
            val seen = HashSet<String>()
            prevRecorder.forEach { c ->
                seen.add(normRelPath(c.path) + "\u0001" + c.op + "\u0001" + normRelPath(c.fromPath))
            }
            fileChanges.forEach { c ->
                val k = normRelPath(c.path) + "\u0001" + c.op + "\u0001" + normRelPath(c.fromPath)
                if (k !in seen) { seen.add(k); prevRecorder.add(c) }
            }
        }
        // 仅嵌套子循环（存在父层收集器）才把计数/明细暂存供外层 consumeNestedToolData 消费；
        // 最外层主循环收尾时 prevRecorder == null，若也暂存会污染模块级单槽 nestedPending，
        // 下一次 executeAgentTool 的 consumeNestedToolData 会把上次任务的计数垒进本条回复
        // （「调用 N 个工具」逐次叠加、虚高到几百）。
        if (prevRecorder != null && (toolCallCount > 0 || toolCalls.isNotEmpty())) {
            nestedPendingCallCount += toolCallCount
            nestedPendingToolCalls.addAll(toolCalls)
        }
    }

    /** 外层消费嵌套暂存:executeAgentTool 返回后调用,把子循环计数/明细并入本层。 */
    private fun consumeNestedToolData(): Pair<Int, List<ChatMsg.ToolStageRecord>> {
        val c = nestedPendingCallCount
        val l = nestedPendingToolCalls.toList()
        nestedPendingCallCount = 0
        nestedPendingToolCalls.clear()
        return c to l
    }

    /** 脚本类工具(shell_exec/execute_python/execute_lua)执行前后局部快照 diff 补录(缺陷B):
     *  全量快照 diff 只能覆盖「任务开始前已存在」的文件;脚本在本轮内新建又删除的文件
     *  (mkdir+rm / write+rm / os.remove)在前后全量快照里都不存在,产生记录盲区。
     *  因此在脚本执行前后各拍一次局部快照,把脚本造成的差异立即补录进收集器;
     *  covered 防重由 mergeSandboxDiff 基于收集器已有记录保证(已记录路径不重复)。 */
    private fun mergeLocalScriptDiff(ctx: Context, userId: Long, before: Map<String, SandboxSnap>) {
        val rec = activeFileChangeRecorder ?: return
        if (before.isEmpty()) return
        mergeSandboxDiff(ctx, userId, before, rec)
    }

    // ===== 沙盒快照 diff:兜底捕获一切形式的文件改动 =====
    // 文件工具(write/delete/rename/copy/append)只覆盖"AI 规规矩矩调工具"的场景;
    // shell_exec / execute_python 等脚本路径写文件完全绕过工具记录。
    // 因此任务开始前对沙盒(ai_files)做全量快照,结束后再扫一遍做整体比对——
    // 不管 AI 用什么手段改文件,最终状态差异一律计入「查看所有改动」。

    /** 单文件快照:size/mtime 用于快速判变;text 存文本内容供 deleted/modified 的逐行 diff */
    private data class SandboxSnap(val size: Long, val mtime: Long, val text: String)

    /** 快照单文件文本上限:超过只记元数据不读内容,防大文件(媒体/压缩包)拖慢收尾 */
    private const val SNAP_TEXT_MAX = 512 * 1024L

    /** 快照/工具记录路径统一规整:去掉 "./" 前缀等噪声,保证两边比对口径一致 */
    private fun normRelPath(p: String) = p.removePrefix("./").trimStart('/')

    private fun capSnapText(t: String) = if (t.length > FC_TEXT_CAP) t.take(FC_TEXT_CAP) + "\n…(已截断)" else t

    /** 对 AI 沙盒(ai_files)做全量快照:相对路径 -> 快照。目录不存在时返回空表。 */
    private fun snapshotSandboxFiles(ctx: Context, userId: Long): HashMap<String, SandboxSnap> {
        val out = HashMap<String, SandboxSnap>()
        val root = agentRoot(ctx)
        if (!root.isDirectory) return out
        try {
            root.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                val rel = try { f.relativeTo(root).invariantSeparatorsPath } catch (_: Exception) { return@forEach }
                val len = try { f.length() } catch (_: Exception) { 0L }
                var text = ""
                if (len in 1..SNAP_TEXT_MAX) {
                    try {
                        val bytes = f.readBytes()
                        // 含 0x00 视作二进制:diff 无意义,只记元数据(卡片仍会列出该文件)
                        if (!bytes.contains(0.toByte())) text = String(bytes, Charsets.UTF_8)
                    } catch (_: Exception) {}
                }
                out[rel] = SandboxSnap(len, try { f.lastModified() } catch (_: Exception) { 0L }, text)
            }
        } catch (_: Exception) {}
        return out
    }

    /**
     * 任务结束(含中途停止)后比对快照,把【工具记录未覆盖】的真实差异补进 changes:
     * 新建 / 修改(带前后文本供逐行 diff) / 删除(带删前文本)。
     * 工具记录已覆盖的路径保留其精确语义(renamed/copied 等快照推不出来),快照不重复记。
     */
    private fun mergeSandboxDiff(ctx: Context, userId: Long, before: Map<String, SandboxSnap>, changes: MutableList<FileChange>) {
        try {
            val after = snapshotSandboxFiles(ctx, userId)
            val coveredExact = HashSet<String>()
            val coveredPrefixes = ArrayList<String>()   // delete_folder 只记目录本身,其下文件按前缀覆盖
            changes.forEach { c ->
                coveredExact.add(normRelPath(c.path))
                if (c.fromPath.isNotEmpty()) coveredExact.add(normRelPath(c.fromPath))
                if (c.op == "deleted_folder") coveredPrefixes.add(normRelPath(c.path).trimEnd('/') + "/")
            }
            fun covered(rel: String) = rel in coveredExact || coveredPrefixes.any { rel.startsWith(it) }
            // 新建 / 修改
            for ((rel, a) in after) {
                if (covered(rel)) continue
                val b = before[rel]
                when {
                    b == null -> changes.add(FileChange(rel, "created", "", "", capSnapText(a.text)))
                    b.size != a.size || b.mtime != a.mtime -> {
                        // size/mtime 变了但内容一致(原样重写)不算改动
                        if (b.text != a.text) changes.add(FileChange(rel, "modified", "", capSnapText(b.text), capSnapText(a.text)))
                    }
                }
            }
            // 删除(快照有、现在没有)
            for ((rel, b) in before) {
                if (rel in after || covered(rel)) continue
                changes.add(FileChange(rel, "deleted", "", capSnapText(b.text), ""))
            }
        } catch (_: Exception) {}
    }

    /** 把改动列表序列化为消息可持久化的 JSON 字符串(空则 "") */
    internal fun serializeFileChanges(list: List<FileChange>): String =
        if (list.isEmpty()) "" else JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    /** 从消息 JSON 字符串反序列化改动列表 */
    internal fun parseFileChanges(json: String?): List<FileChange> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<FileChange>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(FileChange.fromJson(o))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Agent 工具执行循环:反复调用 [round] 生成一轮回复,若该轮含工具调用则执行并回喂历史,
     * 直到模型不再调用工具、封顶 MAX_AGENT_TOOL_ROUNDS 或异常/手动停止。
     * @param history 调用方传入的上下文 MutableList,工具结果会就地追加(跨轮保留)。
     * @param onReasoning 多轮思考累积回调:将前面各轮思考(reasoning_content)按哨兵拼接后回传,
     *                    供调用方实时刷新思考块(分段渲染)。空实现表示不展示。传 null 则内部不累积思考。
     * @param round 执行单轮流式请求;返回该轮是否正常完成及累积文本/用量/该轮思考片段。prefix 为截至上一轮已累积的
     *              对话文本(本轮的展示前缀;首轮即空)。检测到的原生 tool_calls 会填入 nativeCalls。
     */
    suspend fun runAgentChatLoop(
        ctx: Context,
        userId: Long,
        history: MutableList<Pair<String, String>>,
        /** 多段思考累积回调:把多轮思考拼接后实时回传,供 UI 分段渲染 */
        onReasoning: ((String) -> Unit)? = null,
        /** 工具执行前的审批门控回调(挂起):返回 false 则该工具不执行,改为回喂「用户拒绝」结果。
         *  传 null 表示不审批(root 模式/完全访问直接执行)。非 root 且删改类工具由 UI 注入此回调。 */
        onNeedApproval: (suspend (tool: String, argsJson: String) -> Boolean)? = null,
        /** get_file 取出文件后回调:把已落盘到 filesDir/downloads 的文件传给 UI,由 UI 插入可打开/下载的文件卡片消息 */
        onFileSent: (suspend (AiFileSend) -> Unit)? = null,
        /** generate_media 生成成功后回调:把生成类型(image/video)与媒体地址(mediaUrl)传给 UI,由 UI 插入图片/视频消息 */
        onMediaGenerated: (suspend (type: String, mediaUrl: String) -> Unit)? = null,
        /** 工具调用动态状态回调:「名」+状态(0=调用中 1=成功 2=失败)。UI 据此显示蓝/绿/红工具状态行。 */
        onToolStage: ((Pair<String, Int>) -> Unit)? = null,
        /** App 内部业务动作回调(切板块等需 UI 协作的动作):透传给 executeAgentTool。 */
        appActionHandler: (suspend (AppAction) -> AppActionResult)? = null,
        /** 多 Agent 模式强制验收开关:执行者动了工作区却没调用 agent_review 时,注入补验指令强制再跑一轮。
         *  验收 Agent 自身的嵌套循环必须传 false,否则验收者会被要求验收自己。 */
        enforceReview: Boolean = true,
        /** 工具轮数上限覆写:验收者等嵌套循环传较小值(如 5),防验收 Agent 自己无限跑轮 */
        maxRoundsOverride: Int? = null,
        /** 实时进度回调:编排层据此更新气泡底部「任务进行中 · 第 N 轮 / 正在收尾交付」提示 */
        onProgress: ((String) -> Unit)? = null,
        /** 是否启用任务守护（软上限收尾、交付契约、兜底交付）。嵌套子循环（验收/规划/派发）传 false,避免子任务被强制交付。 */
        enableTaskCompletion: Boolean = true,
        round: suspend (nativeCalls: MutableList<AgentNativeToolCall>, prefix: String, toolChoice: String?) -> AgentRoundResult
    ): AgentLoopOutcome {
        // 长任务哨兵：正常收尾（含出错退出）会清掉标记；只有进程被系统强行掐断
        // 才会把标记留下，下次启动时据此如实告知用户「上次任务被中断了」。
        com.aurora.chat.AgentTaskGuard.markRunning(ctx, "AI 工具任务")
        // 文件改动收集器:本会话所有文件类工具的成功改动都会追加到此,供「查看所有改动」展示。
        // 声明在 try 之前,finally 里才能还原;用 save/restore 保护嵌套 agent 循环(验收/规划子循环)不互相串扰。
        val fileChanges = mutableListOf<FileChange>()
        val prevRecorder = activeFileChangeRecorder
        activeFileChangeRecorder = fileChanges
        // 任务开始前对沙盒拍全量快照:结束时比对,兜底捕获工具记录之外的一切改动
        // (shell_exec/execute_python 等脚本写文件不经过文件工具,只有快照 diff 能抓到)
        val sandboxBefore = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { snapshotSandboxFiles(ctx, userId) }
        try {
            var accumulated = ""
            var reasoningAccum = ""
            var toolRounds = 0
            // 多 Agent 强制验收跟踪:是否调用过 agent_review / 动过哪些工作区类工具 / 是否已补验过一次
            // 补验指令只在【多 Agent 模式】生效:普通模式下动了工作区不强制验收(用户没开多 Agent,
            // 就不该有「验收 Agent 接手」这种事);嵌套循环(验收者/规划者/子执行者)传 false 同样关闭。
            val enforceReviewEffective = enforceReview && execActive(ctx, userId, EXEC_MULTI_AGENT)
            var reviewCalled = false
            var reviewNudged = false
            // agent_review 已被调用次数(含被拒):配合 MAX_AGENT_REVIEW_CALLS 做编排层硬上限
            var reviewCallCount = 0
            val mutatedWorkspace = mutableSetOf<String>()
            // 思考深度决定工具轮数上限(纯编排层,任何 API/模型都生效);嵌套循环可覆写为更小值
            val maxToolRounds = maxRoundsOverride ?: maxToolRoundsFor(ctx, userId)
            var lastUsage = 0L
            var lastThinking = 0L
            var lastTotal = 0L
            // 本次会话实际处理过的工具调用次数(含失败/被拒):底部信息行「调用 N 个工具」数据源
            var toolCallCount = 0
            // 每次工具调用明细(工具名/成败/发起时刻毫秒):「工具调用」全屏清单数据源
            val toolCalls = mutableListOf<ChatMsg.ToolStageRecord>()
            // 主循环连续失败硬上限：防止 AI 在同一个坑里死磕（比如 Lua 栈溢出修之前 AI 连调 7 次都是失败）。
            // 规则：本轮所有工具调用全部失败才算一次连续失败；本轮有至少一个成功就清零。
            // 连续 3 轮全失败 → 强制 break，不再给 AI 新轮次去重试。
            var consecutiveAllFailRounds = 0
            // 任务总时长基准:从循环启动(即 AI 接收并开始响应用户请求)到结束的连续墙钟时间。
            // 之前每轮 totalSeconds 直接「覆盖」,多轮工具任务最后只记到最后一轮那一小段(断续记录的根因);
            // 现改为以循环起始时刻为基准的连续计时,工具执行、多轮等待全部计入。
            val loopStartElapsed = android.os.SystemClock.elapsedRealtime()
            // get_file 交付:优先读结果 JSON 的 abs + force_file_card(尊重 AI 的 as_file 参数);
            // JSON 不可用时退回用调用参数 path 定位工作区文件。
            suspend fun emitFileCard(toolName: String, pathRaw: String, result: String, approved: Boolean) {
                if (!approved || toolName != TOOL_GET_FILE) return
                if (onFileSent == null) return
                // ① 首选:结果里的 abs(已复制到 downloads 的落点) + force_file_card
                val obj = try { JSONObject(result) } catch (_: Exception) { null }
                if (obj != null && obj.optBoolean("ok")) {
                    val abs = obj.optString("abs", "")
                    if (abs.isNotBlank()) {
                        val f = File(abs)
                        if (f.isFile && f.length() > 0L) {
                            val force = obj.optBoolean("force_file_card", false)
                            com.aurora.chat.ErrorReporter.debug(
                                "AI_CaptureDelivery", "get_file 交付 abs=$abs forceFileCard=$force"
                            )
                            onFileSent.invoke(AiFileSend(f.name, abs, f.length(), forceFileCard = force))
                            return
                        }
                    }
                }
                // ② 兜底:按调用参数解析工作区相对路径
                val rel = resolveAgentPath(ctx, userId, agentRoot(ctx), pathRaw)
                if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) return
                val sf = sandboxFile(agentRoot(ctx), rel)
                if (!sf.isFile) return
                onFileSent.invoke(AiFileSend(sf.name, File(ctx.filesDir, "downloads/${sf.name}").absolutePath, sf.length()))
            }
            // generate_media 生成成功:把生成类型与 mediaUrl 回调给 UI,由 UI 插入图片/视频消息
            suspend fun emitMediaIfGenerated(toolName: String, result: String, approved: Boolean) {
                if (!approved || toolName != TOOL_GENERATE_MEDIA) return
                if (onMediaGenerated == null) return
                try {
                    val o = JSONObject(result)
                    if (o.optBoolean("ok")) {
                        val t = o.optString("type", "image")
                        val url = o.optString("mediaUrl", "")
                        if (url.isNotBlank()) onMediaGenerated.invoke(t, url)
                    }
                } catch (_: Exception) {}
            }
            // capture_screen 交付:直接读结果里的 delivered_url 字段回调 UI。
            // 复制与校验已在 deliverCapture 内部完成,这里零逻辑,避免中间环节静默失败。
            suspend fun emitCaptureDelivery(toolName: String, result: String, approved: Boolean) {
                if (toolName != TOOL_CAPTURE_SCREEN || !approved) return
                val o = try {
                    JSONObject(result)
                } catch (e: Exception) {
                    com.aurora.chat.ErrorReporter.error("AI_Capture", "结果不是合法 JSON: " + result.take(200))
                    return
                }
                if (!o.optBoolean("ok")) return
                val url = o.optString("delivered_url", "")
                if (url.isBlank()) return                       // save 模式:无需交付
                val isMedia = o.optBoolean("delivered_is_media", true)
                try {
                    if (isMedia) {
                        if (onMediaGenerated == null) {
                            com.aurora.chat.ErrorReporter.error("AI_Capture", "onMediaGenerated 为 null,图片无法交付")
                            return
                        }
                        com.aurora.chat.ErrorReporter.debug("AI_Capture", "回调图片消息 url=" + url)
                        onMediaGenerated.invoke("image", url)
                    } else {
                        if (onFileSent == null) {
                            com.aurora.chat.ErrorReporter.error("AI_Capture", "onFileSent 为 null,文件无法交付")
                            return
                        }
                        val abs = url.removePrefix("file://")
                        val f = File(abs)
                        com.aurora.chat.ErrorReporter.debug(
                            "AI_Capture", "回调文件卡片 url=" + url + " exists=" + f.exists() + " size=" + f.length()
                        )
                        onFileSent.invoke(AiFileSend(f.name, abs, f.length(), forceFileCard = true))
                    }
                    com.aurora.chat.ErrorReporter.debug("AI_Capture", "交付回调完成 isMedia=" + isMedia)
                } catch (e: Exception) {
                    com.aurora.chat.ErrorReporter.error(
                        "AI_Capture",
                        "交付回调抛异常: " + e.javaClass.simpleName + ": " + e.message
                    )
                }
            }

            // ===== 任务守护状态机(NORMAL → WRAP_UP → DELIVERED)=====
            // 把「能否结束」从「模型是否停止调工具」改为「是否交付」;把「轮次预算」从硬墙改为软预算。
            var phase = AgentTaskCompletion.AgentTaskPhase.NORMAL
            var wrapUpRounds = 0
            var nudgeCount = 0
            var continueCount = 0
            var retryCount = 0
            var currentToolChoice: String? = null
            var failStrategyRounds = 0   // P1:连续全失败后「换策略」补救机会计数(失败≠立即收尾)
            var wrapUpToolBudget = 0     // P1:收尾阶段剩余工具补救额度(收尾仍可少量补救,防死循环)
            var contractNeeded = enableTaskCompletion && AgentTaskCompletion.needsContract(ctx, userId, toolCallCount)

            while (true) {
                val prefix = accumulated
                val nativeCalls = mutableListOf<AgentNativeToolCall>()
                // 软上限收尾阶段:禁用工具,强制纯文本交付
                // 实时进度提示:仅任务型会话显示「任务进行中」,普通对话用中性/空提示,避免打扰
                onProgress?.invoke(
                    if (phase == AgentTaskCompletion.AgentTaskPhase.WRAP_UP) "正在收尾交付…"
                    else if (contractNeeded) "任务进行中 · 第 ${toolRounds + 1} 轮"
                    else ""
                )
                val res = round(nativeCalls, prefix, currentToolChoice)
                // 输出被上游 max_tokens 截断(finish_reason=length):自动从断点续写,最多 MAX_CONTINUE_ROUNDS 次
                if (res.finishReason == "length" && continueCount < AgentTaskCompletion.MAX_CONTINUE_ROUNDS) {
                    continueCount++
                    val tail = stripToolBlocks(res.fullText).takeLast(200)
                    history.add("user" to AgentTaskCompletion.continueInstruction(tail))
                    Log.i(TAG, "runAgentChatLoop 输出被截断,自动续写(第 $continueCount 次)")
                    continue
                }
                if (!res.done) {
                    // 可重试错误(网络/5xx/断流):原地重试,保留已完成进度,不终结任务
                    if (res.retriable && retryCount < AgentTaskCompletion.MAX_ROUND_RETRIES) {
                        retryCount++
                        Log.i(TAG, "runAgentChatLoop 单轮可重试错误,第 $retryCount 次重试")
                        continue
                    }
                    // 重试耗尽或致命错误:若曾用工具且需契约,补兜底交付,避免「任务消失」
                    if (contractNeeded && toolCallCount > 0 && !AgentTaskCompletion.hasDelivery(accumulated)) {
                        accumulated += "\n" + AgentTaskCompletion.fallbackDeliveryBlock(toolCalls, fileChanges)
                    }
                    // 手动停止 / 出错:保留已流出部分,不再执行工具、也不收尾(由调用方决定展示)。
                    // 即使中途停止,总时长也如实记到当前时刻(连续计时)。
                    // 中途停止前已发生的真实文件改动同样做快照比对,改动记录不丢
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { mergeSandboxDiff(ctx, userId, sandboxBefore, fileChanges) }
                    // 嵌套 agent 循环(验收/规划/派发):本层收尾时把改动合并回父层、计数/明细暂存
                    absorbNestedLoopIntoParent(prevRecorder, fileChanges, toolCallCount, toolCalls)
                    return AgentLoopOutcome(
                        accumulated, toolRounds, clean = false, lastUsage, lastThinking,
                        (android.os.SystemClock.elapsedRealtime() - loopStartElapsed) / 1000L,
                        fileChanges, toolCallCount, toolCalls
                    )
                }
                lastUsage = res.usage
                // 思考时长跨轮累加;总时长 = 从循环启动到当前时刻的连续墙钟时间(不是单轮覆盖)
                lastThinking += res.thinkingSeconds
                lastTotal = (android.os.SystemClock.elapsedRealtime() - loopStartElapsed) / 1000L
                // 累积本轮思考为一个思考段(跨轮拼接为多段思考)
                if (onReasoning != null && res.reasoning.isNotBlank()) {
                    reasoningAccum = appendReasoningSegment(reasoningAccum, res.reasoning)
                    onReasoning(reasoningAccum)
                }
                // 多轮累积:剥离工具块后拼接为纯对话文本(首轮直接取 cleaned)。
                // 把本轮思考按发生时间点内联嵌入(思考排在本轮正文前、上一轮输出后),
                // 交由渲染端按时间轴交错展示,而非把所有思考堆到顶部。
                val cleaned = stripToolBlocks(res.fullText)
                val showResp = res.reasoning.isNotBlank()
                val withThinking = if (showResp) appendInlineReasoning(accumulated, res.reasoning) else accumulated
                accumulated = when {
                    showResp -> {
                        if (cleaned.isBlank()) withThinking
                        else withThinking + "\n\n" + cleaned
                    }
                    cleaned.isBlank() -> accumulated
                    accumulated.isBlank() -> cleaned
                    else -> accumulated + "\n\n" + cleaned
                }
                val producedTool = nativeCalls.isNotEmpty() || extractFirstToolCall(res.fullText) != null
                // 任务型会话(开了任务模式或已用过工具)需要交付契约:动态更新判断依据
                if (enableTaskCompletion) contractNeeded = AgentTaskCompletion.needsContract(ctx, userId, toolCallCount)
                // 模型本轮既没调用工具、也没输出正文(只有思考):说明它还没想完/没产出答案,
                // 只要没到轮数上限就继续下一轮,避免"想了半天却什么都没说"就结束。
                if (!producedTool && cleaned.isBlank() && toolRounds < maxToolRounds) {
                    continue
                }
                if (!producedTool) {
                    // 多 Agent 硬约束(编排层兜底):动了工作区却从未调用 agent_review → 注入补验指令,强制再跑一轮。
                    // 提示词层的「必须验收」被模型无视时的确定性补救;只补验一次,防死循环。
                    if (enforceReviewEffective && !reviewCalled && !reviewNudged && mutatedWorkspace.isNotEmpty() && toolRounds < maxToolRounds) {
                        reviewNudged = true
                        history.add("user" to "【系统强制补验】你处于多 Agent 模式,本次任务调用了 ${mutatedWorkspace.joinToString("、")} 等改动工作区的工具,但没有调用 agent_review 做独立验收。请现在调用 agent_review(task=用户的原始请求,summary=你的工作汇报:做了什么、改了哪些文件含路径、关键操作与结果)完成验收,再向用户交付最终答复。")
                        continue
                    }
                    // 防提前收工:任务型会话想结束却没交付 → 强制再跑(最多 MAX_DELIVER_NUDGES 次)
                    if (phase != AgentTaskCompletion.AgentTaskPhase.WRAP_UP && contractNeeded && !AgentTaskCompletion.hasDelivery(accumulated) && nudgeCount < AgentTaskCompletion.MAX_DELIVER_NUDGES) {
                        nudgeCount++
                        history.add("user" to AgentTaskCompletion.nudgeInstruction())
                        Log.i(TAG, "runAgentChatLoop 模型想结束但无交付,强制补做(第 $nudgeCount 次)")
                        continue
                    }
                    // 收尾阶段(WRAP_UP):已保留少量工具补救额度,给模型最多 MAX_WRAP_UP_ROUNDS 轮纯文本交付;交付即停,否则续一轮
                    if (phase == AgentTaskCompletion.AgentTaskPhase.WRAP_UP) {
                        if (AgentTaskCompletion.hasDelivery(accumulated) || wrapUpRounds >= AgentTaskCompletion.MAX_WRAP_UP_ROUNDS) break
                        wrapUpRounds++
                        history.add("user" to "【系统·收尾续写】请继续补全你的交付总结(若确需补救且额度未耗尽可少量调用工具)。")
                        continue
                    }
                    // 兜底:任务型会话模型始终不交付 → 编排层合成交付块,确保用户一定拿到结果
                    if (enableTaskCompletion && contractNeeded && !AgentTaskCompletion.hasDelivery(accumulated)) {
                        accumulated += "\n" + AgentTaskCompletion.fallbackDeliveryBlock(toolCalls, fileChanges)
                    }
                    break
                }
                if (toolRounds >= maxToolRounds) {
                    // 软上限:轮次预算耗尽不再硬断,而是转入收尾阶段(禁工具强制纯文本交付),保证用户拿到交付
                    if (contractNeeded && phase == AgentTaskCompletion.AgentTaskPhase.NORMAL && wrapUpRounds < AgentTaskCompletion.MAX_WRAP_UP_ROUNDS) {
                        phase = AgentTaskCompletion.AgentTaskPhase.WRAP_UP
                        wrapUpRounds++
                        currentToolChoice = null // P1:收尾保留少量工具补救额度(由 wrapUpToolBudget 限额)
                        wrapUpToolBudget = AgentTaskCompletion.MAX_WRAP_UP_TOOL_ROUNDS
                        history.add("user" to AgentTaskCompletion.wrapUpInstruction(toolRounds, "轮次预算已用尽"))
                        Log.i(TAG, "runAgentChatLoop 轮次预算耗尽,转入收尾阶段")
                        continue
                    }
                    break
                }
                if (nativeCalls.isNotEmpty()) {
                    // 原生 function calling:标准 role:tool 回喂(request 组装时还原 tool_calls)
                    val tcArr = JSONArray()
                    for (nc in nativeCalls) {
                        tcArr.put(JSONObject().apply {
                            put("id", nc.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", nc.name)
                                put("arguments", nc.argumentsJson)
                            })
                        })
                    }
                    history.add("assistant" to NATIVE_TOOLS_ASSISTANT_PREFIX + tcArr.toString())
                    for (nc in nativeCalls) {
                        val args = try { JSONObject(nc.argumentsJson) } catch (_: Exception) { JSONObject() }
                        val call = AgentToolCall(
                            tool = nc.name,
                            path = args.optString("path", ""),
                            content = args.optString("content", ""),
                            argsJson = nc.argumentsJson
                        )
                        // 多 Agent 强制验收跟踪:是否验过收 / 动过工作区 / 复验计数
                        if (nc.name == TOOL_AGENT_REVIEW) { reviewCalled = true; reviewCallCount++ }
                        if (nc.name in REVIEW_REQUIRED_TOOLS) mutatedWorkspace.add(nc.name)
                        // 调用开始:把「正在调用」状态行内联进正文流当前位置(调用真正发生的位置)
                        accumulated = appendToolInline(accumulated, nc.name, 0)
                        onToolStage?.invoke(nc.name to 0)
                        val callTs = System.currentTimeMillis()
                        val approved = onNeedApproval?.invoke(nc.name, nc.argumentsJson) ?: true
                        val result = if (approved) {
                            if (nc.name == TOOL_AGENT_REVIEW && reviewCallCount > MAX_AGENT_REVIEW_CALLS) reviewCapExceededResult()
                            else executeAgentTool(ctx, userId, call, appActionHandler)
                        } else {
                            agentDeniedResult(ctx, userId, nc.name)
                        }
                        history.add("tool" to NATIVE_TOOL_RESULT_PREFIX + nc.id + "\u0000" + condenseToolResult(result))
                        toolCallCount++
                        // 嵌套 agent 循环(验收/规划/派发)返回的计数与调用明细合并回本层
                        val (nestedCnt, nestedCalls) = consumeNestedToolData()
                        toolCallCount += nestedCnt
                        toolCalls.addAll(nestedCalls)
                        emitFileCard(nc.name, call.path, result, approved)
                        emitMediaIfGenerated(nc.name, result, approved)
                        emitCaptureDelivery(nc.name, result, approved)
                        // 完成态:结果 ok 显示绿色「已调用」,否则红色「调用失败」(完成为彩色状态行,不再写纯文本)
                        val sOk = try { JSONObject(result).optBoolean("ok") } catch (_: Exception) { false }
                        toolCalls.add(ChatMsg.ToolStageRecord(nc.name, if (sOk) 1 else 2, callTs))
                        // agent_review:把验收 Agent 的报告块写进消息流(可见的接手记录,随消息持久化)
                        if (nc.name == TOOL_AGENT_REVIEW || nc.name == TOOL_AGENT_FORK)
                            (if (nc.name == TOOL_AGENT_REVIEW) reviewReportBlock(result) else forkReportBlock(result))?.let { blk ->
                            accumulated = accumulated + "\n" + blk
                        }
                        // ask_user 完成:「问/答」记录直接嵌进状态块(点状态行展开),不再单独写「回答内容」块
                        // 调用完成:把同名状态行原地替换为最终态(1 已调用 / 2 失败)
                        accumulated = replaceToolInline(
                            accumulated, nc.name, if (sOk) 1 else 2,
                            if (nc.name == TOOL_ASK_USER) askUserRecordText(result) ?: "" else ""
                        )
                        onToolStage?.invoke(nc.name to if (sOk) 1 else 2)
                    }
                    // === 本轮全失败硬上限检查 ===
                    // 在上面 for 循环里逐次累计了 roundOkCount / roundFailCount（见下方）
                    // 这里只是消费它们
                    val allFailThisRound = nativeCalls.isNotEmpty() && nativeCalls.all { nc ->
                        // 直接重新算一次 ok：从 history.tool 结果里解析
                        val toolPrefix = NATIVE_TOOL_RESULT_PREFIX + nc.id + "\u0000"
                        val toolResult = history.lastOrNull {
                            it.first == "tool" && it.second.startsWith(toolPrefix)
                        }?.second?.substringAfter("\u0000") ?: ""
                        try { JSONObject(toolResult).optBoolean("ok") } catch (_: Exception) { false }
                    }
                    if (allFailThisRound) {
                        consecutiveAllFailRounds++
                        if (consecutiveAllFailRounds >= 3) {
                            if (contractNeeded) {
                                // P1:失败≠立即收尾。先给「换策略」补救机会(保留工具),让模型换参数/换工具/拆步骤,
                                // 机会用尽仍连续失败才转入收尾;失败提示不拼进 accumulated(用户可见文本),只喂 history
                                if (failStrategyRounds < AgentTaskCompletion.MAX_FAIL_STRATEGY_ROUNDS) {
                                    failStrategyRounds++
                                    history.add("user" to AgentTaskCompletion.failStrategyInstruction())
                                    Log.i(TAG, "runAgentChatLoop 连续 $consecutiveAllFailRounds 轮工具失败,注入换策略指令(第 $failStrategyRounds 次)")
                                } else {
                                    phase = AgentTaskCompletion.AgentTaskPhase.WRAP_UP
                                    wrapUpRounds++
                                    currentToolChoice = null // P1:收尾保留工具(由 wrapUpToolBudget 限额,防死循环)
                                    wrapUpToolBudget = AgentTaskCompletion.MAX_WRAP_UP_TOOL_ROUNDS
                                    history.add("user" to AgentTaskCompletion.wrapUpInstruction(toolRounds, "连续 ${consecutiveAllFailRounds} 轮工具全部失败,已停止重试"))
                                }
                            } else {
                                // 非任务型会话/嵌套子循环:直接耗尽轮次,正常退出,不强制交付
                                toolRounds = maxToolRounds
                            }
                        }
                    } else {
                        consecutiveAllFailRounds = 0
                        failStrategyRounds = 0
                    }
                    // P1:收尾阶段工具补救额度递减(额度耗尽后禁工具,强制纯文本交付)
                    if (phase == AgentTaskCompletion.AgentTaskPhase.WRAP_UP && nativeCalls.isNotEmpty()) {
                        wrapUpToolBudget--
                        if (wrapUpToolBudget <= 0) {
                            currentToolChoice = "none"
                            Log.i(TAG, "runAgentChatLoop 收尾阶段工具补救额度耗尽,后续禁工具")
                        }
                    }
                    toolRounds++
                } else {
                    // 文本协议兜底(原生不可用时):@tool:start 块 → 裸 JSON 代码块
                    var toolCall = extractFirstToolCall(res.fullText)
                    if (toolCall == null) toolCall = extractToolCallFromCodeBlock(res.fullText)
                    if (toolCall != null) {
                        // 多 Agent 强制验收跟踪:是否验过收 / 动过工作区 / 复验计数
                        if (toolCall.tool == TOOL_AGENT_REVIEW) { reviewCalled = true; reviewCallCount++ }
                        if (toolCall.tool in REVIEW_REQUIRED_TOOLS) mutatedWorkspace.add(toolCall.tool)
                        // 调用开始:把「正在调用」状态行内联进正文流当前位置(调用真正发生的位置)
                        accumulated = appendToolInline(accumulated, toolCall.tool, 0)
                        onToolStage?.invoke(toolCall.tool to 0)
                        val callTs = System.currentTimeMillis()
                        val approved = onNeedApproval?.invoke(toolCall.tool, toolCall.argsJson) ?: true
                        val result = if (approved) {
                            if (toolCall.tool == TOOL_AGENT_REVIEW && reviewCallCount > MAX_AGENT_REVIEW_CALLS) reviewCapExceededResult()
                            else executeAgentTool(ctx, userId, toolCall, appActionHandler)
                        } else {
                            agentDeniedResult(ctx, userId, toolCall.tool)
                        }
                        history.add("assistant" to res.fullText)
                        history.add("user" to "工具执行结果: " + condenseToolResult(result))
                        toolCallCount++
                        // 嵌套 agent 循环(验收/规划/派发)返回的计数与调用明细合并回本层
                        val (nestedCnt, nestedCalls) = consumeNestedToolData()
                        toolCallCount += nestedCnt
                        toolCalls.addAll(nestedCalls)
                        emitFileCard(toolCall.tool, toolCall.path, result, approved)
                        emitMediaIfGenerated(toolCall.tool, result, approved)
                        emitCaptureDelivery(toolCall.tool, result, approved)
                        val sOk = try { JSONObject(result).optBoolean("ok") } catch (_: Exception) { false }
                        toolCalls.add(ChatMsg.ToolStageRecord(toolCall.tool, if (sOk) 1 else 2, callTs))
                        // agent_review:把验收 Agent 的报告块写进消息流(可见的接手记录,随消息持久化)
                        if (toolCall.tool == TOOL_AGENT_REVIEW || toolCall.tool == TOOL_AGENT_FORK)
                            (if (toolCall.tool == TOOL_AGENT_REVIEW) reviewReportBlock(result) else forkReportBlock(result))?.let { blk ->
                            accumulated = accumulated + "\n" + blk
                        }
                        // ask_user 完成:「问/答」记录直接嵌进状态块(点状态行展开),不再单独写「回答内容」块
                        // 调用完成:把同名状态行原地替换为最终态(1 已调用 / 2 失败)
                        accumulated = replaceToolInline(
                            accumulated, toolCall.tool, if (sOk) 1 else 2,
                            if (toolCall.tool == TOOL_ASK_USER) askUserRecordText(result) ?: "" else ""
                        )
                        onToolStage?.invoke(toolCall.tool to if (sOk) 1 else 2)
                        // === 文本协议兜底：连续失败硬上限（同上,P1 先换策略补救,机会用尽才收尾）===
                        if (!sOk) {
                            consecutiveAllFailRounds++
                            if (consecutiveAllFailRounds >= 3) {
                                if (contractNeeded) {
                                    // 同上方原生分支:不往 accumulated 拼系统提示,只喂 history
                                    if (failStrategyRounds < AgentTaskCompletion.MAX_FAIL_STRATEGY_ROUNDS) {
                                        failStrategyRounds++
                                        history.add("user" to AgentTaskCompletion.failStrategyInstruction())
                                        Log.i(TAG, "runAgentChatLoop 文本协议连续 $consecutiveAllFailRounds 轮失败,注入换策略指令(第 $failStrategyRounds 次)")
                                    } else {
                                        phase = AgentTaskCompletion.AgentTaskPhase.WRAP_UP
                                        wrapUpRounds++
                                        currentToolChoice = null // P1:收尾保留工具(由 wrapUpToolBudget 限额)
                                        wrapUpToolBudget = AgentTaskCompletion.MAX_WRAP_UP_TOOL_ROUNDS
                                        history.add("user" to AgentTaskCompletion.wrapUpInstruction(toolRounds, "连续 ${consecutiveAllFailRounds} 轮工具全部失败,已停止重试"))
                                    }
                                } else {
                                    toolRounds = maxToolRounds
                                }
                            }
                        } else {
                            consecutiveAllFailRounds = 0
                            failStrategyRounds = 0
                        }
                        // P1:收尾阶段工具补救额度递减(额度耗尽后禁工具)
                        if (phase == AgentTaskCompletion.AgentTaskPhase.WRAP_UP) {
                            wrapUpToolBudget--
                            if (wrapUpToolBudget <= 0) {
                                currentToolChoice = "none"
                                Log.i(TAG, "runAgentChatLoop 文本协议收尾阶段工具补救额度耗尽,后续禁工具")
                            }
                        }
                        toolRounds++
                    }
                }
            }
            // 收尾:总时长取「循环启动 → 此刻」的连续墙钟时间,保证尾段时间不丢失
            val finalTotalSeconds = (android.os.SystemClock.elapsedRealtime() - loopStartElapsed) / 1000L
            // 快照比对:把工具记录之外的一切真实改动(shell/Python 脚本写入等)补进改动清单
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { mergeSandboxDiff(ctx, userId, sandboxBefore, fileChanges) }
            // 嵌套 agent 循环(验收/规划/派发):本层收尾时把改动合并回父层、计数/明细暂存
            absorbNestedLoopIntoParent(prevRecorder, fileChanges, toolCallCount, toolCalls)
            return AgentLoopOutcome(accumulated, toolRounds, clean = true, lastUsage, lastThinking, finalTotalSeconds, fileChanges, toolCallCount, toolCalls)
        } finally {
            activeFileChangeRecorder = prevRecorder
            com.aurora.chat.AgentTaskGuard.markFinished(ctx)
        }
    }

    // ===== 多 Agent 模式:独立验收 Agent =====

    /** 验收 Agent 的只读工具白名单(硬编码,写操作类一律拒绝——验收者不能篡改现场) */
    private val REVIEWER_READ_TOOLS = setOf(
        "list_files", "read_file", "stat_file", "find_files", "search_web", "fetch_webpage",
        "query_environment", "json_process", "regex_test", "hash_digest", "encode_convert",
        "timestamp_convert", "ping_host", "process_list", "list_my_contacts", "list_my_groups",
        "phone_screen", "capture_screen"
    )

    /** 多 Agent 模式下触发强制验收的「动工作区」类工具:执行者用了这些工具就必须走 agent_review */
    val REVIEW_REQUIRED_TOOLS = setOf(
        "write_file", "delete_file", "delete_folder", "rename_file", "copy_file", "append_file",
        TOOL_GIT, TOOL_DOWNLOAD_FILE, TOOL_EXECUTE_PYTHON, TOOL_SHELL_EXEC, TOOL_INSTALL_APK,
        TOOL_AGENT_FORK,   // 并行派发的子任务动了工作区,整体成果同样必须过验收
        TOOL_BATCH_LOOP    // 批量循环内部会执行写入类工具,同样视为动过工作区
    )

    /** 验收 Agent 的独立系统提示词(全新上下文,不与执行者共享对话,避免立场污染) */
    private const val REVIEWER_SYSTEM_PROMPT =
        "你是一个独立的验收 Agent,职责是核验另一个执行 Agent 的工作成果是否真实、完整、正确。规则:\n" +
        "(1) 你只有只读权限:允许使用 list_files/read_file/stat_file/find_files/search_web/fetch_webpage/query_environment/json_process/regex_test/hash_digest/encode_convert/timestamp_convert/ping_host/process_list/list_my_contacts/list_my_groups/phone_screen/capture_screen;任何写入/删除/执行类工具都会被直接拒绝,不要尝试。\n" +
        "(2) 核验必须基于你亲自获取的事实:执行者声称修改了某文件,你就亲自 read_file 打开核对;声称查到了某数据,你就亲自检索复核。绝不许只凭执行者的描述下结论;无法核验的项要如实标注「未核实」,既不放过也不冤枉。\n" +
        "(3) 逐条对照原始任务要求,检查:是否全部完成、结果是否正确、有无遗漏、有无遗留占位符/临时内容/明显错误。\n" +
        "(4) 输出格式(严格遵守):第一行只写验收结论,必须形如「验收结论:PASS」或「验收结论:FAIL」(等价写法如「验收结论:通过/不通过」「结论:PASS/FAIL」「verdict: PASSED/FAILED」也可以,但结论必须独占一行、放在最前面,判定词必须明确,不允许出现「PASS?」「待定」「需要人工确认」等含糊表述);若 FAIL,随后列出具体问题清单——每条一行,写成执行者可以直接照做的修复指令;最后一小节简述你的核验过程(查了哪些文件/来源,各自结果)。不要输出与核验无关的内容,不要寒暄。\n" +
        "(5) 核验必须克制高效:只读工具调用总数控制在 8 次以内,优先核验关键项,查到足够下结论的证据就立即输出验收结论,不做多余动作。对现有只读工具无法核验的事项(例如「消息是否送达对方」「对方是否已读」「主观偏好类要求」),直接标注「未核实」并在结论里说明原因,绝不许为核验不了的项目反复换工具尝试。小任务(1-3 个简单子项)通常 2-3 次工具调用就该出结论。"

    /**
     * 验收 Agent 实时状态总线:doAgentReview 各阶段更新(接手 → 每轮核验 → 正在核验的具体动作),
     * UI 端渲染 agent_review「调用中」状态行时收集显示,让用户看见验收 Agent 的接手过程。
     * 进程内同一时刻只有一个验收在跑,全局单槽安全。
     */
    object AgentReviewBus {
        private val _live = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val live: kotlinx.coroutines.flow.StateFlow<String?> get() = _live
        fun update(s: String?) { _live.value = s }
    }

    /** 用户点「跳过审查」后置位;doAgentReview 在轮次边界与流式中途感知并中止验收(SKIPPED 出口) */
    private val reviewSkipFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    /** UI 调用:请求跳过当前正在进行的独立验收(进程内同一时刻只有一个验收,全局单槽安全) */
    fun requestReviewSkip() { reviewSkipFlag.set(true) }

    /** 新一轮 AI 任务开始时清零「跳过审查」请求(上一轮的跳过不残留到下一轮) */
    fun resetReviewSkip() { reviewSkipFlag.set(false) }

    /** 跳过验收的内部控制流异常:仅用于从验收循环里跳出,不外泄 */
    private class ReviewSkippedByUserException : Exception()

    /**
     * 并行派发实时状态总线:doAgentFork 派发/子任务完成时更新,
     * UI 端在 agent_fork「调用中」状态行下方收集显示(已完成 n/总数)。
     */
    object AgentForkBus {
        private val _live = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val live: kotlinx.coroutines.flow.StateFlow<String?> get() = _live
        private val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile private var total = 0
        fun start(n: Int) {
            total = n
            doneCount.set(0)
            _live.value = "已并行派发 $n 个子执行 Agent,同时开工…"
        }
        fun workerDone() {
            val d = doneCount.incrementAndGet()
            if (d < total) _live.value = "并行执行中:$d/$total 个子任务已完成"
        }
        fun update(s: String?) { _live.value = s }
        fun clear() {
            total = 0
            doneCount.set(0)
            _live.value = null
        }
    }

    /** ask_user 的单个问题定义(由 AI 调用参数解析而来) */
    data class AskUserQuestion(
        val question: String,
        val options: List<String>,
        /** true=允许多选(复选框),false=单选(圆点) */
        val multi: Boolean,
        /** true=附加「其他」自由填写项 */
        val other: Boolean
    )

    /**
     * AI 向用户提问的总线:doAskUser 在工具执行线程 post 一个 Request 并挂起等答案,
     * 聊天界面收集 pending 弹出全屏问答面板,用户「完成/返回」时 complete 答案 JSON。
     * 单槽:同一时刻只允许一个提问面板(并行 Agent 场景下第二个提问者会直接收到失败结果)。
     */
    object AskUserBus {
        data class Request(
            val questions: List<AskUserQuestion>,
            val deferred: kotlinx.coroutines.CompletableDeferred<String>
        ) {
            /** 作答草稿:挂在请求实例上,面板收起再展开时逐字保留(同一次提问内不丢) */
            val draft: AskUserDraft by lazy { AskUserDraft(questions.size) }
        }

        /** 一次提问的作答草稿:选中项/其他勾选/其他文本/自由文本/当前页码 */
        class AskUserDraft(n: Int) {
            val selected = Array(n) { androidx.compose.runtime.mutableStateOf(emptySet<Int>()) }
            val otherChecked = Array(n) { androidx.compose.runtime.mutableStateOf(false) }
            val otherText = Array(n) { androidx.compose.runtime.mutableStateOf("") }
            val freeText = Array(n) { androidx.compose.runtime.mutableStateOf("") }
            val page = androidx.compose.runtime.mutableStateOf(0)
        }

        private val _pending = kotlinx.coroutines.flow.MutableStateFlow<Request?>(null)
        val pending: kotlinx.coroutines.flow.StateFlow<Request?> get() = _pending

        /** 面板收起标记:true=用户主动收起(未提交,提问仍在等待),点消息流里的「正在向用户提问」状态行可重新展开 */
        private val _hidden = kotlinx.coroutines.flow.MutableStateFlow(false)
        val hidden: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _hidden

        /** 收起面板(不提交、不清请求;AI 继续等待作答) */
        fun hide() { _hidden.value = true }

        /** 重新展开面板(带着上次的作答草稿继续) */
        fun reopen() { _hidden.value = false }

        /** 发布提问;已有面板在等待时返回 false */
        fun post(r: Request): Boolean {
            if (_pending.value?.deferred?.isActive == true) return false
            _pending.value = r
            _hidden.value = false
            return true
        }

        /** 撤下提问面板(仅当仍挂着自己的请求) */
        fun clear(r: Request) {
            if (_pending.value === r) {
                _pending.value = null
                _hidden.value = false
            }
        }
    }

    /**
     * 备份恢复审批总线:restore_backup 工具在任意访问模式下都只"发起恢复请求"并挂起,
     * 聊天界面收集 pending 弹出【红字警告】确认框,用户手动批准后才真正写回工作区;拒绝/关闭则不改任何东西。
     * 单槽:同一时刻只允许一个待批准恢复(并行 Agent 场景下第二个会直接失败)。
     *
     * 注意:AI 绝不直接读写备份目录,本总线仅承载"是否批准"这一布尔结果,真正的 restoreBackup 由用户在 UI 侧触发。
     */
    object BackupRestoreBus {
        data class Request(
            val meta: com.aurora.chat.ui.chat.BackupMeta,
            val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>
        )

        private val _pending = kotlinx.coroutines.flow.MutableStateFlow<Request?>(null)
        val pending: kotlinx.coroutines.flow.StateFlow<Request?> get() = _pending

        /** 发布恢复请求;已有待批准请求则返回 false */
        fun post(r: Request): Boolean {
            if (_pending.value?.deferred?.isActive == true) return false
            _pending.value = r
            return true
        }

        /** UI 侧结算:approved=true 表示用户批准恢复;完成后清槽 */
        fun complete(approved: Boolean) {
            val r = _pending.value ?: return
            if (r.deferred.isActive) r.deferred.complete(approved)
            _pending.value = null
        }
    }

    /**
     * ask_user 工具执行:解析问题清单 → 发到 AskUserBus 弹全屏问答面板 → 挂起等用户作答 →
     * 把用户答案 JSON 原样回喂给 AI。用户「完成/返回键」都会触发提交,未答的题标注 skipped=true。
     */
    /** 可用插件 = 已上架的 + 本人自建的全部状态(自建无需审核即可自用) */
    private suspend fun usablePlugins(userId: Long): Pair<List<JSONObject>?, String> {
        val res = com.aurora.chat.data.api.AuroraApi.pluginMarket(mine = true)
        if (!res.success) return Pair(null, res.message)
        val list = (res.data ?: emptyList()).filter {
            it.optString("status") == "approved" || it.optLong("uploaderId") == userId
        }
        return Pair(list, "")
    }

    /** 从响应 JSON 按点分/斜分路径取值(data.result / data.0.name);取不到返回空串 */
    private fun extractPluginPath(text: String, path: String): String {
        if (path.isBlank()) return ""
        return try {
            var cur: Any = JSONObject(text)
            for (seg in path.split('.', '/').filter { it.isNotBlank() }) {
                cur = when (cur) {
                    is JSONObject -> cur.opt(seg) ?: return ""
                    is JSONArray -> cur.opt(seg.toIntOrNull() ?: return "") ?: return ""
                    else -> return ""
                }
            }
            cur.toString()
        } catch (_: Exception) { "" }
    }

    /** 查看可用插件列表(市场已上架 + 用户自建);可选关键词过滤与单插件详情 */
    private suspend fun doPluginList(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = argsOf(call)
        val keyword = args.optString("keyword", "").trim()
        val detail = args.optString("detail", "").trim()
        val (list, errMsg) = usablePlugins(userId)
        if (list == null) return toolErr("获取插件列表失败:$errMsg")
        val filtered = if (keyword.isEmpty()) list else list.filter {
            it.optString("name").contains(keyword, true) || it.optString("description").contains(keyword, true)
        }
        val arr = JSONArray()
        for (p in filtered) {
            val o = JSONObject()
                .put("id", p.optLong("id"))
                .put("name", p.optString("name"))
                .put("description", p.optString("description"))
                .put("uploader", p.optString("uploader"))
                .put("mine", p.optLong("uploaderId") == userId)
            if (p.optLong("uploaderId") == userId) o.put("status", p.optString("status"))
            // 参数清单(便于 AI 知道调用时要传什么)
            val pArr = JSONArray()
            try {
                val def = JSONObject(p.optString("pluginJson"))
                val defs = def.optJSONArray("params") ?: JSONArray()
                for (i in 0 until defs.length()) {
                    val d = defs.optJSONObject(i) ?: continue
                    val item = JSONObject().put("name", d.optString("name")).put("label", d.optString("label"))
                    if (d.optBoolean("required", false)) item.put("required", true)
                    if (d.has("default")) item.put("default", d.optString("default"))
                    pArr.put(item)
                }
            } catch (_: Exception) {}
            o.put("params", pArr)
            if (detail.isNotEmpty() && p.optString("name") == detail) o.put("pluginJson", p.optString("pluginJson"))
            arr.put(o)
        }
        if (arr.length() == 0) {
            return toolOk(JSONObject()
                .put("plugins", arr)
                .put("count", 0)
                .put("message", "当前没有可用插件" + (if (keyword.isNotEmpty()) "(关键词:$keyword)" else "") + "。可提示用户在插件市场安装或用创作模式自建。"))
        }
        return toolOk(JSONObject().put("plugins", arr).put("count", arr.length()))
    }

    /** 调用一个声明式 http_tool 插件:替换 URL 参数 → 发请求 → 按 responsePath 取值返回 */
    private suspend fun doPluginCall(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = argsOf(call)
        val name = args.optString("name", "").trim()
        val id = args.optLong("id", 0L)
        val reqParams = args.optJSONObject("params") ?: JSONObject()
        if (name.isEmpty() && id <= 0) return toolErr("必须提供 name(插件名),可先用 plugin_list 查看可用插件")
        val (list, errMsg) = usablePlugins(userId)
        if (list == null) return toolErr("获取插件列表失败:$errMsg")
        val p = list.firstOrNull {
            (id > 0 && it.optLong("id") == id) || (name.isNotEmpty() && it.optString("name") == name)
        } ?: return toolErr("找不到可用的插件「$name」。用 plugin_list 查看全部可用插件(市场已上架的 + 你所在用户自建的,自建无需审核即可用)")
        val def = try { JSONObject(p.optString("pluginJson")) } catch (_: Exception) {
            return toolErr("插件「${p.optString("name")}」的定义不是合法 JSON,已损坏")
        }
        if (def.optString("type") != "http_tool") return toolErr("插件「${p.optString("name")}」不是 http_tool 类型,暂无法调用")
        val method = def.optString("method", "GET").uppercase()
        val urlTpl = def.optString("url", "").trim()
        if (!urlTpl.startsWith("http://") && !urlTpl.startsWith("https://")) {
            return toolErr("插件「${p.optString("name")}」的 url 非法(必须以 http:// 或 https:// 开头)")
        }
        val responsePath = def.optString("responsePath", "").trim()
        val defs = def.optJSONArray("params") ?: JSONArray()
        // 逐参数取值:请求值 > 插件默认值;required 缺失直接报错
        var url = urlTpl
        val bodyObj = JSONObject()
        for (i in 0 until defs.length()) {
            val d = defs.optJSONObject(i) ?: continue
            val pn = d.optString("name").trim()
            if (pn.isEmpty()) continue
            val provided = reqParams.has(pn) && reqParams.optString(pn).trim().isNotEmpty()
            val v = if (provided) reqParams.optString(pn).trim() else d.optString("default", "")
            if (v.isEmpty() && d.optBoolean("required", false)) {
                return toolErr("插件「${p.optString("name")}」的参数 $pn 为必填,本次未提供且无默认值。请在 params 里传入 {\"$pn\": \"...\"}")
            }
            url = url.replace("{${pn}}", java.net.URLEncoder.encode(v, "UTF-8"))
            if (method == "POST") bodyObj.put(pn, v)
        }
        // 还有残留占位符 = 有参数既没提供也没有默认值
        val leftover = Regex("\\{[a-zA-Z][a-zA-Z0-9_]*\\}").findAll(url).map { it.value }.toList()
        if (leftover.isNotEmpty()) {
            return toolErr("插件「${p.optString("name")}」还有未赋值的参数:${leftover.joinToString("、")}(请在 params 里提供,或该插件定义有误)")
        }
        val out = try {
            if (method == "POST") com.aurora.chat.data.api.HttpClient.jsonRequestAllowError("POST", url, bodyObj.toString())
            else com.aurora.chat.data.api.HttpClient.jsonRequestAllowError("GET", url)
        } catch (e: Exception) {
            return toolErr("插件「${p.optString("name")}」请求失败:${e.message ?: e.javaClass.simpleName}")
        }
        val result = extractPluginPath(out, responsePath)
        return toolOk(JSONObject()
            .put("plugin", p.optString("name"))
            .put("raw", out.take(4000))
            .put("result", if (result.isNotEmpty()) result else JSONObject.NULL)
            .put("message", if (result.isNotEmpty()) "调用成功,已按插件定义的 responsePath 取出结果" else "调用成功,返回原始响应(responsePath 未定义或未取到值,请自行从 raw 解析)"))
    }

    /** 提交声明式 HTTP 工具插件到插件市场(创作模式);复用客户端已有的 pluginSubmit 接口 */
    private suspend fun doPluginSubmit(ctx: Context, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson) } catch (_: Exception) { JSONObject() }
        val name = args.optString("name", "").trim()
        val desc = args.optString("description", "").trim()
        val pluginJson = args.optString("pluginJson", "").trim()
        if (name.isEmpty()) return toolErr("name 参数缺失:必须给插件起个名字(40 字内)")
        if (name.length > 40) return toolErr("插件名超过 40 字,请缩短")
        if (desc.isEmpty()) return toolErr("description 参数缺失:写一句描述,审核的人需要知道它干什么(300 字内)")
        if (desc.length > 300) return toolErr("描述超过 300 字,请精简")
        if (pluginJson.isEmpty()) return toolErr("pluginJson 参数缺失:必须是完整的插件 JSON 字符串")
        if (pluginJson.length > 64 * 1024) return toolErr("插件内容超过 64KB 限制")
        // 本地先校验 JSON 合法性与必备字段,把错误拦在提交之前
        val probe = try { JSONObject(pluginJson) } catch (_: Exception) {
            return toolErr("pluginJson 不是合法的 JSON 对象,请检查转义与格式后重试")
        }
        if (probe.optString("type") != "http_tool") {
            return toolErr("pluginJson.type 必须为 http_tool(当前只支持声明式 HTTP 工具插件)")
        }
        val method = probe.optString("method", "GET").uppercase()
        if (method != "GET" && method != "POST") return toolErr("method 必须为 GET 或 POST")
        val url = probe.optString("url", "").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return toolErr("url 必须以 http:// 或 https:// 开头")
        }
        val res = com.aurora.chat.data.api.AuroraApi.pluginSubmit(name, desc, pluginJson)
        return if (res.success) {
            toolOk(JSONObject()
                .put("id", res.data ?: 0L)
                .put("name", name)
                .put("message", "插件「$name」已提交,进入开发者审核;审核通过后会自动上架,用户可在插件市场-我的-可用的里看到。"))
        } else {
            toolErr("提交失败:${res.message}")
        }
    }

    private suspend fun doAskUser(ctx: Context, userId: Long, call: AgentToolCall): String {
        val args = try { JSONObject(call.argsJson) } catch (_: Exception) { JSONObject() }
        val arr = args.optJSONArray("questions")
        if (arr == null || arr.length() == 0) {
            return JSONObject().put("ok", false)
                .put("error", "questions 参数缺失:必须至少提供 1 个问题").toString()
        }
        val questions = mutableListOf<AskUserQuestion>()
        for (i in 0 until minOf(arr.length(), 8)) {
            val o = arr.optJSONObject(i) ?: continue
            val q = o.optString("question", "").trim()
            if (q.isEmpty()) continue
            val opts = mutableListOf<String>()
            val oa = o.optJSONArray("options")
            if (oa != null) for (j in 0 until oa.length()) {
                val s = oa.optString(j, "").trim()
                if (s.isNotEmpty()) opts.add(s)
            }
            questions.add(AskUserQuestion(q, opts.take(6), o.optBoolean("multi", false), o.optBoolean("other", false)))
        }
        if (questions.isEmpty()) {
            return JSONObject().put("ok", false)
                .put("error", "questions 里没有有效问题(每项必须有 question 字段)").toString()
        }
        val def = kotlinx.coroutines.CompletableDeferred<String>()
        val request = AskUserBus.Request(questions, def)
        if (!AskUserBus.post(request)) {
            return JSONObject().put("ok", false)
                .put("error", "已有一个提问面板在等待用户作答,请稍后再问").toString()
        }
        com.aurora.chat.ErrorReporter.debug("AI_AskUser", "弹出提问面板:${questions.size} 题")
        return try {
            def.await() // 面板提交的就是最终结果 JSON
        } catch (e: kotlinx.coroutines.CancellationException) {
            // AI 任务被用户停止:撤下面板,按取消传播
            AskUserBus.clear(request)
            throw e
        } catch (e: Exception) {
            AskUserBus.clear(request)
            JSONObject().put("ok", false).put("error", "提问异常中止:${e.message ?: e.javaClass.simpleName}").toString()
        }
    }

    /**
     * 「上次的任务被中断」弹窗的「查看 AI 说明」:一次性直连 AI 问中断原因。
     * 关键约束:**完全不落盘**——用独立 history 直接调 streamChat,不经过消息流、
     * 不写会话文件、不进历史,弹窗拿到说明文字后这次问答就消失,用户无需清理。
     */
    suspend fun explainInterruption(ctx: Context, userId: Long, notice: String): String {
        val history = mutableListOf(
            "system" to "你是 Aurora Chat 应用内的助手。用户的上一轮 AI 自动任务被系统异常中断了——这类中断(应用在后台被系统清理/被强行停止/ANR)不产生崩溃异常,应用自己也无法预知。请用简短、通俗的中文向用户解释:这次发生了什么、为什么会发生、之后怎么避免(例如保持应用前台、在系统设置里给应用加锁防清理、关闭省电限制)。不要虚构不存在的细节,不确定的部分如实说不确定。120 字以内,直接给说明,不要寒暄,不要使用 markdown 标题。",
            "user" to "系统给我的中断提示如下:\n$notice\n\n请解释这次任务为什么会被中断。"
        )
        var result = ""
        var err: String? = null
        streamChat(
            ctx = ctx, userId = userId, history = history,
            onDelta = {},
            onDone = { _, full, _, _ -> result = full },
            onError = { err = it },
            treatEmptyAsError = false,
            stopCheck = { false },
            enableAgentTools = false
        )
        if (err != null) {
            return "AI 暂时无法给出说明(${err})。这类中断通常是:应用切到后台后被系统省电策略清理,或被手动强行停止——AI 干活期间尽量留在应用内,或在系统设置里锁定本应用、关闭电池优化。"
        }
        return result.trim().ifBlank {
            "AI 没有返回说明。这类中断通常是:应用切到后台后被系统清理或被强行停止,任务没能跑完。"
        }
    }

    /**
     * AI 会话自动命名:会话标题仍是默认「新对话」时,用首条用户消息生成一个简短标题。
     * 在 AI 开始回答的同时异步进行(独立轻量调用:禁工具、15 秒超时,不占主回复的上下文)。
     * 失败/超时静默保留「新对话」;用户手动改过名的会话一律不动。返回新标题(未命名返回 null)。
     */
    suspend fun autoTitleAiConversation(ctx: Context, userId: Long, firstMessage: String): String? {
        return try {
            val convId = AiConversationSession.activeConvId
            if (convId <= 0L) return null
            val conv = loadAiConvs(ctx).firstOrNull { it.id == convId } ?: return null
            if (conv.title != "新对话") return null   // 已有标题(手动命名/已命名过),绝不覆盖
            val history = mutableListOf(
                "system" to "你是对话标题生成器。根据用户发来的第一条消息,输出一个不超过 12 个字的简短标题,概括他的需求或话题。只输出标题本身:不要任何解释、不要引号、不要以句号等标点结尾。消息太短或无法概括时,就取其中最关键的一个词组作为标题。",
                "user" to firstMessage.take(500)
            )
            var title = ""
            kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                streamChat(
                    ctx = ctx, userId = userId, history = history,
                    stopCheck = { false },
                    onDelta = {},
                    onDone = { _, full, _, _ -> title = full },
                    onError = {},
                    treatEmptyAsError = false,
                    enableAgentTools = false
                )
            }
            val cleaned = title.trim()
                .removePrefix("\"").removeSuffix("\"")
                .removePrefix("「").removeSuffix("」")
                .trim().replace(Regex("\\s+"), " ")
            val finalTitle = cleaned.take(12)
            if (finalTitle.isNotBlank() && finalTitle != "新对话") {
                renameAiConversation(ctx, convId, finalTitle)
                LocalChatRefreshTick.bump()
                finalTitle
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * 429 限流提示总线:streamChat 被 429 时把「限流文案 + 倒计时 + 再次尝试」广播给 UI。
     * 倒计时由 notify 启动的协程每秒刷新文案;倒计时结束(或用户点「再次尝试」)后,
     * streamChat 端的等待被唤醒并立刻重试。同一时刻只有一条限流提示,全局单槽安全。
     */
    object RateLimitBus {
        private val _text = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val text: kotlinx.coroutines.flow.StateFlow<String?> get() = _text
        private var resume: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        private var countdownJob: kotlinx.coroutines.Job? = null

        /** streamChat 429 时调用:count=累计第几次限流(无上限),waitSeconds=本次等待秒数(≤30) */
        fun notify(count: Int, waitSeconds: Long, r: kotlinx.coroutines.CompletableDeferred<Unit>) {
            resume = r
            countdownJob?.cancel()
            countdownJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                for (i in waitSeconds downTo 1) {
                    _text.value = "API 被限流(第 $count 次),$i 秒后自动重试"
                    kotlinx.coroutines.delay(1000)
                }
                _text.value = null
            }
        }

        /** 用户点「再次尝试」:取消倒计时,立刻唤醒 streamChat 重试 */
        fun retryNow() {
            resume?.complete(Unit)
            countdownJob?.cancel()
            _text.value = null
        }

        /** 最终失败/完成时清理,防止横幅残留 */
        fun clear() {
            resume = null
            countdownJob?.cancel()
            _text.value = null
        }
    }

    /** 解析 agent_review 的返回结果,生成随消息持久化的「验收 Agent 报告」文本块;非验收结果返回 null */
    fun reviewReportBlock(resultJson: String): String? {
        return try {
            val o = JSONObject(resultJson)
            val verdict = o.optString("verdict", "").trim()
            if (verdict.isBlank()) null
            else buildString {
                append("┌ 验收 Agent 报告 · 独立核验\n")
                if (verdict == "SKIPPED") {
                    append("├ 结论:用户跳过验收\n")
                    append("└ 核验要点:(未执行任何核验,成果未经独立确认)")
                } else {
                    append("├ 结论:$verdict\n")
                    val detail = o.optString("detail", "").replace(Regex("\\s+"), " ").trim()
                    append("└ 核验要点:")
                    append(if (detail.isBlank()) "(验收 Agent 未产出细节)" else detail.take(300))
                }
            }
        } catch (_: Exception) { null }
    }

    /** 解析 agent_fork 的返回结果,生成随消息持久化的「并行派发报告」文本块;非派发结果返回 null */
    fun forkReportBlock(resultJson: String): String? {
        return try {
            val o = JSONObject(resultJson)
            val arr = o.optJSONArray("results") ?: return null
            buildString {
                append("┌ 并行派发报告 · ${arr.length()} 个子执行 Agent\n")
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val task = r.optString("task", "").replace(Regex("\\s+"), " ").trim()
                    val ok = r.optBoolean("ok")
                    append("├ [${i + 1}] ${if (ok) "完成" else "失败"} · ${task.take(80)}\n")
                    if (!ok) {
                        append("│    ${r.optString("summary", "").replace(Regex("\\s+"), " ").trim().take(150)}\n")
                    }
                }
                append("└ 各子任务详情见执行者的汇总答复")
            }
        } catch (_: Exception) { null }
    }

    /**
     * agent_review 工具实现:启动一个完全独立上下文的验收 Agent 对执行者成果做核验。
     * - 验收者权限:只读白名单内全自动(不打扰用户审批),白名单外一律拒绝 → 机制上禁止验收者改现场;
     * - 验收过程对用户不可见(无流式输出),只在执行者的消息流里体现为一个工具调用状态行;
     * - AgentTaskGuard 为引用计数设计,嵌套调用安全。
     * 返回给执行者的 JSON:ok / verdict(PASS|FAIL|UNVERIFIED) / detail(验收员完整报告)
     */
    private suspend fun doAgentReview(ctx: Context, userId: Long, call: AgentToolCall): String {
        // 兜底:验收是多 Agent 模式专属。非多 Agent 模式下(如模型用文本协议硬塞 JSON 调用)直接拒绝。
        if (!execActive(ctx, userId, EXEC_MULTI_AGENT)) {
            return JSONObject().put("ok", false)
                .put("error", "agent_review 仅在多 Agent 模式下可用;当前未开启多 Agent 模式,无需验收,直接向用户交付结果即可。").toString()
        }
        val args = argsOf(call)
        val task = args.optString("task", "").trim()
        val summary = args.optString("summary", "").trim()
        if (task.isBlank() && summary.isBlank()) {
            return JSONObject().put("ok", false)
                .put("error", "task 与 summary 不能同时为空:请传入用户原始任务与你的工作汇报").toString()
        }
        // 验收者的独立历史:全新上下文。注意:这里【不再】重置跳过标志——用户一旦点「跳过审查」,
        // 本轮任务内所有后续 agent_review 调用都立即被跳过(防模型自作主张再次发起验收,
        // 让用户以为跳过无效、任务一直卡着);标志由新一轮任务开始时(会话页发送路径)统一清零。
        var skippedByUser = false
        AgentReviewBus.update("验收 Agent 已接手 · 建立独立上下文")
        val reviewerHistory = mutableListOf(
            "system" to REVIEWER_SYSTEM_PROMPT,
            "user" to buildString {
                append("原始任务:\n").append(task.ifBlank { "(详见下方工作汇报)" }).append("\n\n")
                append("执行 Agent 的工作汇报:\n").append(summary.ifBlank { "(无)" }).append("\n\n")
                append("请立即开始独立核验,并按规定格式输出验收结论。")
            }
        )
        var reviewerErr: String? = null
        var reviewRound = 0
        // 验收总时长硬预算:验收 Agent 卡流/反复慢轮时,任务绝不被验收环节无限拖死。
        // 正常验收 1~2 分钟内完成,5 分钟是极宽松上限;超时按 UNVERIFIED 收场并如实告知执行者。
        var reviewTimedOut = false
        val outcome = try {
            kotlinx.coroutines.withTimeoutOrNull(300_000L) {
                runAgentChatLoop(
                ctx = ctx, userId = userId, history = reviewerHistory,
                // 验收者工具门控:只读白名单全自动放行(验收不该骚扰用户),白名单外一律拒绝
                onNeedApproval = { tool, _ -> tool in REVIEWER_READ_TOOLS },
                // 验收者自身不再触发强制验收(否则会要求验收者验收自己)
                enforceReview = false,
                // 验收者轮数硬顶:验收是核验不是重做,5 轮足够;防止模型反复调只读工具无限跑轮
                maxRoundsOverride = 5,
                // 验收是嵌套子循环,不应触发用户级任务守护(交付契约/收尾)
                enableTaskCompletion = false,
                round = { nativeCalls, _, toolChoice ->
                    // 用户点了「跳过审查」:本轮不再发起请求,直接中止整个验收循环
                    if (reviewSkipFlag.get()) throw ReviewSkippedByUserException()
                    reviewRound++
                    AgentReviewBus.update("第 $reviewRound 轮独立核验中…")
                    var done = false
                    var ft = ""
                    var err = false
                    var finishReason = ""
                    var lastErrMsg = ""
                    streamChat(
                        ctx = ctx, userId = userId, history = reviewerHistory,
                        // 流式中途感知跳过请求,立即掐断当前请求,不必等本轮跑完
                        stopCheck = { reviewSkipFlag.get() },
                        onReasoningDelta = {},
                        onDelta = {},
                        onDone = { _, full, _, _ -> done = true; ft = full },
                        onError = { err = true; reviewerErr = it; lastErrMsg = it },
                        nativeToolAccumulator = nativeCalls,
                        enableAgentTools = true,
                        toolChoice = toolChoice,
                        onFinish = { r -> finishReason = r }
                    )
                    if (nativeCalls.isNotEmpty()) {
                        val acts = nativeCalls.joinToString("、") { it.name }
                        AgentReviewBus.update("第 $reviewRound 轮 · 正在核验:${acts.take(46)}")
                    }
                    // 本轮结束后再次感知跳过(流式已被掐断时 done=false,同样走跳过出口)
                    if (reviewSkipFlag.get()) throw ReviewSkippedByUserException()
                    AgentRoundResult(done = done && !err, fullText = ft, finishReason = finishReason, retriable = if (err) AgentTaskCompletion.isRetriable(lastErrMsg) else false)
                }
            ) ?: run { reviewTimedOut = true; null }
            }
        } catch (e: ReviewSkippedByUserException) {
            skippedByUser = true
            null
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            android.util.Log.e("AiChatManager", "agent_review 验收循环异常", e)
            reviewerErr = e.message ?: e.javaClass.simpleName
            null
        } finally {
            AgentReviewBus.update(null)
        }
        val reviewerText = outcome?.accumulated?.trim().orEmpty()
        val verdict = when {
            skippedByUser -> "SKIPPED"
            outcome == null || reviewerText.isBlank() -> "UNVERIFIED"
            // 宽容解析:不再只认死格式「验收结论:PASS/FAIL」,见 parseReviewVerdict 注释
            else -> parseReviewVerdict(reviewerText)
        }
        return JSONObject().apply {
            put("ok", verdict != "UNVERIFIED")
            put("verdict", verdict)
            when (verdict) {
                "PASS" -> put("hint", "验收通过。给用户的最终总结里要标注「验收结论:PASS」并转述验收员的核验要点。")
                "FAIL" -> put("hint", "验收不通过。请按 detail 里的问题清单逐条修复,然后再次调用 agent_review 复验(同一任务最多复验 2 次)。")
                "SKIPPED" -> put("hint", "用户主动跳过了本次独立验收(验收 Agent 未产出任何核验结论)。给用户的最终总结必须如实说明「用户跳过了验收,本次成果未经独立核验」,不得声称验收通过,也不得谎称验收不通过——你只负责如实告知,然后立即正常交付你的总结。不要再次调用 agent_review:用户已明确跳过,再调用会立即再次被跳过,纯属浪费时间。")
                else -> when {
                    reviewTimedOut -> put("hint", "独立验收超时(超过 5 分钟仍未完成,已强制中止)。请如实告知用户「验收超时,本次成果未经独立核验」,不要谎称验收通过,然后立即交付你的总结,不要再发起验收。")
                    reviewerErr != null -> put("hint", "验收 Agent 运行失败($reviewerErr),请如实告知用户本次未能完成独立验收,不要谎称验收通过。")
                    else -> put("hint", "验收 Agent 未按规定格式输出结论,请如实告知用户本次验收结果不可判定,不要谎称验收通过。")
                }
            }
            put("detail", reviewerText.take(6000))
        }.toString()
    }

    /**
     * 从验收 Agent 输出文本中宽容解析结论:PASS / FAIL / UNVERIFIED。
     * 旧实现只匹配「验收结论:PASS/FAIL」死格式,模型输出稍变(全角冒号、空格、
     * 「结论:通过/不通过」「verdict: PASSED/FAILED」「✅/❌」、把结论写在最后而非第一行)
     * 就会整段 UNVERIFIED,表现为「不知道什么情况验收不了」。
     * 解析策略:
     *  ① 优先扫「结论/verdict/result/验收」行(从后往前,结论通常在最末),在行内判定;
     *  ② 找不到结论行时退化到全文末尾 600 字符做关键句匹配(否定词优先,保守判 FAIL);
     *  ③ 判定时先剔除「未发现…不合格/问题」等否定短语,避免「未发现不合格项」被误判为 FAIL;
     *  ④ 两轮都不中 → UNVERIFIED(仍走「不可判定」如实告知分支)。
     */
    private fun parseReviewVerdict(text: String): String {
        val passZh = listOf("验收结论:通过", "结论:通过", "验收通过", "核验通过", "验证通过", "检查通过", "审查通过", "通过验收", "判定通过", "✅", "✔", "✓")
        val failZh = listOf(
            "验收结论:不通过", "结论:不通过", "验收不通过", "核验不通过", "验证不通过", "检查不通过", "审查不通过",
            "未通过", "不合格", "验收失败", "核验失败", "存在问题", "需要修改", "需修改", "需修复", "必须修复", "不满足", "打回", "❌", "✘", "✗"
        )
        val passEn = listOf("验收结论:pass", "结论:pass", "verdict:pass", "result:pass", "verdict:passed", "passed")
        val failEn = listOf("验收结论:fail", "结论:fail", "verdict:fail", "result:fail", "verdict:failed", "failed", "failure")
        val passWord = Regex("\\bpass(ed)?\\b", RegexOption.IGNORE_CASE)
        val failWord = Regex("\\bfail(ed|ure)?\\b", RegexOption.IGNORE_CASE)
        fun norm(s: String) = s.trim().lowercase().replace(" ", "").replace("：", ":")
        fun hit(seg: String): String? {
            // 剔除「未发现…不合格/问题」「无不合格」等否定前缀短语,避免把 PASS 报告里的免责句误判为 FAIL
            val cleaned = seg.lowercase()
                .replace(Regex("未(?:发现|存在|出现|有|见)?(?:任何|明显|重大)?(?:不合格|不通过|问题|缺陷)"), "")
                .replace(Regex("无(?:任何|明显|重大)?(?:不合格|问题|缺陷)"), "")
                .replace(Regex("没有(?:任何|明显|重大)?(?:不合格|问题|缺陷)"), "")
                .replace(Regex("不存在(?:任何|明显|重大)?(?:不合格|问题|缺陷)"), "")
            val n = norm(cleaned)
            if (failZh.any { n.contains(it) } || failEn.any { n.contains(it) } || failWord.containsMatchIn(cleaned)) return "FAIL"
            if (passZh.any { n.contains(it) } || passEn.any { n.contains(it) } || passWord.containsMatchIn(cleaned)) return "PASS"
            return null
        }
        // ① 结论行优先:包含 结论/verdict/result/验收 的行(从后往前,结论通常在最末)
        for (line in text.lines().asReversed()) {
            val l = line.lowercase()
            if (l.contains("结论") || l.contains("verdict") || l.contains("result") || l.contains("验收")) {
                hit(line)?.let { return it }
            }
        }
        // ② 退化:全文末尾 600 字符关键句匹配
        hit(text.takeLast(600))?.let { return it }
        return "UNVERIFIED"
    }

    /** 规划者系统提示词:只读权限,只出计划不动手;格式固定,便于执行者逐条执行 */
    private const val PLANNER_SYSTEM_PROMPT = """你是一个独立规划 Agent(规划者),任务是接收一个任务描述并产出高质量执行计划。你只有只读权限,绝不动手执行任务本身——你写不出计划交付物,只能查看现状(list_files/read_file 等)让计划更贴合实际。

你的输出必须严格遵循以下格式(第一行开始,不要有开场白):

【执行计划】
步骤 1: ...(每步一个动作,写明对象与预期结果,若需要工具请点名工具名)
步骤 2: ...
(依实际复杂度列出,简单任务 2-3 步,复杂任务可到 8-10 步)

【风险与注意】
- ...(可能出错的点、前置依赖、边界情况;没有则写"无明显风险")

【计划依据】
...(你实际查看了什么/基于什么做出判断;若完全基于任务描述推断,如实说明)

规则:
1. 必须先实际查看工作区现状(list_files 等)再规划,不要凭空假设文件结构。
2. 每个步骤要可独立执行、可独立验证,粒度以"一次工具调用或一组紧密操作"为宜。
3. 计划只覆盖「怎么把任务做完」,不代替验收——执行者完成后仍须走 agent_review。
4. 任务信息不足以规划时,在【执行计划】区第一行写「信息不足」,并在【风险与注意】里列出需要用户澄清的问题。"""

    /**
     * agent_plan 工具实现:把任务交给一个完全独立上下文的规划 Agent 拆解成执行计划。
     * 规划者只有只读白名单权限(与验收者同一套门控),产出纯文本计划返回给执行者;
     * 规划失败/信息不足时如实返回,不编造计划。
     */
    private suspend fun doAgentPlan(ctx: Context, userId: Long, call: AgentToolCall): String {
        // 兜底:规划是多 Agent 模式专属,非多 Agent 模式直接拒绝(防文本协议绕过)。
        if (!execActive(ctx, userId, EXEC_MULTI_AGENT)) {
            return JSONObject().put("ok", false)
                .put("error", "agent_plan 仅在多 Agent 模式下可用;当前未开启多 Agent 模式,请自行规划并直接执行。").toString()
        }
        val args = argsOf(call)
        val task = args.optString("task", "").trim()
        if (task.isBlank()) {
            return JSONObject().put("ok", false).put("error", "task 不能为空:请传入用户的原始任务描述").toString()
        }
        AgentReviewBus.update("规划 Agent 已接手 · 建立独立上下文")
        val plannerHistory = mutableListOf(
            "system" to PLANNER_SYSTEM_PROMPT,
            "user" to "任务描述:\n$task\n\n请立即开始:先查看工作区现状,再产出执行计划。"
        )
        var plannerErr: String? = null
        var planRound = 0
        val outcome = try {
            runAgentChatLoop(
                ctx = ctx, userId = userId, history = plannerHistory,
                // 规划者与验收者同一套只读白名单门控;自身不触发强制验收/规划
                onNeedApproval = { tool, _ -> tool in REVIEWER_READ_TOOLS },
                enforceReview = false,
                // 规划是嵌套子循环,不应触发用户级任务守护(交付契约/收尾)
                enableTaskCompletion = false,
                round = { nativeCalls, _, toolChoice ->
                    planRound++
                    AgentReviewBus.update("第 $planRound 轮规划中…")
                    var done = false
                    var ft = ""
                    var err = false
                    var finishReason = ""
                    var lastErrMsg = ""
                    streamChat(
                        ctx = ctx, userId = userId, history = plannerHistory,
                        stopCheck = { false },
                        onReasoningDelta = {},
                        onDelta = {},
                        onDone = { _, full, _, _ -> done = true; ft = full },
                        onError = { err = true; plannerErr = it; lastErrMsg = it },
                        nativeToolAccumulator = nativeCalls,
                        enableAgentTools = true,
                        toolChoice = toolChoice,
                        onFinish = { r -> finishReason = r }
                    )
                    if (nativeCalls.isNotEmpty()) {
                        val acts = nativeCalls.joinToString("、") { it.name }
                        AgentReviewBus.update("第 $planRound 轮 · 正在勘察:${acts.take(46)}")
                    }
                    AgentRoundResult(done = done && !err, fullText = ft, finishReason = finishReason, retriable = if (err) AgentTaskCompletion.isRetriable(lastErrMsg) else false)
                }
            )
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            android.util.Log.e("AiChatManager", "agent_plan 规划循环异常", e)
            plannerErr = e.message ?: e.javaClass.simpleName
            null
        } finally {
            AgentReviewBus.update(null)
        }
        val planText = outcome?.accumulated?.trim().orEmpty()
        val insufficient = planText.replace(" ", "").startsWith("【执行计划】信息不足")
        return JSONObject().apply {
            put("ok", outcome != null && planText.isNotBlank() && !insufficient)
            when {
                plannerErr != null -> {
                    put("error", "规划 Agent 运行失败($plannerErr)。请基于你自己的判断继续执行任务,并向用户如实说明本次未能获得独立规划,不要假装有计划。")
                }
                insufficient -> {
                    put("error", "规划 Agent 判定信息不足。请把 detail 里「需要澄清的问题」转述给用户,等待用户补充后再继续。")
                    put("detail", planText.take(6000))
                }
                planText.isBlank() -> {
                    put("error", "规划 Agent 未产出计划。请基于你自己的判断继续执行任务,并如实说明本次未能获得独立规划。")
                }
                else -> {
                    put("plan", planText.take(6000))
                    put("hint", "已获得独立规划。请按【执行计划】逐步执行(步骤可根据实际情况微调,重大偏差要向用户说明);完成后仍必须调用 agent_review 验收。")
                }
            }
        }.toString()
    }

    /** 并行子执行者的系统提示词:专注单个子任务,不碰元工具,只在允许的路径范围内动手 */
    private const val FORK_WORKER_PROMPT =
        "你是一个被并行派发的执行 Agent,与其他若干执行 Agent 同时工作,各自负责一个互不重叠的子任务。规则:\n" +
        "(1) 专注完成派发给你的这一个子任务,不要越界去做其他子任务的事,也不要等待或协调其他 Agent。\n" +
        "(2) 只在子任务描述声明的路径范围内创建/修改文件——并行中你无法感知别人的写入,越界会互相覆盖。\n" +
        "(3) 你是末端执行者:禁止调用 agent_plan / agent_review / agent_fork 这些元工具。\n" +
        "(4) 完成后输出工作汇报:做了什么、动了哪些文件(含路径)、结果如何;如实汇报,失败就写失败原因,不许谎报。"

    /**
     * agent_fork 工具实现:把多个互不依赖的子任务并行派发给独立上下文的执行 Agent(多协程并发)。
     * - 每个子执行者:全新上下文 + 全套工具(写权限),仅受 FORK_WORKER_PROMPT 约束;
     *   审批在派发动作本身(agent_fork 调用)发生,子任务内部免逐个审批,否则单槽审批桥会互相覆盖。
     * - 子执行者不做验收(enforceReview=false),调度者拿到汇总后统一走 agent_review。
     * - 上限 4 个并发,防止请求风暴触发限流。
     */
    private suspend fun doAgentFork(ctx: Context, userId: Long, call: AgentToolCall): String {
        // 兜底:并行派发是多 Agent 模式专属,非多 Agent 模式直接拒绝(防文本协议绕过)。
        if (!execActive(ctx, userId, EXEC_MULTI_AGENT)) {
            return JSONObject().put("ok", false)
                .put("error", "agent_fork 仅在多 Agent 模式下可用;当前未开启多 Agent 模式,请逐个串行执行子任务。").toString()
        }
        val args = argsOf(call)
        val tasks = args.optJSONArray("tasks")
        if (tasks == null || tasks.length() == 0) {
            return JSONObject().put("ok", false)
                .put("error", "tasks 不能为空:请传入子任务描述数组,每项含「做什么 + 只允许操作的路径范围」").toString()
        }
        val n = tasks.length().coerceAtMost(4)
        if (n < tasks.length()) {
            android.util.Log.w(TAG, "agent_fork:请求派发 ${tasks.length()} 个子任务,超出上限,截断为 $n 个")
        }
        AgentForkBus.start(n)
        val results = try {
            kotlinx.coroutines.coroutineScope {
                (0 until n).map { i ->
                    async {
                        val taskDesc = tasks.optString(i).trim()
                        val workerHistory = mutableListOf(
                            "system" to FORK_WORKER_PROMPT,
                            "user" to "子任务:\n$taskDesc\n\n请立即执行,完成后输出工作汇报。"
                        )
                        val outcome = try {
                            runAgentChatLoop(
                                ctx = ctx, userId = userId, history = workerHistory,
                                // 派发动作本身已经过审批(或非手动模式),子任务内部免逐个审批
                                onNeedApproval = { _, _ -> true },
                                // 子执行者不做验收兜底,调度者统一验收
                                enforceReview = false,
                                // 子执行者是嵌套子循环,不应触发用户级任务守护(交付契约/收尾)
                                enableTaskCompletion = false,
                                round = { nativeCalls, _, toolChoice ->
                                    var done = false
                                    var ft = ""
                                    var err = false
                                    var finishReason = ""
                                    var lastErrMsg = ""
                                    streamChat(
                                        ctx = ctx, userId = userId, history = workerHistory,
                                        stopCheck = { false },
                                        onReasoningDelta = {},
                                        onDelta = {},
                                        onDone = { _, full, _, _ -> done = true; ft = full },
                                        onError = { err = true; lastErrMsg = it },
                                        nativeToolAccumulator = nativeCalls,
                                        enableAgentTools = true,
                                        toolChoice = toolChoice,
                                        onFinish = { r -> finishReason = r }
                                    )
                                    AgentRoundResult(done = done && !err, fullText = ft, finishReason = finishReason, retriable = if (err) AgentTaskCompletion.isRetriable(lastErrMsg) else false)
                                }
                            )
                        } catch (e: Exception) {
                            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                            android.util.Log.e("AiChatManager", "agent_fork 子任务 $i 执行异常", e)
                            null
                        }
                        AgentForkBus.workerDone()
                        outcome?.accumulated?.trim().orEmpty()
                    }
                }.map { deferred -> deferred.await() }
            }.mapIndexed { i, text ->
                JSONObject()
                    .put("index", i)
                    .put("task", tasks.optString(i).trim())
                    .put("ok", text.isNotBlank())
                    .put("summary", if (text.isBlank()) "(子执行 Agent 无输出,视为失败)" else text.take(1500))
            }
        } finally {
            AgentForkBus.clear()
        }
        val failed = results.filter { !it.optBoolean("ok") }.map { it.optInt("index") + 1 }
        return JSONObject().apply {
            put("ok", failed.isEmpty())
            put("results", JSONArray().apply { results.forEach { put(it) } })
            put("hint", if (failed.isEmpty()) {
                "并行子任务已全部完成(共 $n 个)。请根据各子任务汇报继续后续步骤,整体完成后必须调用 agent_review 验收整体成果。"
            } else {
                "子任务 ${failed.joinToString("、")} 执行失败或无输出,请自行补做这些部分或如实告知用户;其余部分继续,整体完成后必须调用 agent_review 验收。"
            })
        }.toString()
    }

    // ===== 生成模式:图片 / 视频生成(personal 直连 Agnes,third_party 走后端 /api/ai/image|video) =====
    private const val IMAGE_MODEL = "agnes-image-2.1-flash"
    private const val VIDEO_MODEL = "agnes-video-v2.0"

    /** 生成专用长读超时客户端:图片/视频生成耗时可达 60s+,共享客户端 15s 会超时。进程内只建一次。 */
    private val genClient by lazy { com.aurora.chat.data.api.HttpClient.newClientWithTimeouts(180) }

    data class GenResult(
        val b64: String? = null,
        val url: String? = null,
        val taskId: String? = null,
        val mediaPath: String? = null,
        val status: String? = null,
        val progress: Int = 0
    )

    private suspend fun requestGeneration(
        ctx: Context, userId: Long, prompt: String, kind: String, size: String
    ): GenResult {
        val mode = getMode(ctx, userId)
        val (key, baseUrl, _) = resolveConfig(ctx, userId)
        val model = if (kind == "video") VIDEO_MODEL else IMAGE_MODEL
        val body = org.json.JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            if (kind == "image") {
                put("size", size)
                // 标准 OpenAI 文生图参数:请求 base64。Agnes 的 return_base64 会报错,改用 response_format。
                put("response_format", "b64_json")
            }
        }
        val endpoint: String
        val auth: String
        if (mode == "third_party") {
            endpoint = AuroraApi.serverUrl.trimEnd('/') + (if (kind == "image") "/api/ai/image" else "/api/ai/video")
            auth = AuroraApi.authToken.orEmpty()
        } else {
            endpoint = baseUrl.trimEnd('/') + (if (kind == "image") "/images/generations" else "/video/generations")
            auth = key
        }
        val req = okhttp3.Request.Builder().url(endpoint)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Authorization", "Bearer $auth")
            .build()
        // 生成是慢接口(可达 60s+),用独立长读超时客户端,避免共享客户端 15s 读超时
        val resp = withContext(Dispatchers.IO) { genClient.newCall(req).execute() }
        val code = resp.code
        val respText = try { resp.body?.string() ?: "" } catch (_: Exception) { "" }
        resp.close()
        if (code !in 200..299) {
            throw RuntimeException("生成${if (kind == "image") "图片" else "视频"}失败($code): ${respText.take(200)}")
        }
        var arr: org.json.JSONArray? = null
        try {
            val root = org.json.JSONObject(respText)
            var holder = root
            if (mode == "third_party" && !root.isNull("data") && root.opt("data") is org.json.JSONObject) {
                holder = root.getJSONObject("data")
            }
            if (!holder.isNull("data") && holder.opt("data") is org.json.JSONArray) {
                arr = holder.getJSONArray("data")
            }
        } catch (_: Exception) { arr = null }
        // 视频为异步任务:提交成功返回 task_id(无 data 数组),据此进入轮询
        if (kind == "video") {
            val t = try {
                val root = org.json.JSONObject(respText)
                var holder = root
                if (mode == "third_party" && !root.isNull("data") && root.opt("data") is org.json.JSONObject) {
                    holder = root.getJSONObject("data")
                }
                holder.optString("task_id", "").ifBlank { null }
            } catch (_: Exception) { null }
            if (t != null) {
                com.aurora.chat.ErrorReporter.debug("AiGen", "mode=$mode kind=video 提交成功 task=$t")
                return GenResult(taskId = t)
            }
        }
        if (arr == null || arr.length() == 0) {
            // 响应里没有 data 数组:把原始响应写进日志系统,便于看清实际结构
            com.aurora.chat.ErrorReporter.warn("AiGen", "mode=$mode kind=$kind http=$code 响应缺少 data,原始返回=${respText.take(600).replace("\n", " ")}")
            throw RuntimeException("生成${if (kind == "image") "图片" else "视频"}响应缺少数据")
        }
        val first = arr.getJSONObject(0)
        // 后端已把图片存到本服务 chat_media 时直接给 media_path(走 /chat-media 渲染,避免 2MB base64 传输)
        val mediaPath = if (first.has("media_path")) first.optString("media_path", "").ifBlank { null } else null
        // b64_json 可能为空串(Agnes 默认返回空),空时按无处理,回退到 url
        val b64 = if (first.has("b64_json")) first.optString("b64_json", "").ifBlank { null } else null
        val url = if (first.has("url")) first.optString("url", "").ifBlank { null } else null
        val task = if (first.has("task_id")) first.getString("task_id") else null
        // 写入 App 日志系统(设置→错误日志可查看/导出),便于定位生成结果
        com.aurora.chat.ErrorReporter.debug(
            "AiGen",
            "mode=$mode kind=$kind http=$code media=${mediaPath ?: "-"} b64_len=${b64?.length ?: -1} url_len=${url?.length ?: -1} task=${task ?: "-"}${if (b64 == null && url == null && mediaPath == null) " resp=${respText.take(400).replace("\n", " ")}" else ""}"
        )
        return GenResult(b64, url, task, mediaPath)
    }

    suspend fun generateImage(ctx: Context, userId: Long, prompt: String, size: String = "1024x1024"): GenResult =
        requestGeneration(ctx, userId, prompt, "image", size)

    /** 视频生成(异步任务):提交 → 轮询状态直到完成 → 返回已落盘服务器的 media_path。仅第三方模式支持。 */
    suspend fun generateVideo(ctx: Context, userId: Long, prompt: String): GenResult {
        if (getMode(ctx, userId) != "third_party") {
            throw RuntimeException("视频生成仅支持第三方模式")
        }
        val submitted = requestGeneration(ctx, userId, prompt, "video", "1024x1024")
        val taskId = submitted.taskId ?: throw RuntimeException("视频提交失败:未返回任务ID")
        val deadline = System.currentTimeMillis() + 5 * 60 * 1000 // 最多等 5 分钟
        var last: GenResult = submitted
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(4000)
            last = queryVideoStatus(ctx, userId, taskId)
            when (last.status?.lowercase()) {
                "completed", "succeeded", "done" -> return last
                "failed", "error", "cancelled", "canceled" ->
                    throw RuntimeException("视频生成失败${if (last.progress > 0) "(${last.progress}%)" else ""}")
            }
        }
        throw RuntimeException("视频生成超时,请稍后重试")
    }

    /** 轮询视频任务状态:走后端 /api/ai/video/status/<taskId>(仅第三方模式)。 */
    private suspend fun queryVideoStatus(ctx: Context, userId: Long, taskId: String): GenResult {
        val url = AuroraApi.serverUrl.trimEnd('/') + "/api/ai/video/status/" + taskId
        val req = okhttp3.Request.Builder().url(url)
            .header("Authorization", "Bearer ${AuroraApi.authToken.orEmpty()}")
            .build()
        val resp = withContext(Dispatchers.IO) { genClient.newCall(req).execute() }
        val code = resp.code
        val text = try { resp.body?.string() ?: "" } catch (_: Exception) { "" }
        resp.close()
        if (code !in 200..299) throw RuntimeException("查询视频状态失败($code)")
        val data = try {
            val root = org.json.JSONObject(text)
            root.optJSONObject("data") ?: root
        } catch (_: Exception) { org.json.JSONObject() }
        val status = data.optString("status", "").ifBlank { null }
        val mediaPath = data.optString("media_path", "").ifBlank { null }
        val progress = data.optInt("progress", 0)
        com.aurora.chat.ErrorReporter.debug(
            "AiGen",
            "video task=$taskId status=${status ?: "-"} progress=$progress media=${mediaPath ?: "-"}"
        )
        return GenResult(taskId = taskId, status = status, mediaPath = mediaPath, progress = progress)
    }
}
