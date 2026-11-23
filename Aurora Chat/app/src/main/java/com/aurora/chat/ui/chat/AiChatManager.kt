package com.aurora.chat.ui.chat

import android.content.Context
import android.content.Intent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.util.Log
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.Calendar
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
    /** 无数据看门狗阈值(ms):收到首个 data 行后,超过此时长无任何新 data/':' 行才判停滞。
     *  180s 远宽于 DeepSeek 推理模型的首 token 长时延(TTFT)与长思考间隙,避免把「合法长思考」误杀成卡死。 */
    const val STALL_WATCHDOG_TIMEOUT_MS = 180_000L

    /** 纯函数化的停滞判定:仅当已武装(lastDataTs>0,即已收到首个 data 行)且距其超过阈值才成立;
     *  TTFT 期间 lastDataTs 恒为 0,故不判停滞,豁免慢首包。 */
    internal fun isStallTimeout(lastDataTs: Long, now: Long): Boolean =
        lastDataTs > 0L && now - lastDataTs > STALL_WATCHDOG_TIMEOUT_MS


    // ===== 官方 API 固定配置 =====
    // 安全（密钥清理）：应用内置的 DeepSeek 密钥已移除——内置密钥随 APK 分发，
    // 任何人反编译提取后即可无限制盗用（平台侧额度/风控全部作废）。
    // 官方模式已从 UI 移除（getMode 将旧值 official 统一映射为 third_party 后端中转），
    // 这里只保留非敏感的 BaseURL / 模型名作兜底。
    private const val OFFICIAL_BASE_URL = "https://api.deepseek.com/v1"
    private const val OFFICIAL_MODEL = "deepseek-v4-flash"
    private const val KEY_SERVER_TOKEN_BALANCE = "ai_server_token_balance"



    private const val TAG = "AiChatManager"

    /** 应用级后台协程域,供后台调度使用。进程存活时持续有效。 */
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

    /**
     * [appendReasoningSegment] 的增量缓存（性能·超长思考防卡）。
     *
     * 关键事实：流式期间被反复调用的是 [appendReasoningSegment]，其中 `accumulated`（已定稿的思考段）
     * **恒定不变**，只有 `segment`（本轮累积思考）在增长。而原实现每调用一次就要：
     *   · 对整段 accumulated 做 split + trim + filter（O(总思考长度)）
     *   · 再 joinToString 拼回（又一次 O(总思考长度)）
     * 于是每个 reasoning 增量都分配一份「几万字符的 List + String」。思考越长分配越大 ——
     * 这正是「一大堆思考过程就卡」的核心来源之一（每 token 一次，30~60 次/秒 → GC 持续高压）。
     *
     * 这里把 accumulated 的切分结果与「除最后一段外的前缀(head)」缓存下来：
     * 命中后替换分支只需要一次 `head + clean`，不再有任何 split/join 分配。
     */
    private class ReasoningSegCache(val key: String, val segs: List<String>, val head: String)

    @Volatile
    private var reasoningSegCache: ReasoningSegCache? = null

    /** 带缓存的切分：accumulated 不变时直接复用上次结果（含预先算好的 head）。 */
    private fun splitReasoningSegmentsCached(text: String): List<String> {
        val c = reasoningSegCache
        if (c != null && c.key == text) return c.segs
        val segs = splitReasoningSegments(text)
        // head = 除最后一段外的所有段 + 分隔符；替换最后一段时直接 head + clean，省掉一次全量 join
        val head = if (segs.size <= 1) ""
        else segs.subList(0, segs.size - 1).joinToString(REASONING_SEGMENT_SEP) + REASONING_SEGMENT_SEP
        reasoningSegCache = ReasoningSegCache(text, segs, head)
        return segs
    }

    /** 往已有思考文本追加一个新思考段(分段数不超过 N,超出丢弃最旧)。
 *  若新段是最后一段的扩展(包含最后一段全部内容,如模型跨轮累积式思考),则替换最后一段而非追加,避免重复。 */
    fun appendReasoningSegment(accumulated: String, segment: String, maxSegments: Int = 8): String {
        val clean = segment.trim()
        if (clean.isEmpty()) return accumulated
        if (accumulated.isEmpty()) return clean
        // 【性能】命中缓存：accumulated 在流式期间恒定，只有 clean 在增长，免去每 token 的 split/join 分配
        val segs = splitReasoningSegmentsCached(accumulated)
        val last = segs.lastOrNull()
        val isSuperset = last != null && clean.contains(last)
        if (isSuperset && segs.size <= maxSegments) {
            // 快速路径：替换最后一段 = head + clean（段数不变，故 maxSegments 约束不会被突破）
            val c = reasoningSegCache
            val head = if (c != null && c.key == accumulated) c.head else ""
            return if (head.isEmpty()) clean else head + clean
        }
        // 慢路径（首段 / 追加新段 / 段数越界）：与旧实现完全等价
        val merged = if (isSuperset) segs.toMutableList().also { it[it.lastIndex] = clean }
        else (segs + clean).toMutableList()
        val keep = if (merged.size > maxSegments) merged.takeLast(maxSegments) else merged
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

    /**
     * 组装工具状态块。字段顺序固定为 `工具名 ␂ 状态 [␂ 目标] [␂ 记录]`：
     *  · target = 本次调用的**真实目标**（来自工具参数，如文件名/路径/关键字），供状态行显示
     *    「搜索文件：MainActivity.kt」这种可追溯的汉化文案——用户明确要求，工具名不必改，
     *    但显示时必须带上"它到底在动哪个/搜哪个"，能追溯的就必须追溯。
     *  · record = 附加明细（目前仅 ask_user 的「问/答」记录）。
     * 两者都为空时不写分隔符，保持与旧数据同构（向后兼容：旧块无 target 段）。
     */
    fun toolInlineBlock(
        tool: String, state: Int, record: String = "", target: String = "", argsSummary: String = ""
    ): String = ToolInlineProtocol.encode(tool, state, target, record, argsSummary)

    /** 在正文流末尾追加一条工具状态块(调用开始:0) */
    fun appendToolInline(
        text: String, tool: String, state: Int, target: String = "", argsSummary: String = ""
    ): String = ToolInlineProtocol.append(text, tool, state, target, argsSummary)

    /** 把正文流中「最后一次出现的同名工具状态块」替换为新状态(完成:1/2);找不到则追加。
     *  ask_user 完成时经 record 参数把「问/答」记录直接嵌进状态块,点击状态行即可展开查看。
     *  target / argsSummary 传空时自动沿用该块原有值（编解码统一走 [ToolInlineProtocol]，不再按下标猜段位）。 */
    fun replaceToolInline(
        text: String, tool: String, newState: Int, record: String = "",
        target: String = "", argsSummary: String = ""
    ): String = ToolInlineProtocol.replaceLast(text, tool, newState, record, target, argsSummary)

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
            if (close < 0) {
                // 闭合标签丢失：尾部这段是「工具状态行」而不是正文，直接丢弃。
                // 以前会把「工具名 + 分隔符 + 状态」拼进正文，界面上就是控制字符乱码 + 英文工具名。
                break
            }
            rest = after.substring(close + TOOL_INLINE_CLOSE.length)
        }
        return sb.toString().trim()
    }

    /** 交错块:正文 / 思考 / 工具状态 / 回答记录(按发生顺序排列,用于时间轴渲染) */
    sealed class InlineBlock {
        data class Text(val content: String) : InlineBlock()
        data class Thinking(val content: String) : InlineBlock()
        /** 工具状态块;ask_user 完成时 record 携带「问/答」记录(点击状态行展开);
         *  target = 本次调用的真实目标(文件名/路径/关键字),状态行据此显示「搜索文件：xxx」这种可追溯的汉化文案;
         *  argsSummary = 本次调用的关键参数摘要,点击状态行展开查看（状态行本身只显示 target）。 */
        data class Tool(
            val tool: String, val state: Int, val record: String = "",
            val target: String = "", val argsSummary: String = ""
        ) : InlineBlock()
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
                // ⚠️ 闭合标签丢失（流式中断 / 落盘被截断）时，绝不能再把「OPEN 哨兵 + 工具名 + 状态」原样当正文吐出去：
                //    那样界面上会出现一串控制字符乱码，工具名还会露出英文。
                //    工具块按「进行中」收尾成正常状态行；其余情况只保留纯文本部分。
                if (isTool) {
                    // 闭合标签丢失也要保留已经写进去的目标/参数摘要（否则用户只看到一条没头没尾的「正在读取文件」）
                    ToolInlineProtocol.parseTruncated(body)?.let {
                        out.add(InlineBlock.Tool(it.tool, it.state, it.record, it.target, it.argsSummary))
                    }
                } else {
                    val t = body.trim()
                    if (t.isNotBlank()) out.add(InlineBlock.Text(t))
                }
                break
            }
            val content = body.substring(0, close).trim()
            when {
                isTool -> {
                    // 5 段新格式与 2/3/4 段旧格式统一由协议解析器处理，杜绝「生成/替换/解析」三套逻辑各说各话
                    val b = ToolInlineProtocol.parseBody(content)
                    out.add(InlineBlock.Tool(b.tool, b.state, b.record, b.target, b.argsSummary))
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


    /** 「显示思考」开关：与齿轮设置页同一份偏好。关闭时思考阶段不写入思考内容、不提前上屏。 */
    fun getShowReasoning(ctx: Context, userId: Long): Boolean = AIUiPrefs.showReasoning(ctx)

    // ===== AI 访问控制模式:root=已 root 管理员模式(跨应用访问) / full=完全访问 / manual=手动审批 =====
    const val ACCESS_ROOT = "root"
    const val ACCESS_FULL = "full"
    const val ACCESS_MANUAL = "manual"

    fun getAccessMode(ctx: Context, userId: Long): String =
        prefs(ctx, userId).getString("agent_access_mode", ACCESS_MANUAL) ?: ACCESS_MANUAL

    fun setAccessMode(ctx: Context, userId: Long, mode: String) {
        prefs(ctx, userId).edit().putString("agent_access_mode", mode).apply()
    }

    // ===== AI 语义模式:ask=仅对话(无工具权限) / craft=完整工具能力(默认) / plan=先创建规划方案再执行 =====
    const val MODE_ASK = "ask"
    const val MODE_CRAFT = "craft"
    const val MODE_PLAN = "plan"

    fun getAIMode(ctx: Context, userId: Long): String =
        prefs(ctx, userId).getString("agent_mode", MODE_CRAFT) ?: MODE_CRAFT

    fun setAIMode(ctx: Context, userId: Long, mode: String) {
        prefs(ctx, userId).edit().putString("agent_mode", mode).apply()
    }

    // ===== 技能(执行模式):off=标准(默认) / multi_agent=多 AI 分工+验收 / reverse=逆向大师 =====
    // 存储为逗号分隔集合(如 "multi_agent,reverse");空/仅 off = 标准模式。
    // 前端展示名已统一为「技能」(按钮 contentDescription / 底部菜单标题)。
    // 「极速模式」「记忆增强」已改为独立开关(fast_mode / memory_enabled);「长任务」已移除;
    // 「深度研究」已改为按需调用的 deep_research 工具(不再做成模式)。
    // EXEC_FAST / EXEC_LONG / EXEC_RESEARCH / EXEC_MEMORY 四个常量仅保留用于兼容过滤旧存储数据,新代码不要再引用。
    const val EXEC_OFF = "off"
    const val EXEC_FAST = "fast"           // 已废弃:极速已迁至独立开关 fast_mode
    const val EXEC_LONG = "long"           // 已废弃:长任务模式已移除
    const val EXEC_RESEARCH = "research"   // 已废弃:深度研究已改为 deep_research 工具
    const val EXEC_MULTI_AGENT = "multi_agent"
    const val EXEC_REVERSE = "reverse"     // 逆向:内嵌专业逆向大师提示词 + 增强的逆向能力(不限轮数/大输出预算)
    const val EXEC_MEMORY = "memory"       // 已废弃:记忆增强已迁至独立开关 memory_enabled

    /** 逆向模式注入的专业系统提示词:以「逆向工程分析专家」身份授权,强调取证级证据与增强能力 */
    // 工具调用系统提示已清理：保留常量名以维持 AI 骨架，内容已清空。
    private const val REVERSE_MASTER_PROMPT = ""

    /** 执行模式冲突表(现仅剩 research / multi_agent,两者可共存无冲突;保留空表供 UI 统一处理) */
    val EXEC_CONFLICTS: Map<String, Set<String>> = emptyMap()

    // ===== 极速模式独立开关(默认关闭):开启后不注入任何系统提示词与工具,模型以纯对话身份工作,最快最省 =====
    fun isFastMode(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("fast_mode", false)

    fun setFastMode(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("fast_mode", on).apply()
    }

    // ===== 记忆增强独立开关(默认开启):不再属于执行模式,由「AI 使用」设置页控制 =====
    fun isMemoryEnabled(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("memory_enabled", true)

    fun setMemoryEnabled(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("memory_enabled", on).apply()
    }

    // ===== 工具调用轮数上限(默认 60):由「AI 使用」设置页让用户自己选 =====
    // 为什么要可配：轮数太小时，模型"还没了解完项目就被打断"（用户实际反馈过）；
    // 太大或"不限"则适合大改造/长任务，但失控时消耗更多 —— 这个取舍只有用户自己知道。
    // 返回 -1/0 之外的数值即上限；<=0 表示不设上限。
    fun getToolRoundLimit(ctx: Context, userId: Long): Int =
        prefs(ctx, userId).getInt("tool_round_limit", MAX_AGENT_TOOL_ROUNDS)

    fun setToolRoundLimit(ctx: Context, userId: Long, limit: Int) {
        prefs(ctx, userId).edit().putInt("tool_round_limit", limit).apply()
    }

    /** 仍有效的执行模式白名单:multi_agent + reverse;其余(off/fast/long/research/memory/未知旧值)一律过滤 */
    private val ACTIVE_EXEC_MODES = setOf(EXEC_MULTI_AGENT, EXEC_REVERSE)

    /**
     * **Plan 模式下禁止直接执行**的工具：凡会改动工作区 / 设备状态 / 执行代码的，一律算。
     *
     * 为什么要有这条：用户切到 Plan 模式，就是在要「**先看方案、我点了「构建」才动手**」这个保证。
     * 只靠提示词会被模型自我说服跳过（"这个不用规划"）→ 于是它直接把活儿干完，方案卡片与「构建」
     * 按钮全部形同虚设。所以改成**执行层确定性拦截**（能靠机制就不靠提示词）。
     *
     * 刻意**放行**的（否则没法用）：
     *  · 只读探索类（read_file / list_files / find_files / search_in_files / stat_file / fetch_webpage / search_web …）
     *    —— 不了解项目就写不出像样的方案；
     *  · set_agent_mode —— 用户点「构建」后要靠它切到 craft 模式；
     *  · 面向用户的产出（get_file 发文件、generate_media 生成媒体）—— 不改动用户的东西，不设卡。
     */
    private val PLAN_MODE_BLOCKED_TOOLS = setOf(
        "write_file", "append_file", "edit_file", "apply_patch", "batch_replace",
        "delete_file", "delete_folder", "create_folder", "rename_file", "copy_file",
        "restore_file", "extract_archive", "move_dir_contents",
        TOOL_COMPRESS_ARCHIVE, TOOL_UPLOAD_FILE, TOOL_KILL_PROCESS, TOOL_STOP_APP,
        "shell_exec", TOOL_EXECUTE_PYTHON, TOOL_RUN_SCRIPT, TOOL_RUN_ADB,
        "shutdown_device", "reboot_device", "update_plan_step"
    )

    fun getExecModes(ctx: Context, userId: Long): Set<String> {
        val raw = prefs(ctx, userId).getString("exec_mode", EXEC_OFF) ?: EXEC_OFF
        return raw.split(',').map { it.trim() }.filter { it in ACTIVE_EXEC_MODES }.toSet()
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
     * 单次请求允许的最大工具轮数(纯编排层保护,防止工具死循环)。
     * 由执行模式决定;不再与「思考深度」绑定。
     *
     * 原则:**只有"任务型"场景才配得上不限轮数;而"了解项目/查工作区/切模式"也是任务的一部分**,
     * 所以普通模式给的是一个宽松上限(见 [MAX_AGENT_TOOL_ROUNDS]),不是 6 这种一碰就断的小额度。
     */
    fun maxToolRoundsFor(ctx: Context, userId: Long, planActive: Boolean = false): Int {
        // 极速模式在编排层硬切断工具执行(0 轮,轮数检查在工具执行之前);
        // 多 AI 模式不设轮数上限(多 AI 自带任务+验收+修复复验流水线)。
        if (isFastMode(ctx, userId)) return 0
        val modes = getExecModes(ctx, userId)
        // 方案进行中:步骤天然多步,采用大厂式「完成即停」终止(由交付/契约收尾),不再用固定轮数硬墙卡死任务。
        if (planActive) return Int.MAX_VALUE
        if (EXEC_MULTI_AGENT in modes) return Int.MAX_VALUE
        if (EXEC_REVERSE in modes) return Int.MAX_VALUE
        // 普通模式:用「AI 使用」里用户自己选的上限(默认 60;可选 30/60/150/300/不限)
        val userLimit = getToolRoundLimit(ctx, userId)
        return if (userLimit <= 0) Int.MAX_VALUE else userLimit
    }

    /** 输出 token 预算:任务型模式拉满,否则给通用默认值(不再随「思考深度」变化)。 */
    private fun outputTokenBudget(ctx: Context, userId: Long): Int {
        // 任务型模式(多AI / 逆向)输出通常很长,预算直接拉满,避免被截断导致「输出几段就消失」
        val modes = getExecModes(ctx, userId)
        val taskMode = EXEC_MULTI_AGENT in modes || EXEC_REVERSE in modes
        // 普通模式也从 8192 抬到 24576：**写文件(尤其单页网页 / 长脚本)是最高频的长输出场景**，
        // 一个完整 HTML 动辄 6k~15k token，推理模型的思考还额外占 completion 额度；
        // 旧值一碰写大文件就被 max_tokens 拦腰截断 → @tool:end 缺失、JSON 不完整 →
        // 工具解析失败、任务无声卡住（用户实测：单独派创建文件没事，AI 自己决定写大文件就卡住）。
        // 拉高不会让请求失败：不接受该参数的 API 会在 400 时自动剥离重试（见 applyOutputBudget 调用处）。
        return if (taskMode) 32768 else 24576
    }

    /**
     * 写入通用输出 token 预算(所有 OpenAI 兼容端都认的 max_tokens / max_completion_tokens 标准字段)。
     * 不再注入任何厂商私有推理参数(reasoning_effort 等)——原生推理模型走各自默认思考行为,
     * 非推理模型也不受多余字段影响,因此无需逐厂商适配。
     * @return 本次写入的字段名列表(供 400 时自动回退移除,确保任何 API 都不会因该参数失败)
     */
    private fun applyOutputBudget(body: JSONObject, ctx: Context, userId: Long, model: String): List<String> {
        val added = mutableListOf<String>()
        val budget = outputTokenBudget(ctx, userId)
        // o 系推理模型只认 max_completion_tokens,其余一律用通用的 max_tokens
        if (model.matches(Regex("^o[1-9].*"))) {
            body.put("max_completion_tokens", budget); added.add("max_completion_tokens")
        } else {
            body.put("max_tokens", budget); added.add("max_tokens")
        }
        return added
    }

    // ════════════════════════════════════════════════════════════════════
    //  思考参数注入:让"有思考能力但默认不思考"的模型真的思考
    //  ---------------------------------------------------------------
    //  不逐个适配厂商/模型。做法与输出预算完全同构——把参数投出去,被拒(400)就降一档再试;
    //  候选按覆盖广度排序,共 5 档,试到能吃下的那一档为止;命中的档位写入本地缓存,
    //  之后同一模型直接命中,零重复开销。新模型无需改代码,首次自动试出来。
    // ════════════════════════════════════════════════════════════════════

    // 思考深度（用户可调）：决定"思考多深"。与"用哪个字段"（variant）是两个独立维度。
    /** 关闭：完全不注入思考参数（模型按自身默认行为，等同该功能上线前的表现，最快最省）。 */
    const val THINKING_OFF = -1
    const val THINKING_LOW = 0
    /** 中等：默认档。 */
    const val THINKING_MEDIUM = 1
    const val THINKING_HIGH = 2

    /** 用户选择的思考深度（默认中等）。 */
    fun getThinkingLevel(ctx: Context, userId: Long): Int =
        prefs(ctx, userId).getInt("thinking_level", THINKING_MEDIUM)

    fun setThinkingLevel(ctx: Context, userId: Long, level: Int) {
        prefs(ctx, userId).edit().putInt("thinking_level", level).apply()
    }

    /** 深度档位 → reasoning_effort 取值（OpenAI / OpenRouter 系）。 */
    private fun effortWord(level: Int): String = when (level) {
        THINKING_LOW -> "low"
        THINKING_HIGH -> "high"
        else -> "medium"
    }

    /** 深度档位 → 思考 token 预算（Claude / Gemini 系的 budget 参数用）。 */
    private fun thinkingBudget(level: Int): Int = when (level) {
        THINKING_LOW -> 2048
        THINKING_HIGH -> 32768
        else -> 8192
    }

    /**
     * 按 [variant] 档位 + [level] 深度向请求体注入思考参数（档位 0..4）。
     * variant 越界返回空列表（表示该模型已探明"不吃任何思考参数"）；level=OFF 同样返回空（用户主动关闭）。
     * @return 本次写入的字段名列表(供 400 时移除并降级到下一档)
     */
    private fun applyThinkingParams(body: JSONObject, variant: Int, level: Int): List<String> {
        if (level == THINKING_OFF) return emptyList()
        val fields: List<Pair<String, Any>> = when (variant) {
            0 -> listOf("reasoning_effort" to effortWord(level))                        // OpenAI o 系 / gpt-5 / 多数中转
            1 -> listOf("reasoning" to JSONObject().put("effort", effortWord(level)))   // OpenRouter 统一字段
            2 -> listOf("enable_thinking" to true)                                      // Qwen3 / 通义兼容模式(只有开关,无深度参数)
            3 -> listOf("thinking" to JSONObject()                                      // Anthropic Claude
                .put("type", "enabled").put("budget_tokens", thinkingBudget(level)))
            4 -> listOf("thinkingConfig" to JSONObject()                                // Gemini
                .put("thinkingBudget", thinkingBudget(level)))
            else -> return emptyList()
        }
        fields.forEach { (k, v) -> body.put(k, v) }
        return fields.map { it.first }
    }

    /** 模型名关键词 → 首选档位(仅决定"从哪一档开始试")。不匹配走 0,后续降级链照样兜底。 */
    private fun preferredThinkingVariant(model: String): Int {
        val m = model.lowercase()
        return when {
            m.contains("claude") -> 3
            m.contains("gemini") -> 4
            m.contains("qwen3") || m.contains("qwq") -> 2
            else -> 0
        }
    }

    private fun thinkingCacheKey(model: String) = "think_variant_" + model.lowercase().take(80)

    /** 取该模型当前思考档位:优先用本地探测缓存,无缓存时按模型名给首选档位。 */
    private fun getThinkingVariant(ctx: Context, userId: Long, model: String): Int =
        prefs(ctx, userId).getInt(thinkingCacheKey(model), preferredThinkingVariant(model))

    /** 记住探测结果,下次直接命中,不再重复试错。 */
    private fun setThinkingVariant(ctx: Context, userId: Long, model: String, variant: Int) {
        prefs(ctx, userId).edit().putInt(thinkingCacheKey(model), variant).apply()
    }

    /** 生成工具拒绝结果:Ask 模式提示需要切换模式,否则提示用户拒绝(供各工具执行链路统一使用) */
    fun agentDeniedResult(ctx: Context, userId: Long, tool: String): String {
        val msg = if (getAIMode(ctx, userId) == MODE_ASK) {
            "当前为 Ask 模式(仅对话):无法访问、修改或创建文件,也不能调用任何工具。如需执行请切换到 Craft 或 Plan 模式。"
        } else {
            "用户拒绝了该操作(权限审批未通过),未执行。"
        }
        return """{"status":"denied","message":"$msg","tool":"$tool"}"""
    }

    /** 该工具是否为「删改」型:在非 root 模式下由自动执行降级为手动审批 */
    fun isDestructiveTool(tool: String): Boolean = tool in setOf(
        "write_file", "delete_file", "delete_folder", "rename_file", "copy_file", "append_file"
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

    // 系统提示已清理：节省 Token 约束 / 解除指令常量已移除（原本零引用）。

    // ===== 极致节省模式(关闭性格调试,强制恢复正常中性语气,且不被历史上下文干扰) =====
    fun getUltraSave(ctx: Context, userId: Long): Boolean =
        prefs(ctx, userId).getBoolean("ultra_save", false)

    fun setUltraSave(ctx: Context, userId: Long, on: Boolean) {
        prefs(ctx, userId).edit().putBoolean("ultra_save", on).apply()
    }

    // 系统提示已清理：极致节省模式约束常量已移除（原本零引用）。

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

    /**
     * 「继续处理任务」续写约束（系统提示词·绝不随用户消息下发）。
     *
     * 背景：用户点「已手动停止」旁的「继续处理任务」时，界面上只以用户身份发送一句
     * 「继续完成未完成的任务」——那才是对话历史里该被看到的东西；真正的执行约束（断点续做、
     * 不重复已产出内容、不复述、不寒暄、按原顺序做完剩余步骤）全部在这里以 system 身份注入。
     * 二者分离，避免把一大段指令当作用户发言显示在气泡里。
     */
    // 工具调用系统提示已清理：保留常量名以维持 AI 骨架，内容已清空。
    const val RESUME_TASK_PROMPT = ""
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

    /** 保存用户选定的「免费 API」(来自开发者审核通过的列表),并切换为 free_api 模式。
     *  安全（密钥不下发）：只存 id/url/model，贡献者的 Key 只在服务端持有，
     *  对话/生图/测速全部走后端中转端点。 */
    fun saveFreeApiConfig(ctx: Context, userId: Long, id: Long, url: String, model: String) {
        prefs(ctx, userId).edit()
            .putLong("free_api_id", id)
            .putString("free_url", url.trim().trimEnd('/'))
            .putString("free_model", model.trim())
            .putString("mode", "free_api")
            .apply()
    }

    fun getFreeApiId(ctx: Context, userId: Long): Long = prefs(ctx, userId).getLong("free_api_id", 0L)

    /** 免费 API 配置 Triple(固定空 key, baseUrl, model)——key 不再由客户端持有 */
    fun getFreeApiConfig(ctx: Context, userId: Long): Triple<String, String, String> {
        val p = prefs(ctx, userId)
        return Triple(
            "",
            p.getString("free_url", "") ?: "",
            p.getString("free_model", "") ?: ""
        )
    }

    fun hasFreeApiConfig(ctx: Context, userId: Long): Boolean {
        val (_, u) = getFreeApiConfig(ctx, userId)
        return getFreeApiId(ctx, userId) > 0L || u.isNotBlank()
    }

    // ===== 新用户默认免费 API 自动选择 =====
    // 背景：从未在「AI 使用」里做过任何选择的用户，模式读出来是内部默认值 third_party
    // （平台免费 AI，界面上没有对应单选），于是面板看起来「什么都没选」。这里在登录后替这类用户
    // 自动从「已通过审核的免费 API」里挑一个**真正活着**的（优先延迟最低的）并落库为 free_api 模式，
    // 界面上即显示为已选中「选择免费 API」，用户拿到的是能直接用的东西。
    // 原则：只挑活的，绝不把已挂/已下架的条目塞给用户；若一个可用的都没有，回落平台免费 AI
    // （third_party 始终可用），保证用户永远有得用。
    // 只对「没有自己选择过的用户」生效：手动选过付费/个人，或手动挑的免费 API 仍可用时，一律不干涉。
    @Suppress("unused")
    suspend fun ensureDefaultFreeApi(ctx: Context, userId: Long): Boolean {
        return try {
            // 用户已有明确归属的选择 → 不干涉
            if (getPaidApiId(ctx, userId) > 0L || getPaidApiConfig(ctx, userId).second.isNotBlank()) return false
            if (getPersonalConfig(ctx, userId).first.isNotBlank()) return false
            val mode0 = getMode(ctx, userId)
            if (mode0 != "free_api" && mode0 != "third_party") return false

            // 拉取已上架列表（失败按「无可选项」处理，下面统一回落平台免费 AI）
            val arr = try {
                val res = AuroraApi.listAvailableFreeApis()
                if (res.success) res.data?.optJSONArray("items") else null
            } catch (_: Exception) { null }

            // 解析出「未被标记已挂」的候选
            data class FreeCand(val id: Long, val url: String, val model: String)
            val live = ArrayList<FreeCand>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optInt("marked_down") == 1) continue
                    val id = o.optLong("id")
                    if (id <= 0L) continue
                    live.add(FreeCand(id, o.optString("base_url"), o.optString("model_name")))
                }
            }

            // 当前选用的免费 API 仍在架且未挂 → 沿用，不换来换去
            val curId = getFreeApiId(ctx, userId)
            val curUrl = getFreeApiConfig(ctx, userId).second
            val curUsable = live.any { (curId > 0L && it.id == curId) || (curId <= 0L && curUrl.isNotBlank() && it.url == curUrl) }
            if (mode0 == "free_api" && curUsable) return false

            val pool = live.take(8)
            if (pool.isEmpty()) {
                // 一个可用条目都没有 → 落回平台免费 AI（始终可用），不让用户卡在无效模式上
                setMode(ctx, userId, "third_party")
                return false
            }

            // 逐个测速（服务端代发，贡献者的 Key 不下发），取最快的一个活着
            val lat = coroutineScope {
                pool.map { c ->
                    async {
                        val (ok, ms) = AuroraApi.testFreeApi(c.id)
                        try {
                            AuroraApi.pingFreeApi(c.id, ok, if (ok) ms else 0L)
                        } catch (_: Exception) { }
                        if (ok && ms >= 0) ms else -1L
                    }
                }.awaitAll()
            }
            var bestIdx = -1
            var bestMs = Long.MAX_VALUE
            for (i in pool.indices) {
                val ms = lat[i]
                if (ms >= 0 && ms < bestMs) { bestMs = ms; bestIdx = i }
            }
            if (bestIdx < 0) {
                // 全挂了：同样回落平台免费 AI，绝不把死条目给用户
                setMode(ctx, userId, "third_party")
                return false
            }
            val best = pool[bestIdx]
            // 落库（同时写入 mode=free_api）：界面上「选择免费 API」即显示为选中态
            saveFreeApiConfig(ctx, userId, best.id, best.url, best.model)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 保存用户选定的「付费 API」(已上架),并切换为 paid_api 模式。
     *  安全（密钥不下发）：只存 id/url/model，上传者的 Key 只在服务端持有，
     *  对话走 /api/paid-api/chat、生图走 /api/paid-api/image 中转。 */
    fun savePaidApiConfig(ctx: Context, userId: Long, id: Long, url: String, model: String) {
        prefs(ctx, userId).edit()
            .putLong("paid_api_id", id)
            .putString("paid_url", url.trim().trimEnd('/'))
            .putString("paid_model", model.trim())
            .putString("mode", "paid_api")
            .apply()
    }

    fun getPaidApiId(ctx: Context, userId: Long): Long = prefs(ctx, userId).getLong("paid_api_id", 0L)

    /** 付费 API 配置 Triple(固定空 key, baseUrl, model)——key 不再由客户端持有 */
    fun getPaidApiConfig(ctx: Context, userId: Long): Triple<String, String, String> {
        val p = prefs(ctx, userId)
        return Triple(
            "",
            p.getString("paid_url", "") ?: "",
            p.getString("paid_model", "") ?: ""
        )
    }

    /** 读取缓存的上传者倍率（input/output price），未缓存返回 (0.0, 0.0) */
    fun getPaidApiPrice(ctx: Context, userId: Long): Pair<Double, Double> {
        val p = prefs(ctx, userId)
        return Pair(
            p.getFloat("paid_in_price", 0f).toDouble(),
            p.getFloat("paid_out_price", 0f).toDouble()
        )
    }

    /** 缓存当前选用付费 API 的上传者倍率（选择页选用或发送前兜底拉取时写入） */
    fun savePaidApiPrice(ctx: Context, userId: Long, inPrice: Double, outPrice: Double) {
        prefs(ctx, userId).edit()
            .putFloat("paid_in_price", inPrice.toFloat())
            .putFloat("paid_out_price", outPrice.toFloat())
            .apply()
    }

    /** 发送前兜底：从付费 API 列表拉取当前选用项的倍率并缓存；列表拉取失败或未匹配时保持原值（流式实时掐断自动降级为完成后扣费） */
    suspend fun refreshPaidApiPrice(ctx: Context, userId: Long) {
        try {
            val id = getPaidApiId(ctx, userId)
            val (_, url, model) = getPaidApiConfig(ctx, userId)
            if (id <= 0L && url.isBlank()) return
            val (inP, outP) = getPaidApiPrice(ctx, userId)
            if (inP > 0.0 && outP > 0.0) return
            val res = AuroraApi.listAvailablePaidApis()
            if (!res.success) return
            val arr = res.data?.optJSONArray("items") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optLong("id") == id || (id <= 0L && o.optString("base_url") == url && o.optString("model_name") == model)) {
                    savePaidApiPrice(ctx, userId, o.optDouble("input_price", 0.0), o.optDouble("output_price", 0.0))
                    return
                }
            }
        } catch (_: Exception) { }
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
            // 免费 API(用户贡献、开发者审核通过):统一走后端中转 /api/free-api/chat ——
            // 贡献者的 Key 只在服务端持有(available 接口不再下发 api_key),
            // 由后端解析条目并直连上游;model 为空时由后端保留客户端传值。
            "free_api" -> {
                val (_, u, m) = getFreeApiConfig(ctx, userId)
                Triple(
                    AuroraApi.authToken.orEmpty(),
                    "${AuroraApi.serverUrl.trimEnd('/')}/api/free-api/chat",
                    if (m.isEmpty()) OFFICIAL_MODEL else m
                )
            }
            // 付费 API(已上架):统一走后端中转 /api/paid-api/chat —— 上传者的 Key 只在服务端持有,
            // 由后端解析上游真实 usage 并按 paid_apis 表里的权威倍率扣费。
            // (原来的「客户端直连上游 + 自行上报 token」口径存在少报/被篡改/崩溃丢报,会让平台按轮数成比例亏损)
            "paid_api" -> Triple(
                AuroraApi.authToken.orEmpty(),
                "${AuroraApi.serverUrl.trimEnd('/')}/api/paid-api/chat",
                getPaidApiConfig(ctx, userId).third
            )
            // 官方 API 模式已移除(内置密钥随 APK 分发等于公开泄露)。
            // 旧值/异常值统一收敛到第三方中转,与 getMode 的映射保持一致。
            else -> Triple(
                AuroraApi.authToken.orEmpty(),
                "${AuroraApi.serverUrl.trimEnd('/')}/api/ai",
                ""
            )
        }
    }

    // ===== 额度(仅官方模式,按用户按天统计) =====
    private fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    /**
     * 返回 Pair(今日已用 tokens, 剩余 tokens)。余额只来自服务器,绝不使用本地推导值。
     * 剩余 = 服务器真实余额(由 syncServerLimit 拉取并缓存)，所有用户统一无豁免。
     * 若服务器余额尚未拉取(缓存为 -1),剩余返回 -1,UI 显示「加载中...」,由服务器裁决是否可继续。
     */
    fun getOfficialUsage(ctx: Context, userId: Long): Pair<Long, Long> {
        val p = prefs(ctx, userId)
        val today = todayStr()
        // 跨天仅重置「今日已用」本地计数(用于展示消耗);余额本身始终取自服务器,不随天数重置
        if ((p.getString("quota_date", "") ?: "") != today) {
            p.edit().putString("quota_date", today).putLong("quota_used", 0L).apply()
        }
        val used = p.getLong("quota_used", 0L)
        // 余额只取服务器真实数据;未拉取时为 -1(未知),交由服务器裁决
        val remaining = p.getLong(KEY_SERVER_TOKEN_BALANCE, -1L)
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

    fun addOfficialUsage(ctx: Context, userId: Long, tokens: Long) {
        val t = tokens.coerceAtLeast(0)
        val p = prefs(ctx, userId)
        // 仅累加本地「今日已用」计数(用于展示消耗统计);余额本身只来自服务器,绝不在此本地扣减。
        // 每次请求结束后由 syncServerLimit 重新从服务器拉取权威余额刷新缓存。
        val used = p.getLong("quota_used", 0L)
        p.edit().putLong("quota_used", used + t).apply()
        // 同步记录到「按天历史」(供「近 30 天用量」界面使用)
        recordDailyUsage(ctx, userId, t)
    }

    private const val KEY_USAGE_HISTORY = "ai_usage_history" // JSON 数组: [{"d":"yyyy-MM-dd","t":tokens}, ...]

    /** 把本次消耗累加到「今天」的历史条目;同时静默丢弃超过 30 天的旧记录(无任何提示)。 */
    private fun recordDailyUsage(ctx: Context, userId: Long, tokens: Long) {
        val p = prefs(ctx, userId)
        val today = todayStr()
        val raw = p.getString(KEY_USAGE_HISTORY, "") ?: ""
        val arr = runCatching { if (raw.isBlank()) JSONArray() else JSONArray(raw) }.getOrDefault(JSONArray())
        // 30 天窗口:保留 date >= 今天-29 天;字符串 yyyy-MM-dd 可直接字典序比较
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -29) }
        val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(cal.time)
        val kept = JSONArray()
        var todayFound = false
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            val d = o.optString("d", "")
            val tv = o.optLong("t", 0L)
            if (d < cutoff) continue            // 超期:丢弃(静默清理)
            if (d == today) {
                kept.put(JSONObject().put("d", d).put("t", tv + tokens))
                todayFound = true
            } else kept.put(o)
        }
        if (!todayFound) kept.put(JSONObject().put("d", today).put("t", tokens))
        p.edit().putString(KEY_USAGE_HISTORY, kept.toString()).apply()
    }

    /**
     * 读取「近 30 天」每天用量(升序:旧→新)。
     * 读取时再次静默清理超过 30 天的记录(保证旧数据不残留,无任何提示/弹窗)。
     */
    fun getUsageHistory(ctx: Context, userId: Long): List<Pair<String, Long>> {
        val p = prefs(ctx, userId)
        val raw = p.getString(KEY_USAGE_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -29) }
        val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(cal.time)
        val out = mutableListOf<Pair<String, Long>>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = o.optString("d", "")
                if (d >= cutoff) out.add(d to o.optLong("t", 0L))
            }
        }
        out.sortBy { it.first }
        // 静默写回(丢弃超期项,并稳定排序)
        val kept = JSONArray()
        out.forEach { (d, t) -> kept.put(JSONObject().put("d", d).put("t", t)) }
        p.edit().putString(KEY_USAGE_HISTORY, kept.toString()).apply()
        return out
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

    /**
     * 从「只输出思考、正文为空」的 reasoning 里捞回可交付的正文。
     *
     * 为什么需要：DeepSeek 等推理模型在写文件这类任务上，经常把要写的内容在思考里
     * 演练一遍、content 却一字未留（用户实测约 90% 的任务因此空手中断）。
     * 捞思考的**最后一个段落**（结论通常落在末尾）并做长度封顶，
     * 既不空手结束，也不会把整段思考糊进气泡。
     */
    fun salvageAnswerFromReasoning(reasoning: String): String {
        if (reasoning.isBlank()) return ""
        val blocks = Regex("\n\\s*\n").split(reasoning).map { it.trim() }.filter { it.isNotBlank() }
        val tail = blocks.lastOrNull() ?: reasoning.trim()
        return if (tail.length <= 1500) tail else "…" + tail.takeLast(1500)
    }

    /** 每张图片的近似 token 数。图片已压到长边 ≤1600 的 JPEG，视觉计费按 512px 分块，取常用近似值。
     *  只用于「上游不回报 usage」时的兜底估算——不追求精确，只求**量级正确**。 */
    const val VISION_TOKEN_ESTIMATE = 1024L

    /**
     * 估算一次请求的**输入** token 数。口径必须与 [streamChat] 实际发出的请求一致：
     * system 提示 + 工具 schema + 历史消息文本 + 图片。
     *
     * 历史教训（用户实测"估算值和真实消耗毫无关系"）：旧口径只累加聊天历史文本，
     * 漏掉了占比 90% 以上的 **system 提示**（约 10k+ token）与 **tools schema**（数千 token），
     * 于是中断时「输入」比真实值低一个数量级。这三项必须一起算。
     */
    fun estimateRequestInputTokens(
        systemText: String,
        historyTexts: List<String>,
        toolSchemaJson: String,
        imageCount: Int = 0
    ): Long {
        val system = estimateTokens(systemText).toLong()
        val tools = if (toolSchemaJson.isBlank()) 0L else estimateTokens(toolSchemaJson).toLong()
        val history = historyTexts.sumOf { estimateTokens(it).toLong() }
        val images = imageCount.toLong() * VISION_TOKEN_ESTIMATE
        return system + tools + history + images
    }

    /**
     * 与 [streamChat] 请求体**逐字节同源**的工具 schema 文本（仅用于输入估算）。
     *
     * 抽出来是为了避免"两处口径漂移"：估算用的工具清单必须和真正发出去的清单一致，
     * 否则极速/多 AI/Ask 模式下又会对不上。
     */
    fun toolSchemaTextForEstimate(
        ctx: Context, userId: Long, enableAITools: Boolean, fastMode: Boolean,
        lastUserText: String? = null, history: List<Pair<String, String>>? = null,
        role: AIRoles.AIRole = AIRoles.AIRole.ROOT
    ): String {
        if (enableAITools && fastMode) return ""            // 极速：不向模型声明任何工具
        if (enableAITools) {
            val enhanced = execActive(ctx, userId, EXEC_MULTI_AGENT)
            // 多 AI 全量不裁剪；普通模式按「当前轮用户意图」分档（与 streamChat 实际发出同源）
            val tier = if (enhanced) ToolIntentTier.CRAFT
            else toolIntentTier(ctx, userId, lastUserText, history ?: emptyList())
            // 与 streamChat 一样按角色裁剪:子 AI 实际发出去的清单就是裁过的,
            // 估算若仍按全量算,会把子循环的输入 token 估高一截(提前触发上下文裁剪)。
            return AIRoles.toolSchemaFor(
                role,
                buildAIToolsSchema(includeMultiAI = enhanced, minimal = !enhanced, intentTier = tier)
            ).toString()
        }
        return buildAIModeOnlySchema().toString()           // Ask：只注册 set_agent_mode
    }

    /**
     * 基于「实际发出的 history」估算一次请求的**输入** token 数。
     *
     * 与 [estimateRequestInputTokens] 同口径，但把"工具 schema 文本"的拼装也一并封装，
     * 避免调用方在两处重复拼 [toolSchemaTextForEstimate] 而与真实发出的清单漂移
     * （见 [toolSchemaTextForEstimate] 的"两处口径漂移"警告）。
     *
     * 关键用途：手动中断 / 实时显示时，history 已经累积了任务过程中的工具结果，
     * 必须用它（而非发送时按初始上下文算的快照）来重算输入，否则输入会比真实值低一个数量级
     * （旧 bug：中断回退估算用了发送时的初始上下文快照）。
     */
    fun estimateHistoryInputTokens(
        ctx: Context, userId: Long,
        systemText: String,
        historyTexts: List<String>,
        enableAITools: Boolean,
        fastMode: Boolean,
        imageCount: Int = 0,
        lastUserText: String? = null,
        history: List<Pair<String, String>>? = null
    ): Long {
        val toolSchemaJson = toolSchemaTextForEstimate(ctx, userId, enableAITools, fastMode, lastUserText, history)
        return estimateRequestInputTokens(
            systemText = systemText,
            historyTexts = historyTexts,
            toolSchemaJson = toolSchemaJson,
            imageCount = imageCount
        )
    }

    /**
     * 服务端下发的**权威结算结果**（付费 API 中转的 SSE 事件 `aurora_billing`）。
     * 见 backend/paid_api.go 防线③：客户端断开时后端仍会结算并落库，只是事件发不出去。
     */
    data class AiBilling(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val costTokens: Long = 0,
        val cutOff: Boolean = false,
        val realUsage: Boolean = false
    )

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
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun buildAdvancedSystemPrompt(ctx: Context, userId: Long): String = ""

    /** 多次输出约束:仅在开启时返回,要求 AI 自行判断是否分多条短消息,并用分隔符标记 */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun multiOutputConstraint(ctx: Context, userId: Long): String = ""


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
        /** 用量明细回调(输入/输出 token、缓存命中、上下文长度);上游未提供 usage 明细时为 null */
        onUsageDetail: ((AiUsageDetail?) -> Unit)? = null,
        onTokenInsufficient: () -> Unit = {},
        /** 当返回内容为空时是否视为错误(对话页=true 暴露问题;沙盒需 false 以支持 [PASS]) */
        treatEmptyAsError: Boolean = true,
        /** 随本次用户消息一起发送给 AI 的图片(DeepSeek/OpenAI 多模态格式,直接发往用户配置的 API)。空表示纯文本。 */
        images: List<AiImage> = emptyList(),
        /** 随图片一起发送的文字;为空且带图时补默认说明。仅 images 非空时生效。 */
        imageText: String = "",
        /** 返回 true 表示用户手动停止:流式循环据此提前退出,不再回调 onDone(界面据此保留部分输出并显示「继续」)。 */
        stopCheck: (() -> Boolean)? = null,
        /** AI 原生 function calling:调用方传入一个空列表,结束时若检测到 tool_calls 会填入(用于 role:tool 回喂)。为空/null 即纯文本协议。 */
        nativeToolAccumulator: MutableList<AINativeToolCall>? = null,
        /** 是否在请求体带 tools/tool_choice(原生 function calling)。仅 AI 工具循环开启;普通发送保持纯文本不恢复开销、不向非 AI 场景暴露工具。 */
        enableAITools: Boolean = false,
        /** 流结束前回调上游 finish_reason(stop/length/tool_calls),供编排层决定是否续写/收尾。默认不触发。 */
        onFinish: ((finishReason: String) -> Unit)? = null,
        /** 工具调用开关覆盖:null=沿用 enableAITools;\"none\"=收尾轮禁用工具(移除 tools 并置 tool_choice=none,强制纯文本交付)。 */
        toolChoice: String? = null,
        /** 服务端权威结算回调(付费 API 中转的 aurora_billing 事件):上游真实 usage 或服务端估算值 + 真实扣费。
         *  手动停止时该事件随连接断开丢失,调用方需退回「查询最近一次结算」接口兜底。 */
        onBilling: ((AiBilling) -> Unit)? = null
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

                // 额度校验(仅官方模式):余额扣到 0 即拒绝,避免继续烧真实 API 余额，所有人统一无豁免
                // 第三方免费 AI(SolitaryCryAI)不走官方额度,跳过此处
                val curMode = getMode(ctx, userId)
                if (curMode != "personal" && curMode != "third_party" && curMode != "free_api" && curMode != "paid_api") {
                    val (_, remaining) = getOfficialUsage(ctx, userId)
                    if (remaining <= 0L) {
                        // 余额用尽是终态中断：把后台保活小窗切成故障态（点击回前台），避免留僵尸小窗
                        try { com.aurora.chat.AiKeepAliveService.notifyFault(ctx) } catch (_: Exception) {}
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
                        // 工具调用系统提示已清理：过滤掉内容为空的占位消息，避免上游 400。
                        if (content.isBlank() && role != "tool") continue
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
                    // 付费/免费 API 走后端中转:带上平台侧标识(后端据此查表定位条目、
                    // 以服务端持有的上传者 Key 直连上游),base_url/model_name 仅用于
                    // 客户端本地 id 失效时后端兜底匹配,不再携带任何上游密钥。
                    when (getMode(ctx, userId)) {
                        "paid_api" -> {
                            put("api_id", getPaidApiId(ctx, userId))
                            val (_, paidUpstreamUrl, paidUpstreamModel) = getPaidApiConfig(ctx, userId)
                            if (paidUpstreamUrl.isNotBlank()) put("base_url", paidUpstreamUrl)
                            if (paidUpstreamModel.isNotBlank()) put("model_name", paidUpstreamModel)
                        }
                        "free_api" -> {
                            put("api_id", getFreeApiId(ctx, userId))
                            val (_, freeUpstreamUrl, freeUpstreamModel) = getFreeApiConfig(ctx, userId)
                            if (freeUpstreamUrl.isNotBlank()) put("base_url", freeUpstreamUrl)
                            if (freeUpstreamModel.isNotBlank()) put("model_name", freeUpstreamModel)
                        }
                    }
                    // 工具调用系统提示注入已清理：不再向模型下发 tools / tool_choice。
                    // 工具执行器、schema 与循环骨架保留但闲置，供后续「AI 对话」移植复用。
                }

                // 输出预算:在请求底层写入通用 max_tokens
                val outputBudgetKeys = applyOutputBudget(body, ctx, userId, model)
                // 思考参数:让"有思考能力但默认不思考"的模型真的思考(极速模式跳过——省时省钱是它的语义)。
                // 与输出预算同层:投出去,被拒就降一档重试;命中的档位缓存在本地,后续直接命中。
                var thinkingVariant = getThinkingVariant(ctx, userId, model)
                val thinkingLevel = getThinkingLevel(ctx, userId)
                // model 为空 = 第三方免费 AI(模型由后端指定),不注入——避免替后端做它没要求的假设
                var thinkingKeys = if (isFastMode(ctx, userId) || model.isBlank()) emptyList()
                                  else applyThinkingParams(body, thinkingVariant, thinkingLevel)

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
                // - 400 且带思考参数 → 降一档换用下一候选重试(最多 5 档,穷尽后不再注入;被拒的档位记入缓存,不再重复试)
                // - 400 且带输出预算参数 → 剥离后重试(参数移除后自然只触发一次)
                // - 429 限流 → 【无限次】等待重试,绝不因「重试 3 次」而中断连接:
                //   单次等待最多 30s(优先遵循服务端 Retry-After,同样上限 30s),等多久都等,
                //   直到放行或用户手动停止。多 AI 模式连续大量请求被限流时,任务链不再被打断。
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
                    // ① 思考参数被拒(该模型不吃这一档) → 降一档重试,并把档位记进本地缓存。
                    //    理由同下:不识别厂商,靠"被拒即降档"自我适配,新模型无需改代码。
                    if (code == 400 && thinkingKeys.isNotEmpty()) {
                        Log.w(TAG, "streamChat 400:思考参数 $thinkingKeys 被拒,降档重试($thinkingVariant → ${thinkingVariant + 1})")
                        thinkingKeys.forEach { body.remove(it) }
                        thinkingVariant++
                        setThinkingVariant(ctx, userId, model, thinkingVariant)
                        thinkingKeys = applyThinkingParams(body, thinkingVariant, thinkingLevel)
                        req = req.newBuilder()
                            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                            .build()
                        continue
                    }
                    // ② 兼容兜底:若该 API 不接受输出预算参数(400),一次性剥离后重试。
                    // 这样任何 OpenAI 兼容 API 都不会因 max_tokens 而失败,无需逐个厂商适配。
                    if (code == 400 && outputBudgetKeys.isNotEmpty()) {
                        Log.w(TAG, "streamChat 400:移除输出预算参数 $outputBudgetKeys 后重试")
                        outputBudgetKeys.forEach { body.remove(it) }
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
                                onError("API 持续限流(429)，已自动停止重试。请稍后再试，或降低请求频率(如减少多 AI 模式下的连续任务)。")
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
                // 无数据看门狗:流式 SSE 在「收到首个 data 行之后」超过 3 分钟无任何新 data 行(连接已建立且首包已到,但后续 API 卡流/只回 keep-alive),
                // 判定为停滞,主动 close 响应让阻塞中的 readLine 抛 IOException,catch 分支据此明确报错——
                // 修复「运行中无输出无思考」的黑洞态(原来只能干等读超时,期间界面毫无反馈)。
                // 注意:计时起点改为「首 data 行之后」(由 1816 处武装),豁免 DeepSeek 推理模型的首 token 长时延(TTFT 常 >60s),
                // 避免把「合法长思考」误判成「连接卡死」硬杀;TTFT 前的等待由 OkHttp 读超时兜底,不计入此计数。
                lastDataTs = 0L
                stopWatchdog = if (code in 200..299) Thread {
                    try {
                        while (true) {
                            if (stopCheck?.invoke() == true) {
                                manualStopped = true
                                runCatching { resp?.close() }
                                return@Thread
                            }
                            if (isStallTimeout(lastDataTs, System.currentTimeMillis())) {
                                stalled = true
                                runCatching { resp?.close() }
                                return@Thread
                            }
                            Thread.sleep(120)
                        }
                    } catch (_: Exception) {}
                }.apply { isDaemon = true; start() } else null
                if (code !in 200..299) {
                    // 诊断日志:仅记录模型与图片数量/体积,绝不打印 base64 图片内容(避免 PII 泄露与日志膨胀,见 8.2)
                    Log.e(TAG, "streamChat 请求体预览: model=${body.optString("model")} images=${images.size}(~${imagesBytes}B)")
                    val errBody = try {
                        resp.body?.string() ?: ""
                    } catch (_: Exception) { "" }
                    val msg = if (code == 402) {
                        "平台余额不足,请通知开发者充值"
                    } else if (code == 429) {
                        "API 限流(429):无限等待重试被中断(手动停止或连接异常)。${errBody.take(200)}。请稍后再试,或降低请求频率(如减少多 AI 模式下的连续任务)。"
                    } else {
                        "API 错误($code): ${errBody.take(300)}"
                    }
                    Log.e(TAG, "streamChat 失败 code=$code body=${errBody.take(500)}")
                    resp.close()
                    RateLimitBus.clear()
                    if (code == 402) {
                        // 402=平台余额不足，属终态中断：把后台保活小窗切成故障态（点击回前台）
                        try { com.aurora.chat.AiKeepAliveService.notifyFault(ctx) } catch (_: Exception) {}
                    }
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
                // 用量明细(输入/输出 token、缓存命中、上下文长度);上游未提供时各字段为 0
                var usageDetail: AiUsageDetail? = null
                // 服务端权威结算(付费 API 中转):含真实扣费 token;上游不返回 usage 时后端也会兜底结算
                var lastBilling: AiBilling? = null
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
                    // SSE 注释/keep-alive 行(以 ':' 开头)视为连接仍活跃,刷新看门狗时间戳,
                    // 使长推理/大工具规划期间上游仅发心跳(如后端 30s 一次的 ": ping")不被误判停滞(6.2/7.1)。
                    if (l.startsWith(":")) {
                        lastDataTs = System.currentTimeMillis()
                        continue
                    }
                    if (rawSb.length < 2000) rawSb.append(l).append("\n")
                    if (!l.startsWith("data:")) continue
                    val data = l.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    // 无数据看门狗喂食:只要有 data 行到达就刷新最后活跃时间
                    lastDataTs = System.currentTimeMillis()
                    try {
                        val obj = JSONObject(data)
                        // 付费 API 中转的权威结算事件(见 backend/paid_api.go 防线③):
                        // 在 [DONE] 之前下发,携带真实扣费 token 与「是否上游真实 usage」。
                        // 用户手动停止时连接已断、这条事件到不了调用方 —— 那种情况由调用方
                        // 事后查「最近一次结算」接口兜底(后端照样已结算落库)。
                        if (!obj.isNull("aurora_billing")) {
                            val b = obj.optJSONObject("aurora_billing")
                            if (b != null) {
                                lastBilling = AiBilling(
                                    inputTokens = b.optLong("input_tokens", 0),
                                    outputTokens = b.optLong("output_tokens", 0),
                                    costTokens = b.optLong("cost_tokens", 0),
                                    cutOff = b.optBoolean("cut_off", false),
                                    realUsage = b.optBoolean("real_usage", false)
                                )
                                onBilling?.invoke(lastBilling!!)
                            }
                        }
                        if (!obj.isNull("usage")) {
                            val u = obj.getJSONObject("usage")
                            usageTokens = u.optLong("prompt_tokens", 0) + u.optLong("completion_tokens", 0)
                            // 用量明细:输入=prompt_tokens(扣除缓存命中),输出=completion_tokens,
                            // 缓存命中=prompt_tokens_details.cached_tokens,上下文长度=prompt_tokens+completion_tokens(取非 0 者)
                            val inputP = u.optLong("prompt_tokens", 0)
                            val outputP = u.optLong("completion_tokens", 0)
                            var cacheHit = 0L
                            if (!u.isNull("prompt_tokens_details")) {
                                val pd = u.getJSONObject("prompt_tokens_details")
                                cacheHit = pd.optLong("cached_tokens", 0)
                            }
                            var ctxLen = 0L
                            if (!u.isNull("context_length")) ctxLen = u.optLong("context_length", 0)
                            if (ctxLen <= 0L) ctxLen = inputP + outputP
                            usageDetail = AiUsageDetail(
                                inputTokens = inputP,
                                outputTokens = outputP,
                                cacheHitTokens = cacheHit,
                                contextLength = ctxLen
                            )
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
                    // 【手动停止也要能查看消耗】正常结束才走 onDone → onUsageDetail，中断时上游 usage 会被整段丢弃，
                    // 消息上的 token 字段全为 0，底部「Token 明细」按钮随之不渲染（用户点停止后查不到本次消耗）。
                    // 这里把「停止前已收到」的用量明细照样回报一次（上游没发过则为 null，由调用方决定是否用估算兜底）。
                    // 注意：usageDetail 为空时也回报 null 是安全的——内联循环走 accumulateUsage(prev, null) 保留旧值，
                    // 主发送链路只是把本轮 ud 置空，不会覆盖更早轮次的真实用量。
                    onUsageDetail?.invoke(usageDetail)
                    return@withContext
                }
                // 把本次检测到的原生 tool_calls 整理进调用方提供的累积器(按 index 排序)
                if (nativeToolAccumulator != null) {
                    nativeToolAccumulator.clear()
                    if (nativeToolSeen) {
                        for (idx in nativeToolArgs.keys.sorted()) {
                            nativeToolAccumulator.add(AINativeToolCall(
                                id = nativeToolIds[idx] ?: "",
                                name = nativeToolNames[idx] ?: "",
                                argumentsJson = nativeToolArgs[idx].toString()
                            ))
                        }
                    }
                }
                val rawAnswer = sb.toString()
                // 原生 tool_calls 的 content 通常为空:此时不属于「空响应」错误
                val hasNativeTools = nativeToolSeen && (nativeToolAccumulator?.isNotEmpty() == true)
                // DeepSeek 等推理模型的高发故障:整轮只吐 reasoning_content、content 一字未留。
                // 以前这里直接判「空响应」报错中断 —— 用户实测 DeepSeek 上约 90% 的写文件任务
                // 就是这么断的(模型把要写的内容在思考里列了一遍,正文什么都没给就结束)。
                // 现在只要有思考内容,就把思考的**最后一段**捞回来当正文交付,绝不空手中断。
                val full = if (rawAnswer.isBlank() && !hasNativeTools && reasonSb.isNotBlank()) {
                    Log.w(TAG, "streamChat 正文为空、思考 ${reasonSb.length} 字 → 捞回思考末段作为正文(model=$model)")
                    salvageAnswerFromReasoning(reasonSb.toString())
                } else rawAnswer
                if (full.isBlank() && treatEmptyAsError && !hasNativeTools) {
                    // 请求成功但内容为空:多半是模型名/额度/网络问题。
                    // 【原始返回只进日志,绝不进气泡】旧实现把「原始返回前 400 字」连同 HTTP 码
                    // 一起塞进 AI 气泡,一段 JSON 残片混着排查术语,既看不懂也不该让用户看。
                    // 排查信息写进 ModelLogger(「查看日志」可复制给开发者),气泡里只留一句人话。
                    val rawPreview = rawSb.toString().take(400).replace("\n", " ")
                    Log.e(TAG, "streamChat 返回空内容:code=$code model=$model 已收思考内容=${reasonSb.length}字 raw=$rawPreview")
                    com.aurora.chat.ModelLogger.error(
                        ctx, "ERR",
                        "模型返回空内容 | code=$code model=$model 已收思考=${reasonSb.length}字 raw=$rawPreview"
                    )
                    withContext(Dispatchers.Main) {
                        onError("模型这次没有返回任何内容，已自动重试；若反复出现，请检查模型名与额度。")
                    }
                } else {
                    val tDone = System.currentTimeMillis()
                    val thinkingSeconds =
                        if (firstReasoningTs > 0L && (firstContentTs - firstReasoningTs) > 0L) (firstContentTs - firstReasoningTs) / 1000L else 0L
                    val totalSeconds = if ((tDone - tStart) > 0L) (tDone - tStart) / 1000L else 0L
                    // 先通知编排层上游结束原因(供续写/收尾判定),再回调 onDone。
                    withContext(Dispatchers.Main) { onFinish?.invoke(finishReason) }
                    withContext(Dispatchers.Main) { onDone(usageTokens, full, thinkingSeconds, totalSeconds) }
                    onUsageDetail?.invoke(usageDetail)
                    // 【模型日志·每轮一行】刻意放在 streamChat 里：**内联循环与编排循环都必经此处**，
                    // 不像挂在某条循环上那样会漏记。带上下文条数与 finish_reason，
                    // 便于定位长任务的 token 到底是被"历史累积"还是"工具结果/固定开销"撑起来的。
                    com.aurora.chat.ModelLogger.info(
                        ctx, "ROUND",
                        "模型返回 | finish=$finishReason 上下文条数=${history.size} 输入=${usageDetail?.inputTokens ?: 0} 输出=${usageDetail?.outputTokens ?: 0} 缓存命中=${usageDetail?.cacheHitTokens ?: 0} 上下文长度=${usageDetail?.contextLength ?: 0}"
                    )
                }
            } catch (e: Exception) {
                stopWatchdog?.interrupt()
                // 【模型日志】上游/网络异常如实记下（长任务"为什么自己停了"最常见的原因就在这里）。
                // 同时会进 ErrorReporter，所以「发送日志给开发者」也能带上。
                if (!manualStopped) {
                    com.aurora.chat.ModelLogger.error(
                        ctx, "ERR",
                        "streamChat 异常 | 上下文条数=${history.size} 错误=${e.message?.take(300)}", e
                    )
                }
                // 看门狗掐断(stopCheck 为真时 close 响应致 readLine 抛 IO 异常):与流内 break 同语义,静默返回
                if (manualStopped) return@withContext
                // 无数据看门狗掐断:流超过 60s 无任何 data 行 → 主动中断并明确报错,不留黑洞
                // P1-4 可观测性:错误文本带最后 activity 时间戳与停滞时长,便于定位「是卡流还是压根没连上」
                if (stalled) {
                    val stalledSeconds = if (lastDataTs > 0L) (System.currentTimeMillis() - lastDataTs) / 1000L else 0L
                    val lastDataText = if (lastDataTs > 0L) {
                        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(lastDataTs))
                        "最后收到数据于 $ts(已停滞 ${stalledSeconds}s)"
                    } else "连接建立后从未收到任何数据"
                    Log.e(TAG, "streamChat 流停滞(>3min 无数据)被看门狗掐断, $lastDataText")
                    withContext(Dispatchers.Main) { onError("AI 响应停滞超过 3 分钟，已自动中断。$lastDataText。可重试。") }
                    return@withContext
                }
                // 协程被取消(如离开沙盒)属正常流程,原样抛出交由上层忽略,不显示为错误
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Log.e(TAG, "streamChat error", e)
                // 【断流不是模型故障,别甩天书给用户】
                // 流式读取中途抛 EOFException 的真实含义是「连接被上游/中间网关提前关闭」——
                // SSE 走 chunked 传输,数据块读到一半连接没了就会抛它,而它的 message 是 null,
                // 于是旧文案 `e.message ?: e.javaClass.simpleName` 直接把类名 "EOFException" 端到用户脸上:
                // 既没有任何可执行信息,又让人以为是模型坏了。这里翻译成人话。
                // 注意:是否重试不在这里决定 —— 调用方用 isRetriable(文案) 判定,这条文案不含致命关键词,
                // 因此会走「原地重试、保留已生成进度」的兜底(用户看到的"任务没真的中断"就是它)。
                val friendlyErr = when {
                    e is java.io.EOFException -> "连接被上游提前关闭（流式响应中途断流）"
                    e is java.io.IOException && e.message.isNullOrBlank() -> "网络连接异常"
                    else -> e.message ?: e.javaClass.simpleName
                }
                withContext(Dispatchers.Main) { onError("请求失败: $friendlyErr") }
            }
        }
    }

    // ===== AI 沙盒文件工具(应用私有工作区) =====
    // 所有文件操作都被限定在 <filesDir>/ai_files 内;采用自包含文本协议(模型不支持原生 function-calling 也能用)。
    const val AGENT_ROOT = "ai_files"
    /** AI 备份目录名(与工作区 ai_files 同级,但绝不在沙盒内,任何访问模式下 AI 都禁止触碰)。 */
    const val BACKUP_ROOT = "workspace_backups"
    const val TOOL_START = "@tool:start"
    const val TOOL_END = "@tool:end"
    /**
     * 普通模式下单个用户请求的工具轮数上限。
     *
     * ⚠️ 它的定位是**失控保护（runaway guard）**，不是"生产力闸"。
     * 之前是 6 —— 实际后果：切换模式 1 轮 + 看工作区 1 轮 + 读几个文件就没额度了，
     * 模型"连项目都没了解完"就被迫中断；更糟的是这个数字会被写进系统提示
     * （「每个用户请求最多执行 N 次工具调用」），模型看到 6 就会**不敢先了解、急着动手**。
     * 方案模式与多 AI 模式早已改为「不设上限」（理由：步骤天然多步），普通模式同理。
     *
     * 这里取一个**宽松到实际不会咬人**的有限值：既让"了解项目 → 动手 → 收尾"这种正常流程
     * 完全不受限，又保留一道硬墙，防止真失控时无上限烧 token/费用
     * （真正的细粒度保护由「连续失败硬上限」「重复验收上限」「交付契约」「用户随时停止」
     *  「付费余额切断」共同承担）。
     */
    const val MAX_AGENT_TOOL_ROUNDS = 60
    /** 取出文件工具的标识:AI 用它将工作区文件作为可下载/可打开的文件卡片发出来 */
    const val TOOL_GET_FILE = "get_file"
    const val TOOL_GENERATE_MEDIA = "generate_media"
    const val TOOL_EXECUTE_PYTHON = "execute_python"
    /** 通用脚本执行:按扩展名自动调度到已导入的运行组件(不限于 Python) */
    const val TOOL_RUN_SCRIPT = "run_script"
    // 逆向分析工具(全模式通用,不独占:逆向模式靠专业取证提示词 + 不限轮数区分,而非靠禁用他人)
    const val TOOL_REVERSE_STRINGS = "reverse_strings"
    const val TOOL_REVERSE_DEX = "reverse_dex"
    const val TOOL_REVERSE_NATIVE = "reverse_native"
    const val TOOL_REVERSE_DISASM = "reverse_disasm"

    /**
     * 需要过「自我保护」检查的工具：凡是能对**外部样本**做逆向 / 拆包 / 安装 / 执行的都在内。
     *
     * 为什么不把 read_file / list_files 也放进来：那两个是工作区常规操作，放进来会把正常读写全拖慢
     * （每次都要读 APK 元数据）。真正危险的是"分析样本"和"安装样本"这两类，它们必然带样本路径。
     * execute_python / shell_exec 也纳入：模型完全可以写脚本去拆，只拦 reverse_* 是漏的。
     */
    private val SELF_GUARD_TOOLS = setOf(
        TOOL_REVERSE_STRINGS, TOOL_REVERSE_DEX, TOOL_REVERSE_NATIVE, TOOL_REVERSE_DISASM,
        "install_apk", "uninstall_app",
        "shell_exec", "run_adb", "execute_python", "run_script",
        "extract_archive", "download_file", "ftp_transfer"
    )

    /**
     * 需要挂「自我保护」**事前提示**的工具（schema 描述末尾加一句，约 20 字/个）。
     *
     * 只覆盖"用户会把样本直接指过来"的入口：逆向四件套 + install_apk + extract_archive。
     * 不给 execute_python / shell_exec 这类通用工具挂——那是噪声（日常写脚本完全无关），
     * 而且真有人绕路走它，工具层照样会拦（见 [SELF_GUARD_TOOLS]）。
     *
     * 与 [SELF_GUARD_TOOLS] 分开是有意的：**执行**要全覆盖（堵死所有绕过路径），
     * **告知**只要覆盖入口（省 token，也让提示挂在它语义上属于的地方——工具文档）。
     */
    private val SELF_GUARD_HINT_TOOLS = setOf(
        TOOL_REVERSE_STRINGS, TOOL_REVERSE_DEX, TOOL_REVERSE_NATIVE, TOOL_REVERSE_DISASM,
        "install_apk", "extract_archive"
    )

    /** 自我保护事前提示文案（挂在受保护入口类工具的 schema 描述末尾）。 */
    private const val SELF_GUARD_HINT = "(系统边界:针对本应用自身安装包的分析会被拦截)"
    // 后台常驻服务（服务托管）：AI 能在沙盒里起一个「不退出的服务」并自己管理
    const val TOOL_SERVE_START = "serve_start"
    const val TOOL_SERVE_STOP = "serve_stop"
    const val TOOL_SERVE_LIST = "serve_list"
    const val TOOL_SERVE_LOG = "serve_log"
    // 专业开发者工具
    const val TOOL_FETCH_URL = "fetch_webpage"
    const val TOOL_SHELL_EXEC = "shell_exec"
    const val TOOL_RUN_ADB = "run_adb"
    // 专业开发者工具(第二批)
    const val TOOL_HTTP_REQUEST = "http_request"
    const val TOOL_PROCESS_LIST = "process_list"
    const val TOOL_PING = "ping_host"
    const val TOOL_SEARCH_WEB = "search_web"
    const val TOOL_DEEP_RESEARCH = "deep_research"
    const val TOOL_AGENT_REVIEW = "agent_review"

    /** 单次任务 agent_review 调用硬上限:首次验收 + 复验 2 次。超过直接拒绝执行(编排层硬兜底,
     *  不依赖模型自觉),防止「验收→FAIL→复验→FAIL→再复验…」无限循环拖死会话。 */
    const val MAX_AGENT_REVIEW_CALLS = 3

    /**
     * 嵌套子循环触发软截止(禁工具催结论)后,还留给模型多少轮把结论写完。
     * 取 3 轮而不是 1 轮:模型刚被催时通常还要补一两句收尾;太少会把"已经想到结论但没写完"也打断,
     * 那和直接超时掐断没区别。太多则又失去截止的意义。
     */
    private const val AGENT_SUBLOOP_DEADLINE_GRACE = 3
    /** 复验超限时回喂给执行者的结果 JSON */
    fun reviewCapExceededResult(): String = JSONObject()
        .put("ok", false)
        .put("error", "agent_review 调用已达本次任务上限")
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
    // 定位与相机
    const val TOOL_GET_CURRENT_LOCATION = "get_current_location"
    const val TOOL_OPEN_FRONT_CAMERA = "open_front_camera"
    const val TOOL_OPEN_REAR_CAMERA = "open_rear_camera"
    // 设备控制与系统开关
    const val TOOL_SET_VOLUME = "set_volume"
    const val TOOL_PLAY_AUDIO = "play_audio"
    const val TOOL_PLAY_VIDEO = "play_video"
    const val TOOL_VIBRATE = "vibrate"
    const val TOOL_SET_BRIGHTNESS = "set_brightness"
    const val TOOL_KEEP_SCREEN_ON = "keep_screen_on"
    const val TOOL_SET_SCREEN_ORIENTATION = "set_screen_orientation"
    const val TOOL_SET_BLUETOOTH = "set_bluetooth"
    const val TOOL_SET_GPS = "set_gps"
    const val TOOL_SET_WIFI = "set_wifi"
    const val TOOL_SET_NFC = "set_nfc"
    const val TOOL_SET_FLASHLIGHT = "set_flashlight"
    const val TOOL_BATTERY = "battery_manage"
    // 闹钟 / 定时提醒（真正落系统 AlarmManager：进程被杀、重启后仍生效）
    const val TOOL_SET_ALARM = "set_alarm"
    const val TOOL_CANCEL_ALARM = "cancel_alarm"
    const val TOOL_LIST_ALARMS = "list_alarms"
    // App 内部业务工具(AI 操作本 App 自身功能)
    const val TOOL_SWITCH_TAB = "switch_tab"
    const val TOOL_SEND_MESSAGE = "send_message"
    /** 把工作区里的文件作为「文件卡片 / 图片 / 视频」消息发送给好友(私聊)或群(群聊)——真正发给别人。
     *  与 get_file 的区别:get_file 是把文件发给【当前用户自己】看;本工具是替用户把文件发给【聊天对象】。 */
    const val TOOL_SEND_FILE = "send_file"
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
    const val TOOL_RESULT_BACKFEED_MAX = 6000
    /** 单字段折叠阈值:JSON 内单个字符串字段超过该长度才折叠 */
    const val TOOL_RESULT_FIELD_MAX = 3000

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
                    obj.put(k, v.take(1500) + "\n…[中段已折叠:原文共 " + v.length + " 字符]…\n" + v.takeLast(900))
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
            raw.take(2000) + "\n…[结果过长已截断,原文共 " + raw.length + " 字符]…\n" + raw.takeLast(1000)
        }
    }

    // ===== 历史上下文构造(P3:模式/思考深度自适应 + 超长折叠)=====
    /** 单条消息折叠阈值:历史中超过该 token 数的单条消息在发送前折叠为摘要(头+尾),防止"条数不多但每条爆长" */
    const val CONTEXT_MSG_FOLD_MAX = 1800
    // ===== 上下文窗口 / token 预算(P4:根治长文本溢出与早期消息丢失)=====
    /** 模型上下文窗口(单位 token,估算值)。按模型名前缀匹配,未匹配用默认。用于发送前预算,根治上下文溢出。 */
    private val MODEL_CONTEXT_WINDOWS = linkedMapOf(
        "agnes-2.5" to 200_000,
        "gpt-5" to 400_000,
        "gpt-4o" to 128_000,
        "gpt-4" to 128_000,
        "o1" to 200_000, "o3" to 200_000, "o4" to 200_000,
        "claude" to 200_000,
        "gemini" to 1_000_000,
        "deepseek" to 128_000,
        "qwen" to 128_000,
        "kimi" to 128_000,
        "yi" to 128_000,
        "mistral" to 128_000,
    )
    private const val DEFAULT_CONTEXT_WINDOW = 128_000
    /** 系统提示 + 工具 schema 的粗略 token 开销预留,避免预算算满后把系统提示挤爆 */
    private const val CONTEXT_SYSTEM_OVERHEAD = 6_000
    /** 安全余量,吸收估算误差 */
    private const val CONTEXT_SAFETY_MARGIN = 2_000
    /** 单条消息的 token 折叠阈值:超过才压缩(保留头尾),普通消息原样保留,降低"忘中段" */
    private const val MSG_TOKEN_FOLD_MAX = 1_500

    private fun contextWindowFor(model: String): Int {
        val m = model.lowercase()
        for ((k, v) in MODEL_CONTEXT_WINDOWS) if (m.contains(k)) return v
        return DEFAULT_CONTEXT_WINDOW
    }

    /** P3:构造发送给模型的历史上下文,取代旧的硬截断 take(1)+takeLast(N)。
     *  增强点(兼容多种执行模式):
     *  ① 条数预算按模式自适应:极速模式收紧,其余维持 40;
     *  ② 超长单条折叠(保留头尾+折叠标记),与工具结果回喂裁剪(condenseToolResult)双管齐下防膨胀;
     *  ③ 被编辑的消息(含最新文本)始终强制进入上下文,编辑对 AI 永远生效;
     *  ④ 首条作为种子保留,会话基调不丢。
     *  发送前与确认发言前两条路径统一走本函数,行为一致。 */
    /** 构造发送给模型的历史上下文(P4:token 预算驱动,根治长文本溢出与早期消息丢失)。
     *  关键改进:
     *  ① 硬性条数上限(兼容 FAST/深度)只作最终兜底,不再作为主要裁剪手段;
     *  ② token 预算驱动:从新到旧贪心填充,保留首条种子 + 被编辑消息,近因消息完整保留(不再腰斩);
     *  ③ 预算外的旧消息不再物理丢弃,而是压缩为极简存根(保留身份/长度信号),显著缓解"忘这忘那";
     *  ④ 单条超长消息仅在显著超 token 阈值时折叠(保留头尾),普通消息原样保留。
     *  model / outputBudget 可选:传入后预算更精确;缺省按默认窗口与输出预留估算。 */
    fun buildChatContext(
        candidates: List<IMessageRef>,
        editTargetMsgId: Long = 0L,
        ctx: Context? = null,
        userId: Long = 0L,
        model: String = "",
        outputBudget: Int = 0
    ): List<IMessageRef> {
        // ① 硬性条数上限(最终兜底,避免极端情况下无限堆积)
        var hardCap = 40
        if (ctx != null && userId != 0L) {
            if (isFastMode(ctx, userId)) {
                hardCap = 20 // 极速模式:纯对话为主,收紧历史,最快最省
            }
        }
        val capped = if (candidates.size > hardCap) {
            candidates.take(1) + candidates.takeLast(hardCap - 1)
        } else candidates

        // ② token 预算:availableInput = 窗口 - 输出预算 - 系统/工具开销 - 安全余量
        val window = contextWindowFor(model)
        val outReserve = if (outputBudget > 0) outputBudget
            else if (ctx != null && userId != 0L) outputTokenBudget(ctx, userId) else 16_000
        val budget = maxOf(4_000, window - outReserve - CONTEXT_SYSTEM_OVERHEAD - CONTEXT_SAFETY_MARGIN)

        // 必须保留:首条种子 + 被编辑消息
        val edited = editTargetMsgId.takeIf { it != 0L }?.let { tid -> candidates.firstOrNull { it.id == tid } }
        val seed = capped.firstOrNull()
        val mustKeep = listOfNotNull(seed, edited).distinctBy { it.id }

        // 从新到旧贪心填充,近因消息完整保留
        val recent = mutableListOf<IMessageRef>()
        var used = 0
        for (m in capped.asReversed()) {
            if (m in mustKeep) continue
            val t = (m as? ChatMsg)?.text ?: ""
            val cost = maxOf(1, estimateTokens(t))
            if (used + cost > budget && recent.isNotEmpty()) break
            recent.add(0, m)
            used += cost
        }
        // ③ 预算外(更旧)消息:压缩为极简存根,保留身份与长度信号,不再物理丢弃
        val overflow = capped.filter { it !in mustKeep && it !in recent }
        val compacted = overflow.map { m ->
            val t = (m as? ChatMsg)?.text
            if (t == null || t.isBlank()) m
            else m.copy(text = "〔历史存档·约 " + estimateTokens(t) + " token〕" + t.take(120).replace('\n', ' '))
        }

        val ordered = buildList {
            addAll(compacted)                       // 旧消息存根在前
            addAll(mustKeep.filter { it !in recent }) // 种子/编辑
            addAll(recent)                          // 近因消息完整
        }
        // ④ 单条超长消息折叠(仅显著超阈值时,保留头尾,降低"忘中段")
        return ordered.map { msg ->
            val t = (msg as? ChatMsg)?.text ?: return@map msg
            if (estimateTokens(t) > MSG_TOKEN_FOLD_MAX) {
                val head = t.take(4000)
                val tail = t.takeLast(1500)
                msg.copy(text = head + "\n…[该条过长已折叠,原文约 " + estimateTokens(t) + " token,如需细节请追问]…\n" + tail)
            } else msg
        }
    }

    /** 原生 function calling 产生的工具调用(OpenAI tool_calls) */
    data class AINativeToolCall(val id: String, val name: String, val argumentsJson: String)

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
        "list_files", "read_file", "write_file", "edit_file", "append_file", "find_files", "stat_file",
        "search_in_files",
        "create_folder", "delete_file", "delete_folder", "rename_file", "copy_file", "restore_file",
        "extract_archive", "move_dir_contents", "compress_archive", "archive_list", "archive_read_file",
        "batch_replace", "apply_patch", "diff_text",
        "json_process", "regex_test", "encode_convert", "hash_digest", "timestamp_convert",
        "text_stats", "size_convert", "wait", "keep_alive", "ask_user", "query_environment",
        "work_log", "batch_loop",
        // 代码/命令执行属于核心能力：普通模式（minimal=true）也必须声明，否则模型根本看不到
        // execute_python / shell_exec，用户看到的就是「当前没有开放代码执行工具」。
        // 计划模式另有 PLAN_MODE_BLOCKED_TOOLS 拦截，不会因此获得写权限。
        TOOL_EXECUTE_PYTHON, TOOL_RUN_SCRIPT, "shell_exec",
        "upload_file", "download_file", "get_file", "qr_decode", "crypto_encrypt", "crypto_decrypt",
        "search_web", "deep_research", "fetch_webpage", "generate_media",
        TOOL_GET_CURRENT_LOCATION, TOOL_OPEN_FRONT_CAMERA, TOOL_OPEN_REAR_CAMERA,
        TOOL_SET_VOLUME, TOOL_PLAY_AUDIO, TOOL_PLAY_VIDEO, TOOL_VIBRATE, TOOL_SET_BRIGHTNESS,
        TOOL_KEEP_SCREEN_ON, TOOL_SET_SCREEN_ORIENTATION, TOOL_SET_BLUETOOTH,
        TOOL_SET_GPS, TOOL_SET_WIFI, TOOL_SET_NFC, TOOL_SET_FLASHLIGHT, TOOL_BATTERY,
        TOOL_SET_ALARM, TOOL_CANCEL_ALARM, TOOL_LIST_ALARMS
    )
    /** 聊天扩展工具:默认模式一并注入(发消息/联系人等与聊天强相关),其余低频/设备/主机/网络诊断工具只对增强模式开放 */
    private val CHAT_EXT_TOOL_NAMES = setOf(
        "switch_tab", "send_message", "send_file", "send_friend_request", "create_community_post",
        "list_my_contacts", "list_my_groups", "list_recent_messages", "recall_message"
    )

    /**
     * 工具意图分档（意图级 schema 裁剪，在 minimal 基础上再细化）：
     * - CRAFT：默认档，保留 minimal 全集（文件/代码执行/网络/设备/媒体…）。
     * - LEAN：纯对话 / 写代码类请求——从 minimal 再剔除设备控制/媒体/相机/定位/生成媒体等
     *   与意图无关的死重工具（详见 [LEAN_TIER_EXCLUDE]）。模型仍保留 set_agent_mode 可随时切到 craft 自我升级。
     * - DEVICE_MEDIA：设备/媒体意图——当前等同于 minimal（暂不做额外增删，占位便于后续调）。
     * 仅当 minimal（普通 Craft 模式）且非多 AI 时生效；multi_agent 全量、Ask/fast 走各自分支不受影响。
     */
    enum class ToolIntentTier { CRAFT, LEAN, DEVICE_MEDIA }

    /** LEAN 档从 minimal keep 集中剔除的工具：设备控制/媒体/相机/定位/生成媒体 */
    private val LEAN_TIER_EXCLUDE = setOf(
        TOOL_SET_VOLUME, TOOL_PLAY_AUDIO, TOOL_PLAY_VIDEO, TOOL_VIBRATE, TOOL_SET_BRIGHTNESS,
        TOOL_KEEP_SCREEN_ON, TOOL_SET_SCREEN_ORIENTATION, TOOL_SET_BLUETOOTH, TOOL_SET_GPS,
        TOOL_SET_WIFI, TOOL_SET_NFC, TOOL_SET_FLASHLIGHT, TOOL_BATTERY,
        TOOL_OPEN_FRONT_CAMERA, TOOL_OPEN_REAR_CAMERA, TOOL_GET_CURRENT_LOCATION,
        "generate_media"
    )

    /** 设备/媒体意图关键词（命中→DEVICE_MEDIA，当前等同 minimal，仅占位） */
    private val DEVICE_MEDIA_KEYWORDS = listOf(
        "音量", "声音", "静音", "亮度", "蓝牙", "闪光", "手电", "gps", "定位", "位置", "wi-fi", "wifi",
        "无线", "飞行", "nfc", "振动", "震动", "电池", "省电", "截屏", "截图", "屏幕", "相机", "拍照",
        "摄像", "录音", "播放", "音乐", "视频", "媒体", "铃声", "旋转", "横屏", "竖屏", "通知", "闹钟"
    )

    /** 代码/文件/web 构建/媒体生成类意图关键词（命中→CRAFT，保留全部能力，含 generate_media） */
    private val CRAFT_KEYWORDS = listOf(
        "文件", "代码", "脚本", "程序", "运行", "执行", "写", "改", "创建", "新建", "编辑", "修改", "读",
        "查", "搜索", "查找", "找", "网页", "网站", "浏览器", "下载", "上传", "安装", "包", "项目", "编译",
        "构建", "部署", "服务器", "主机", "终端", "命令", "adb", "python", "bug", "修复", "函数", "类",
        "接口", "api", "方案", "计划", "生成", "图片", "画", "海报", "照片", "音频", "媒体"
    )

    /**
     * 依据「当前这轮用户消息意图」+ 任务上下文推断工具分档。
     * 护栏：history 已含 tool 角色（即本轮任务中途已用过工具）→ 不退档，保持 CRAFT，避免任务中途抖动。
     * 纯对话（无任何工具相关关键词）→ LEAN；命中设备/媒体关键词 → DEVICE_MEDIA；其余 → CRAFT（默认）。
     */
    private fun toolIntentTier(ctx: Context, userId: Long, lastUserText: String?, history: List<Pair<String, String>>): ToolIntentTier {
        if (history.any { it.first == "tool" }) return ToolIntentTier.CRAFT
        val t = lastUserText?.trim().orEmpty()
        if (t.isEmpty()) return ToolIntentTier.CRAFT
        val lower = t.lowercase()
        if (DEVICE_MEDIA_KEYWORDS.any { lower.contains(it) }) return ToolIntentTier.DEVICE_MEDIA
        if (CRAFT_KEYWORDS.any { lower.contains(it) }) return ToolIntentTier.CRAFT
        return ToolIntentTier.LEAN
    }

    /** 构造标准 OpenAI tools 数组(6 个沙盒工具),用于原生 function calling。
     *  includeMultiAI=false(非多 AI 模式)时不声明 agent_plan/agent_fork/agent_review——
     *  验收/规划/并行派发是多 AI 模式专属能力,普通模式下模型不该看到、更不该自发调用。
     *  minimal=true 时只注入核心+聊天扩展集(约 45 个),丢弃设备/主机/网络诊断/进程等低频工具,
     *  把普通对话的 schema 体积压到一半以下;增强模式(long/multi_agent/research)传 false 全量注入。 */
    /** 工具目标覆盖率审计是否已执行（进程内只跑一次，避免每次生成 schema 都遍历） */
    private val toolTargetAudited = java.util.concurrent.atomic.AtomicBoolean(false)

    fun buildAIToolsSchema(includeMultiAI: Boolean = true, minimal: Boolean = false, intentTier: ToolIntentTier = ToolIntentTier.CRAFT): JSONArray {
        val tools = JSONArray()
        fun pathP(desc: String) = JSONObject().put("type", "string").put("description", desc)
        fun boolP(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
        fun fn(name: String, desc: String, props: JSONObject, required: Array<String>) {
            val params = JSONObject().put("type", "object").put("properties", props)
            if (required.isNotEmpty()) params.put("required", JSONArray().apply { required.forEach { put(it) } })
            // 【自我保护·事前提示】只给"用户会拿样本直接指过来"的入口类工具挂一句边界说明
            // （全表只此一处，每个约 20 字）。没有这句，模型会先调用 → 撞拦截 → 才从一个 error
            // 里反推规则，白烧一轮还看得莫名其妙。这是**工具文档**，属于 schema 该承载的信息，
            // 所以放这里，而不是写成系统提示里的政策散文（那个每个请求都付费且拦不住人）。
            val finalDesc = if (name in SELF_GUARD_HINT_TOOLS) desc + SELF_GUARD_HINT else desc
            tools.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", name).put("description", finalDesc).put("parameters", params)))
        }
        val noReq = emptyArray<String>()
        val pathReq = arrayOf("path")
        fn("list_files", "列出工作区(ai_files)内指定目录下的子目录与文件;path 省略时表示根目录。",
            JSONObject().apply { put("path", pathP("工作区内相对目录路径,省略表示根目录")) }, noReq)
        fn("read_file", "读取工作区内一个文本文件的内容。可用 start_line/end_line 指定读取的行段(从 1 开始,含端点),返回该行段内容并在 line_start/line_end 标明实际区间,便于引用具体行;省略行段参数则读取全文。",
            JSONObject().apply {
                put("path", pathP("工作区内相对文件路径"))
                put("start_line", pathP("可选:起始行号(从 1 开始),与 end_line 配合读取指定行段"))
                put("end_line", pathP("可选:结束行号(含),省略时读到文件末尾"))
                put("max_lines", pathP("可选:最多读取行数,防止一次读入过多内容"))
            }, pathReq)
        fn("write_file", "仅用于在工作区内创建全新的 UTF-8 文本文件。禁止用于修改已有文件:整体覆盖会丢失未改动内容,且写入错误将破坏整个文件;修改已有文件须改用 edit_file 定点替换、apply_patch 应用 diff,或 batch_replace 批量替换。",
            JSONObject().apply {
                put("path", pathP("工作区内相对文件路径"))
                put("content", pathP("要写入的文本内容(可省略)"))
            }, pathReq)
        fn("edit_file", "对工作区内已有文本文件做定点修改:将 old_string 精确替换为 new_string,仅改动匹配处(或全部匹配)。适用于修复缺陷、新增函数、修改配置等局部编辑。old_string 必须与文件中某段完全一致(建议包含上下空行/缩进以保证唯一);replace_all=true 时替换所有匹配。未找到或存在歧义时报错,不会静默改写。任何删除/修改前系统已自动备份,出错可用 restore_file 恢复。",
            JSONObject().apply {
                put("path", pathP("工作区内相对文件路径"))
                put("old_string", pathP("要被替换的原始文本片段(必须与文件内容完全一致,含空白/缩进)"))
                put("new_string", pathP("替换后的新文本"))
                put("replace_all", pathP("可选:true 时替换所有匹配,默认 false 仅改第一处"))
            }, arrayOf("path", "old_string", "new_string"))
        fn("restore_file", "从自动备份恢复工作区文件,用于文件被整体覆盖或误删后的兜底恢复。path 为工作区相对路径;可选 backup 指定具体备份文件名(省略则恢复最近一次)。返回恢复到的路径与大小。",
            JSONObject().apply {
                put("path", pathP("要恢复的工作区相对文件路径"))
                put("backup", pathP("可选:指定备份文件名(agent_backups 目录内),省略用最近一次"))
            }, arrayOf("path"))
        fn("delete_file", "删除工作区内的一个文件。",
            JSONObject().apply { put("path", pathP("工作区内相对文件路径")) }, pathReq)
        fn("create_folder", "在工作区内创建目录。",
            JSONObject().apply { put("path", pathP("工作区内相对目录路径")) }, pathReq)
        fn("delete_folder", "递归删除工作区内的一个目录(禁止删除工作区根目录)。",
            JSONObject().apply { put("path", pathP("工作区内相对目录路径")) }, pathReq)
        // ===== 文件读写增强工具 =====
        fn("rename_file", "重命名或移动工作区内的一个文件或目录。",
            JSONObject().apply {
                put("path", pathP("要改名的文件/目录相对路径"))
                put("new_name", pathP("新的名字(不含目录;目标须仍在工作区内)"))
            }, arrayOf("path", "new_name"))
        fn("copy_file", "在工作区内将文件或目录复制到目标相对路径。",
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
        fn("find_files", "在工作区内按名称关键字递归搜索文件与目录(大小写不敏感)。",
            JSONObject().apply {
                put("keyword", pathP("要匹配的名称关键字"))
                put("path", pathP("搜索起点相对目录;省略表示根目录"))
            }, arrayOf("keyword"))
        fn("search_in_files", "在工作区内按【文件内容】递归搜索(grep 语义):返回命中的「文件路径 + 行号 + 该行内容」。" +
            "与 find_files 的区别:find_files 只搜文件名,本工具搜文件里的内容。适用场景:定位某个函数/变量/字符串/报错信息出现在哪些文件;" +
            "大规模代码或文本里找线索;核对某处改动是否真的落到了文件里。\n" +
            "· query:要搜索的内容;regex=true 时按正则解释(默认 false 按纯文本子串匹配,更安全)。\n" +
            "· path:搜索起点相对目录(省略=工作区根目录)。\n" +
            "· glob:文件名过滤,支持逗号分隔多个通配,如 \"*.kt\" 或 \"*.py,*.md\"(省略=所有文本文件)。\n" +
            "· case_sensitive:是否区分大小写,默认 false。\n" +
            "· max_results:最多返回多少条命中,默认 60,上限 200。\n" +
            "自动跳过二进制文件、超大文件(>2MB)与常见依赖/缓存目录;结果超上限时会标记 truncated=true,请用更精确的 query/glob 收窄后重搜。",
            JSONObject().apply {
                put("query", pathP("要搜索的内容关键字(或正则,见 regex 参数)"))
                put("path", pathP("搜索起点相对目录;省略表示工作区根目录"))
                put("glob", pathP("可选:文件名过滤,如 *.kt 或 *.py,*.md(省略=所有文本文件)"))
                put("regex", boolP("可选:true 时把 query 当正则表达式;默认 false 按纯文本子串匹配"))
                put("case_sensitive", boolP("可选:是否区分大小写,默认 false"))
                put("max_results", pathP("可选:最多返回条数,默认 60,上限 200"))
            }, arrayOf("query"))
        fn("extract_archive", "解压工作区内一个 ZIP 压缩包到指定目录。支持相对路径;target 省略时解压至压缩包同级目录下、以压缩包同名的文件夹中(如 data.zip → data/)。自动拒绝 zip slip 路径穿越(条目含 .. 或绝对路径)。",
            JSONObject().apply {
                put("source", pathP("ZIP 压缩包在工作区内的相对路径,如 downloads/data.zip"))
                put("target", pathP("解压目标目录相对路径(可选,省略=压缩包所在目录下同名文件夹)"))
            }, arrayOf("source"))
        fn("move_dir_contents", "将源目录内所有直接子项(文件与子目录)一次性搬至目标目录,单次调用处理 N 个文件,避免逐个 move/rename。若目标已存在同名条目,自动追加 _1、_2 直至无冲突。source 与 target 均为工作区内相对路径:\n• target 省略 = 源的上一级目录;\n• target 传 '.' 或 'root' = 工作区根目录(内容平铺至 ai_files 根);\n• target 传其他相对路径 = 该目录。\n返回含 moved/failed/success/message:failed=0 且 moved>0 即表示全部成功,按实际结果如实汇报,不得臆造系统降级或上限等不存在的报错。常见于:① 解压后将文件夹内容平铺到工作区根目录(传 target='root');② 批量搬运至另一目录。",
            JSONObject().apply {
                put("source", pathP("源目录相对路径"))
                put("target", pathP("目标目录相对路径:省略=上一级目录;'.'或'root'=工作区根目录;其他=相对路径"))
            }, arrayOf("source"))
        // ===== 压缩包体系 =====
        fn(TOOL_COMPRESS_ARCHIVE, "将工作区内一个目录或一组文件打包为 ZIP 压缩包。sources 为相对路径数组(支持文件/目录),target 为输出 zip 相对路径(如 out/data.zip)。自动跳过 zip slip。",
            JSONObject().apply {
                put("sources", pathP("要打包的源相对路径数组,如 [\"src\", \"README.md\"]"))
                put("target", pathP("输出 ZIP 相对路径(以 .zip 结尾)"))
                put("root", pathP("可选:打包时使用的内部根目录名,空=保持原结构"))
            }, arrayOf("sources", "target"))
        fn(TOOL_ARCHIVE_LIST, "列出一个 ZIP 压缩包内的所有条目(含嵌套路径、大小、类型)。只读,不解压。",
            JSONObject().apply { put("path", pathP("ZIP 在工作区内的相对路径")) }, arrayOf("path"))
        fn(TOOL_ARCHIVE_READ, "将 ZIP 压缩包内某个条目作为文本返回。path 为 zip 相对路径,entry 为 zip 内部相对路径(可用 archive_list 先查)。最大返回 256KB,超出截断并提示用 extract_archive 完整解压。",
            JSONObject().apply {
                put("path", pathP("ZIP 在工作区内的相对路径"))
                put("entry", pathP("zip 内部条目的相对路径,如 src/Main.kt"))
            }, arrayOf("path", "entry"))
        // ===== 进程/应用控制 =====
        fn(TOOL_KILL_PROCESS, "终止一个正在运行的进程。root 模式下直接以 kill -9 强制结束;非 root 模式下仅对本应用进程有效。pid 可先用 process_list 取得。",
            JSONObject().apply {
                put("pid", pathP("进程 PID(数字)"))
                put("signal", pathP("可选:信号名 KILL/TERM/INT,默认 KILL"))
            }, arrayOf("pid"))
        fn(TOOL_STOP_APP, "停止一个第三方应用的前台 Activity(拉起后立即关闭,本质为调用 force-stop)。非 root 模式下仅能停止本应用自身;root 模式下可停止任意应用。target 传包名(如 com.example.app)。",
            JSONObject().apply { put("target", pathP("目标应用包名")) }, arrayOf("target"))
        // ===== 上传(从工作区到 URL) =====
        fn(TOOL_UPLOAD_FILE, "将工作区内一个文件以 HTTP POST multipart/form-data 上传到指定 URL。headers 可选(JSON 字符串)。适用于将工作区文件发送至用户的服务器、云存储或 API 端点。",
            JSONObject().apply {
                put("path", pathP("要上传的工作区内相对文件路径"))
                put("url", pathP("目标上传 URL(http/https)"))
                put("headers", pathP("可选:自定义请求头 JSON 字符串"))
                put("field", pathP("可选:multipart 字段名,默认 file"))
            }, arrayOf("path", "url"))
        // ===== 工作日志 =====
        fn(TOOL_WORK_LOG, "查看或清空本应用私有工作日志目录(AI 自身的操作记录,独立于系统 logcat)。mode=list 列出最近 N 条(默认 50);mode=clear 清空全部。size 字段显示日志总大小,用于防止体积无限增长。",
            JSONObject().apply {
                put("mode", pathP("list=列出日志内容(默认) / clear=清空全部日志"))
                put("limit", pathP("list 模式下最多返回条数,默认 50,最多 500"))
            }, noReq)
        // ===== 跨文件批处理 =====
        fn(TOOL_BATCH_REPLACE, "在工作区内一批文本文件中查找并替换字符串(单次调用处理 N 个文件,比逐文件 replace 更高效)。paths 为相对路径数组;pattern/replacement 为纯字符串(非正则,避免灾难性回溯)。返回每个文件修改次数与总修改次数。",
            JSONObject().apply {
                put("paths", pathP("待处理文件的相对路径数组,如 [\"src/A.kt\", \"src/B.kt\"]"))
                put("pattern", pathP("要查找的字符串(纯文本,非正则)"))
                put("replacement", pathP("替换为的字符串"))
            }, arrayOf("paths", "pattern", "replacement"))
        // ===== 网络安全 =====
        fn(TOOL_DNS_LOOKUP, "对一个域名做 DNS 解析,返回解析到的所有 IP 地址(A/AAAA)与解析耗时。",
            JSONObject().apply { put("host", pathP("要解析的域名,如 example.com")) }, arrayOf("host"))
        fn(TOOL_PORT_SCAN, "扫描一个主机的指定端口范围,返回每个端口是否开放及常见服务名识别。ports 省略时扫描 1-1024 常用端口(范围限制在 1024 以内)。timeout 为单端口超时秒数,默认 2。",
            JSONObject().apply {
                put("host", pathP("目标主机或 IP"))
                put("ports", pathP("可选:端口数组或范围字符串,如 [22,80,443] 或 \"1-1024\""))
                put("timeout", pathP("单端口超时秒数,默认 2,最多 5"))
            }, arrayOf("host"))
        fn(TOOL_TRACEROUTE, "对一个主机做 ICMP 路由追踪,返回每一跳的 IP、耗时、域名解析与总跳数。依赖 root 权限发送真正的 ICMP;非 root 模式下回退至 UDP 探测,可能被防火墙拦截。",
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
        // ===== 逆向分析工具(全模式通用,不按模式门控;依赖的内置库缺失时如实降级) =====
        fn(TOOL_REVERSE_STRINGS, "逆向·敏感串猎手:扫描二进制/文本样本,抽取并分类 URL、IP、邮箱、API Key/Token/密码、Base64 大串、JNI 函数名(Java_xxx)、包名、文件路径、SQL 片段等。纯 Python 实现,无需外部依赖,永远可跑。完整分类结果写入工作区报告文件,返回命中数量摘要。",
            JSONObject().apply {
                put("path", pathP("工作区内待扫描文件的相对路径(如 samples/app.apk、samples/libnative.so、或任意二进制)"))
                put("min_length", pathP("可选:可打印串的最小长度,默认 4"))
            }, arrayOf("path"))
        fn(TOOL_REVERSE_DEX, "逆向·APK/DEX 审计:解析 APK 内 classes.dex 头部计数(字符串/类型/方法/类定义数量)、AndroidManifest 四大组件与权限(依赖 androguard 时补类名,缺失则跳过)、并扫描 DEX 字节命中加密(Cipher/MessageDigest/Mac/SecretKeySpec)、反射(Class.forName/Method.invoke)、网络(HttpURLConnection/OkHttp/Retrofit)、loadLibrary 等 API 频次与样例 URL/密钥串。完整结果写入工作区报告文件。",
            JSONObject().apply { put("path", pathP("工作区内 APK 文件的相对路径(如 samples/app.apk)")) }, arrayOf("path"))
        fn(TOOL_REVERSE_NATIVE, "逆向·原生库分析:解析 .so/ELF 的架构/位数/字节序,用 lief 抽取段表、导入/导出函数、依赖库,检测 JNI_OnLoad 与 RegisterNatives 是否出现;lief 缺失时回退到 ELF 头解析并扫描 JNI 符号串与 URL。完整结果写入工作区报告文件。",
            JSONObject().apply { put("path", pathP("工作区内 ELF/.so 文件的相对路径(如 samples/libnative.so)")) }, arrayOf("path"))
        fn(TOOL_REVERSE_DISASM, "逆向·反汇编:将二进制文件从指定偏移反汇编为汇编指令;自动按 ELF 机器类型识别 ARM64/ARM/x86 架构(capstone 缺失时自动尝试安装)。返回汇编清单,完整结果另写入工作区报告文件。",
            JSONObject().apply {
                put("path", pathP("工作区内待分析文件的相对路径(如 samples/libnative.so)"))
                put("offset", pathP("可选:反汇编起始偏移(十六进制如 0x1000 或十进制),默认 0"))
                put("count", pathP("可选:最多反汇编的指令条数,默认 64"))
            }, arrayOf("path"))
        // ===== 后台常驻服务（沙盒里跑「不退出的服务」；配合「服务器样式 → 反向代理」可让网页调到本地后端）=====
        fn(TOOL_SERVE_START, "启动一个后台常驻服务：在设备内置 Linux 运行环境(proot)里跑一段 Python 代码或一个工作区脚本，进程**不会随本次调用结束而退出**，可长期监听端口。典型用途：起本地后端 / API 服务，再把网页的 /api 请求经「服务器样式 → 进阶 → 反向代理」转发到 127.0.0.1:<端口>，实现纯本机的前后端联调(不依赖任何外部服务器)。proot 不隔离网络，guest 内监听 127.0.0.1:<port>，本机 App 与网页均可直接访问。幂等：同名 tag 会先停掉旧进程再起新的。注意：一次性、跑完就退的脚本请用 execute_python，不要用本工具。",
            JSONObject().apply {
                put("tag", pathP("服务标识(必填)：字母/数字/下划线/点/横线，如 backend、api8080。同名会先停旧的再起新的"))
                put("code", pathP("要常驻运行的 Python 代码(与 script 二选一)。代码应自行 bind 端口并 serve_forever"))
                put("script", pathP("要常驻运行的工作区脚本相对路径(与 code 二选一)，如 server.py"))
            }, arrayOf("tag"))
        fn(TOOL_SERVE_STOP, "停止一个由 serve_start 启动的后台常驻服务：先温和终止，2 秒不退则强杀。停止后该服务监听的端口立即释放。",
            JSONObject().apply { put("tag", pathP("要停止的服务标识(必填)，先用 serve_list 查看")) }, arrayOf("tag"))
        fn(TOOL_SERVE_LIST, "列出当前所有后台常驻服务：标识、PID、已运行秒数、日志文件路径。用于确认服务是否真的活着——若代码有语法错误或端口被占用，进程会立刻退出，此时它不会出现在列表里。",
            JSONObject(), noReq)
        fn(TOOL_SERVE_LOG, "读取某个后台服务的运行日志尾部(含 stdout / stderr，以及每次启动/退出的记录)。服务起不来、网页代理报 502、请求无响应时，第一时间用本工具看真实报错。",
            JSONObject().apply { put("tag", pathP("服务标识(必填)")) }, arrayOf("tag"))
        fn(TOOL_SEND_NOTIFICATION, "在系统通知栏发送一条提醒通知,用户点开可跳转或执行预设 action(当前 action 仅支持 open_app 打开本应用)。title 必填,body 可选。系统会自动请求 POST_NOTIFICATIONS 权限(Android 13+)。",
            JSONObject().apply {
                put("title", pathP("通知标题(必填)"))
                put("body", pathP("通知正文(可选)"))
                put("action", pathP("可选:点击通知执行的动作,当前支持 open_app"))
            }, arrayOf("title"))
        // ===== Diff/补丁/二维码/加解密 =====
        fn(TOOL_APPLY_PATCH, "将一个 unified diff 补丁文本应用到工作区内的目标文件(或 files 数组批量应用)。strict=true 时补丁须精确匹配上下文;strict=false 时自动降级到最后一行匹配。返回每个文件应用的 hunk 数与失败原因。",
            JSONObject().apply {
                put("files", pathP("补丁要应用到的目标相对路径数组,如 [\"src/A.kt\"]"))
                put("patch", pathP("unified diff 补丁完整文本(必须以 --- / +++ 开头)"))
                put("strict", pathP("是否严格模式,默认 true"))
            }, arrayOf("files", "patch"))
        fn(TOOL_QR_DECODE, "识别工作区内一张二维码图片文件,解码返回原始内容文本(URL / 文本 / vCard 等)。失败返回 error 字段。",
            JSONObject().apply { put("path", pathP("图片在工作区内的相对路径,如 images/qr.png")) }, arrayOf("path"))
        fn(TOOL_CRYPTO_ENCRYPT, "用 AES-256-CBC 模式加密工作区内一个文件或一段纯文本。key 可为任意长度字符串,内部以 SHA-256 取前 32 字节标准化。输出为 base64 密文或新文件(传 dest 时落盘)。",
            JSONObject().apply {
                put("path", pathP("要加密的工作区内相对文件路径(与 content 二选一)"))
                put("content", pathP("要加密的纯文本字符串(与 path 二选一)"))
                put("key", pathP("加密密钥(字符串,任意长度,内部 SHA-256 标准化)"))
                put("dest", pathP("可选:加密后落盘的目标相对路径;不传则返回 base64 密文"))
            }, arrayOf("key"))
        fn(TOOL_CRYPTO_DECRYPT, "用 AES-256-CBC 模式解密一个 base64 密文或文件。key 须与加密时相同。path/content 二选一。传 dest 时解密落盘,不传则返回明文。",
            JSONObject().apply {
                put("path", pathP("要解密的工作区内密文文件路径(与 content 二选一)"))
                put("content", pathP("要解密的 base64 密文字符串(与 path 二选一)"))
                put("key", pathP("解密密钥,与加密时相同"))
                put("dest", pathP("可选:解密后落盘的目标相对路径;不传则返回明文"))
            }, arrayOf("key"))
        // ===== 备份工具(只读 + 恢复审批)=====
        fn(TOOL_LIST_BACKUPS, "列出工作区之外所有备份的基础信息(只读):备份目录名/创建时间/范围(整个工作区或某文件夹)/大小/文件数。AI 仅可读取这些元信息,不得修改备份。需查看备份内具体文件内容时用 read_backup_file。",
            JSONObject(), noReq)
        fn(TOOL_READ_BACKUP, "读取某个备份内的一个文本文件内容(只读)。路径严格限定在该备份目录内,禁止绝对路径或 .. 上跳;AI 可借此查看历史内容,但不得改动备份。",
            JSONObject().apply {
                put("backup", pathP("备份目录名,如 backup_1690000000000(先用 list_backups 取得)"))
                put("path", pathP("备份内的相对文件路径,如 src/MainActivity.kt"))
            }, arrayOf("backup", "path"))
        fn(TOOL_RESTORE_BACKUP, "请求将某个备份恢复到工作区。该工具不会立即执行,而是弹出审批提示由用户手动批准;无论当前为手动/完全访问/root 模式,均须用户批准方可恢复,用户拒绝则不做任何改动。恢复仅将备份内容写回工作区,备份本身绝不被 AI 改动。",
            JSONObject().apply {
                put("backup", pathP("要恢复的备份目录名,如 backup_1690000000000(先用 list_backups 取得)"))
            }, arrayOf("backup"))
        fn("query_environment", "查询用户设备运行环境信息:型号、厂商、Android 版本、存储、应用版本、root 状态、当前访问模式等,用于了解用户实际运行环境。",
            JSONObject(), noReq)
        fn("shutdown_device", "电源设备操作:shutdown_device 执行关机。仅在设备具备 root 权限时生效;若无 root,调用会失败,应如实告知用户,且在无 root 时不执行此类操作。调用前应先用 query_environment 确认 root 状态。",
            JSONObject(), noReq)
        fn("reboot_device", "电源设备操作:reboot_device 执行重启。仅在设备具备 root 权限时生效;若无 root,调用会失败,应如实告知用户,且在无 root 时不执行此类操作。调用前应先用 query_environment 确认 root 状态。",
            JSONObject(), noReq)
        fn(TOOL_GET_FILE, "取出工作区内的一个文件并发送给用户(不要再以纯文本复制文件内容)。path 为工作区内相对文件路径。默认按文件类型自动呈现:图片(.jpg/.png/.gif/.webp 等)显示为图片消息气泡,用户可直接查看并点开大图;视频显示为视频消息;其它文件显示为可下载/可打开的文件卡片。若用户明确要求「以文件形式发我」「发文件」「发压缩包」或「不要图片气泡,给我文件卡片」,须传 as_file=true,此时即使是图片也以文件卡片形式发送。",
            JSONObject().apply {
                put("path", pathP("工作区内相对文件路径"))
                put("as_file", pathP("true=强制以文件卡片发送(即使内容是图片);默认 false,按类型自动呈现"))
            }, pathReq)

        fn("update_plan_step", "在「构建」后按方案逐步执行时,每开始或完成一个流程步骤即调用本工具,将该步骤的实时状态回写至聊天的「方案卡片」(卡片实时打勾,已完成步骤保留显示)。用户要求「继续做完未完成的步骤」时,重新逐条调用即可刷新进度,不得因已交付过而拒绝调用。标记纪律:同一时刻至多一个步骤为 doing(开始下一步前先将上一步标为 done);每步仅标记一次 doing 与一次 done,不得反复来回;已 done 的步骤不得改回 doing(需重做先标为 pending,系统会忽略 done→doing 回退,避免卡片出现两个「正在执行」)。严格按序号顺序推进(1→2→3…),禁止跳步:标记第 N 步前,第 1..N-1 步必须全为 done;若发现前序步骤漏标 done(如第 6 步已 done 而第 5 步仍 doing),先补该步 done 再继续。step_index 为步骤序号(从 1 开始,对应卡片「1. 步骤」编号);status 为该步状态:doing=正在执行 / done=已完成 / pending=未开始。每步开始执行时调用一次 status=doing,执行成功后调用一次 status=done。",
            JSONObject().apply {
                put("step_index", pathP("步骤序号(从 1 开始,对应方案卡片里「1. 步骤」的编号)"))
                put("status", pathP("该步骤当前状态:doing=正在执行 / done=已完成 / pending=未开始"))
            }, arrayOf("step_index", "status"))

        fn(TOOL_GENERATE_MEDIA, "根据用户描述生成图片或视频,并将结果作为图片/视频消息直接发送给用户。调用时机:用户明确想要生成/绘制/制作一张图片或一段视频(如 \"画一只猫\"\"生成一张海报\"\"做一个星空视频\"),或要求基于某张已有图片进行编辑/风格化/合成(图生图,如 \"把这张图改成赛博朋克风格\"\"参考这两张图做一张海报\")。prompt 参数默认直接使用用户原始表述;仅在以下情况才需先将表述优化为更严谨的提示词再传入:(1) 用户明确要求\"优化提示词/润色描述/帮我写得更好\";(2) 用户描述过于简略、缺少主体/风格/细节等关键信息,直接生成效果较差;(3) 用户明确表达想要某种效果但描述不够严谨。除此之外一律将用户原话原样作为 prompt 传入,不得擅自改写。图生图/多图合成时:在 image 参数传入参考图 URL(单张传字符串,多张传数组,最多 4 张),prompt 写清对每张图做什么(官方推荐结构:[改变要求]+[新风格/场景]+[需添加或移除的元素]+[需保留的元素])。",
            JSONObject().apply {
                put("type", pathP("生成类型:image=图片 / video=视频"))
                put("prompt", pathP("生成内容的提示词(默认直接用用户原话;按上述规则需要优化时才传入优化后的提示词)"))
                put("size", pathP("图片尺寸(可选,仅 image 使用)。两种写法:①档位式 1K/2K/3K/4K,配合 ratio 得到标准分辨率(推荐;如 2K+16:9=2624x1472,1K+1:1=1024x1024);②精确尺寸如 1024x1024 / 768x1024 / 1024x768(不支持的尺寸可能被标准化)。用户未指定尺寸时:头像/方形图默认 1K,横幅/封面/壁纸类默认 2K。"))
                put("ratio", pathP("宽高比(可选,仅 image 使用,与档位式 size 配合):1:1 / 3:4 / 4:3 / 16:9 / 9:16 / 2:3 / 3:2 / 21:9。用户提到\"横版/竖版/16:9/手机壁纸/宽幅\"等比例要求,或你判断内容适合某种比例时传入;不传默认 1:1。"))
                put("image", pathP("参考图(可选,仅 image 的图生图/多图合成使用):传入用户提供的图片完整 URL——通常是对话中用户发送的图片消息的 URL。单张传字符串,多张(多图合成)传字符串数组,最多 4 张。仅在用户明确要求基于某张图片生成/编辑/合成时使用;纯文生图不要传。"))
                put("keep", pathP("是否额外将生成结果保存到 AI 工作区(可选,true/false,默认 false)。true 时图片/视频落盘至工作区 ai_files 并返回相对路径,后续可用 list_files/get_file/edit_file 等工具继续处理(如裁剪、转格式、再分析)。仅当用户明确要保留,或你下一步要操作该文件时传 true。"))
            }, arrayOf("type", "prompt"))
        fn(TOOL_EXECUTE_PYTHON, "在 AI 的 Python 运行环境中执行一段 Python 代码。优先使用用户已安装的应用内置运行环境(完整 Linux + CPython,无需 Termux,可直接 pip 安装第三方库);未安装则自动回退至设备自带 Python(如 Termux),二者皆无才返回失败(此时引导用户到「AI 使用 → 运行环境」导入内置环境,而非安装 Termux)。工作目录为 AI 工作区,代码中相对路径的读写直接落在工作区 ai_files 内,可用文件类工具继续处理。超时默认 30 秒(可用 timeout 调整,5-300)。⚠️ 本工具**与访问模式无关**:root 模式/完全访问/手动审批三种模式下的执行能力完全一致,内置沙盒是隔离的 Linux 环境(设备文件系统未映射进去),里面用 socket 监听端口、用 subprocess 调外部命令、读写文件都允许。**严禁**以「当前不是 root 模式」为由拒绝执行或宣称无法运行;若执行失败,只能转述真实报错(缺依赖/超时/退出码/stderr)。",
            JSONObject().apply {
                put("code", pathP("要执行的 Python 源代码(完整代码,可多行)"))
                put("timeout", pathP("超时秒数(可选,默认 30,最多 300)"))
            }, arrayOf("code"))
        fn(TOOL_RUN_SCRIPT, "运行工作区里的脚本,按文件扩展名自动选择已导入的运行环境组件(不限于 Python:装了 Kotlin 就能跑 .kts/.kt,装了 Node 就能跑 .js,以此类推)。与 execute_python 的区别:execute_python 只能跑 Python;run_script 是通用入口,**支持哪些语言完全取决于用户导入了哪些环境包**。两种用法:①传 file —— 跑工作区里已存在的脚本(相对工作区路径);②传 code + lang —— 把代码写入临时脚本再跑(lang 填扩展名,如 kts/kt/js/py)。运行目录为脚本所在目录,绑定为沙盒内的 /workspace。找不到能处理该扩展名的运行组件时会明确告知该扩展名没有可用运行环境(此时可建议用户在「运行环境」导入对应语言包,或改用 execute_python)。超时默认取组件声明值(通常 60~300 秒)。",
            JSONObject().apply {
                put("file", pathP("要运行的工作区脚本路径(与 code 二选一),如 demo.kts / src/app.kt"))
                put("code", pathP("要运行的代码正文(与 file 二选一);需同时提供 lang"))
                put("lang", pathP("代码语言/扩展名(仅在传 code 时使用),如 kts、kt、js、py"))
                put("timeout", pathP("超时秒数(可选,默认用组件声明的值)"))
            }, emptyArray<String>())

        // ===== 专业开发者工具(仅开发场景使用)=====
        fn(TOOL_FETCH_URL, "抓取网页:GET 给定 URL,剥离脚本/样式/标签噪声后提取正文,返回可读纯文本(必要时转换为 Markdown 标题/列表),并将完整结果落盘至工作区(文件名自动取自 URL + 时间戳)。适用于:阅读文档/文章、抓取 API 返回的 HTML 页面、获取公开网页内容用于分析或二次处理。仅用于用户明确要抓取的公开 URL;不得用于隐私/登录态页面。",
            JSONObject().apply {
                put("url", pathP("要抓取的网页地址(http/https 完整 URL)"))
                put("timeout", pathP("超时秒数(可选,默认 20,最多 60)"))
                put("keep_markdown", pathP("是否保留 Markdown 结构(可选,true/false;默认 true)。false 则只返回纯文本正文"))
            }, arrayOf("url"))
        fn(TOOL_SHELL_EXEC, "执行 Shell 命令,返回 stdout/stderr/退出码。**默认跑在应用内置的 Linux 环境里**(Debian aarch64 完整发行版,bash / coreutils / grep / sed / awk / apt 等 GNU 工具齐备,apt 源已配好,进入后内部身份即 root,无需设备 root),工作目录是 AI 工作区(guest 内映射为 /workspace),相对路径读写直接落在工作区 —— 这是完整版,应作为首选。如需某工具缺失(如 git/jadx/apktool),在沙盒内执行 `ensure_tool <工具名>` 即可按需安装(首次会自动 apt 补齐基础工具),不要因「命令不存在」就停下。⚠️ 只要用户装了内置 Linux 环境,本工具就自动走它,**严禁**以「没有设备 root」为由拒绝执行或改用其他残缺手段;执行失败只能转述真实报错。仅当需要操作 **Android 系统层**(pm / am / settings / getprop / dumpsys / svc 等设备专有命令,或读写 /sdcard 等设备真实路径)时,才把 device 设为 true 改走设备 shell(Android 自带 toybox,工具较少);设备 shell 在非 root 下会拦截破坏性/提权类命令(rm/mv/dd/mount/chmod、sh/su/sudo、管道喂解释器),那拦的只是「碰 Android 系统」这一件事,**与运行能力无关**。超时默认 30 秒(可用 timeout 调整,5-120)。不得用于未经用户确认的破坏性操作。",
            JSONObject().apply {
                put("command", pathP("要执行的 shell 命令行(如 \"ls -la\" / \"uname -a\" / \"apt list --installed\")"))
                put("timeout", pathP("超时秒数(可选,默认 30,最多 120)"))
                put("device", boolP("是否强制走设备 shell(Android 系统层)。仅当要执行 pm/am/settings/getprop/dumpsys 等设备专有命令、或读写 /sdcard 等设备真实路径时设为 true。默认 false = 跑内置 Linux 环境(完整版,推荐)。"))
            }, arrayOf("command"))
        fn(TOOL_RUN_ADB, "执行 ADB 命令(开发者调试工具):通过 adb 运行一条 Android 调试桥命令(如 shell/install/push/pull/devices/getprop 等)。适用于:协助开发者在电脑端调试设备、安装 APK、拉取文件、查询设备属性。依赖设备已开启调试且 adb 可执行;执行目录为应用工作区 ai_files。超时 60 秒。",
            JSONObject().apply {
                put("args", pathP("adb 的参数部分(不含 adb 前缀),如 \"devices\" / \"shell pm list packages\" / \"-s install xxx.apk\""))
                put("timeout", pathP("超时秒数(可选,默认 60,最多 180)"))
            }, arrayOf("args"))
        // ===== 专业开发者工具(第二批)=====
        fn(TOOL_HTTP_REQUEST, "发起任意 HTTP 请求(开发者工具):method 支持 get/post/put/patch/delete/head,可带 headers 与 body(JSON 字符串),返回状态码、响应头与响应体(正文最多返回 8000 字符,超出截断;二进制响应会提示长度并建议用 fetch_webpage/get_file 等)。适用于:调试自有 API、调用 REST 接口、查看返回结构。不得用于访问用户隐私/登录态页面或发起恶意请求。",
            JSONObject().apply {
                put("url", pathP("目标地址(http/https 完整 URL)"))
                put("method", pathP("请求方法(默认 GET),可选 GET/POST/PUT/PATCH/DELETE/HEAD"))
                put("headers", pathP("请求头(可选),JSON 对象字符串,如 {\"Authorization\":\"Bearer x\"}"))
                put("body", pathP("请求体(可选),通常是 JSON 字符串;GET/HEAD 一般留空"))
                put("timeout", pathP("超时秒数(可选,默认 20,最多 60)"))
            }, arrayOf("url"))
        fn(TOOL_PROCESS_LIST, "列出当前设备上正在运行的进程(开发者调试工具):返回 pid / 进程名 / 用户名(尽力)。root 模式信息更全;非 root 模式仅能看到当前应用自身及部分可见进程。适用于:排查卡顿、确认某进程是否在运行、定位要 kill 的 pid。",
            JSONObject().apply {
                put("limit", pathP("最多返回条数(可选,默认 50,最多 200)"))
            }, arrayOf("limit"))
        fn(TOOL_PING, "Ping 一个主机以判断网络连通性(开发者工具):优先 ICMP ping,主机不可 ICMP 时回退至 TCP 端口探测。返回是否可达、往返耗时(多次取平均)、丢包情况。适用于:确认服务器/设备是否在线、诊断网络。",
            JSONObject().apply {
                put("host", pathP("要探测的主机名或 IP(如 8.8.8.8 / example.com)"))
                put("count", pathP("发送次数(可选,默认 4,最多 10)"))
                put("port", pathP("TCP 探测端口(可选,仅当需要探测某端口连通性时填,如 443)"))
            }, arrayOf("host"))
        fn(TOOL_SEARCH_WEB, "联网搜索(开发者调研工具):双引擎回退,首选 Bing RSS(国内直连可达),失败后自动回退 DuckDuckGo。返回若干条结果(标题 + 链接 + 摘要),并附带抓取到的首个页面正文片段。适用于:查文档、查报错、调研技术方案。仅用于公开信息检索。若两源均失败,通常仅为境外搜索源被网络环境阻断,不代表设备无法联网,可改用 fetch_webpage 抓取具体网址。",
            JSONObject().apply {
                put("query", pathP("搜索关键词(如 \"kotlin okhttp timeout example\")"))
                put("max", pathP("返回结果条数(可选,默认 5,最多 10)"))
            }, arrayOf("query"))
        fn(TOOL_DEEP_RESEARCH, "深度研究(一次调用完成多源调研):针对一个研究课题,自动从多个角度并发联网检索、跨源去重,并抓取若干高相关页面的正文,聚合成结构化材料返回(每条含标题/链接/摘要/正文片段)。适用于:需要多角度交叉验证的调研、技术选型、事实核查、写报告前的资料收集——比反复单次 search_web 更快更全、覆盖更广。拿到材料后应交叉比对、给出结论并附来源链接;不同来源冲突时如实指出分歧,检索不到可靠信息时如实说明,不得编造。",
            JSONObject().apply {
                put("query", pathP("研究课题(一句话,如 \"Rust 与 Go 在后端并发场景的优劣\")"))
                put("angles", pathP("可选:自定义检索角度(JSON 字符串数组,如 [\"Rust 并发模型\",\"Go goroutine 调度\"]);不传则系统自动拆解多角度"))
                put("max_sources", pathP("可选:抓取正文的来源数量,默认 6,范围 3-12"))
            }, arrayOf("query"))
        fn(TOOL_GIT, "执行 Git 命令(开发者工具):在工作区 ai_files 目录(或其子目录,若已 git init)下运行一条 git 命令,如 status / log / diff / add / commit / clone / pull / push / branch。适用于:让 AI 用 git 管理工作区里的代码与文档版本。注意 push/pull 需要远端与凭据已配置。",
            JSONObject().apply {
                put("args", pathP("git 参数(不含 git 前缀),如 \"status\" / \"log --oneline -5\" / \"add -A\" / \"commit -m 更新\" / \"clone <url> repo\""))
                put("timeout", pathP("超时秒数(可选,默认 60,最多 180)"))
            }, arrayOf("args"))
        fn(TOOL_DIFF, "对比两段文本的差异(开发者工具):给定 a 与 b 两段文本,返回逐行差异(统一 diff 格式),用于展示「修改前 vs 修改后」的区别,或核对两版代码/配置的不同。",
            JSONObject().apply {
                put("a", pathP("原始文本(修改前)"))
                put("b", pathP("对比文本(修改后)"))
                put("context", pathP("上下文行数(可选,默认 3)"))
            }, arrayOf("a", "b"))
        fn(TOOL_DOWNLOAD_FILE, "下载一个网页文件(任意类型,含 APK 安装包)到本地。适用于:用户想获取某个链接指向的文件/安装包并保存到工作区或指定目录。url 为文件直链;未指定 dest 时落到工作区 ai_files(根模式可写绝对路径);dest 为相对工作区的路径或文件名(如 app.apk / downloads/x.apk),已存在则覆盖。可选项:headers(自定义请求头 JSON)、timeout(秒,默认 60,最多 300)、overwrite(是否覆盖,默认 true)。返回保存到的绝对路径与大小。",
            JSONObject().apply {
                put("url", pathP("要下载的文件直链(如 https://example.com/app.apk)"))
                put("dest", pathP("保存位置(可选):相对工作区 ai_files 的路径或文件名;留空则存到工作区根并使用 url 末段文件名"))
                put("headers", pathP("请求头(可选),JSON 对象字符串,如 {\"Authorization\":\"Bearer x\"}"))
                put("timeout", pathP("超时秒数(可选,默认 60,最多 300)"))
                put("overwrite", pathP("已存在时是否覆盖(可选,默认 true)"))
            }, arrayOf("url"))
        fn(TOOL_INSTALL_APK, "安装一个 APK 安装包:拉起系统安装器让用户确认安装。适用于:已下载好 APK,或用户给出了本地 APK 路径,需要真正装到设备上。path 支持两种写法:(1) AI 工作区相对路径(如 app.apk / downloads/x.apk);(2) 设备真实绝对路径(如 /sdcard/Download/app.apk、/data/.../xxx.apk),root 模式与沙盒模式下均支持绝对路径。该方法只负责拉起安装界面,安装成功与否由用户在系统安装器中决定;返回是否成功拉起安装器。",
            JSONObject().apply {
                put("path", pathP("APK 路径:工作区相对路径(如 app.apk)或设备绝对路径(如 /sdcard/Download/app.apk)"))
            }, arrayOf("path"))
        // ===== 定位与相机 =====
        fn(TOOL_GET_CURRENT_LOCATION,
            "获取用户当前的地理位置(经纬度与文字地址)。" +
            "适用于:用户说「我现在在哪」「获取我的位置」「我现在的位置」「我这附近是什么地方」等。" +
            "如果还没有授予定位权限,本工具会通过 App 自动弹出系统授权请求,并等待用户在弹窗里做出选择(默认最多等 60 秒);" +
            "用户同意后立即继续取位置,不需要用户再重复一遍。" +
            "可选「等待秒数(timeout_sec)」调整等待授权的时间(5~180 秒,默认 60);" +
            "可选「打开定位设置(open_settings)」填「是」时,若取不到位置会自动打开系统定位设置页,方便用户开启 GPS 或定位服务。" +
            "返回:纬度、经度、地址、精度(米)、定位来源、定位时间。",
            JSONObject().apply {
                put("timeout_sec", pathP("等待授权的秒数(可选):5~180,默认 60"))
                put("open_settings", pathP("取不到位置时是否打开系统定位设置页(可选):是 / 否,默认否"))
            }, noReq)
        fn(TOOL_OPEN_FRONT_CAMERA,
            "打开前置摄像头(自拍)。适用于:用户说「打开前置摄像头」「打开自拍」「用前置摄像头拍一张」等。" +
            "若未授予相机权限,本工具会自动弹出系统授权并等待用户同意,然后才打开相机。" +
            "若本机没有前置摄像头,会直接返回可用摄像头列表,不会失败。\n" +
            "可选「等待秒数(wait_sec)」:填 0(默认)表示只负责把相机界面打开," +
            "用户拍完后照片会存到工作区 camera_captures 目录(可用 list_files 查看);" +
            "填 60 等数值则会等待用户拍照,拍完直接把照片的绝对路径返回。",
            JSONObject().apply {
                put("wait_sec", pathP("等待拍照的秒数(可选):0~300,默认 0(只打开界面不等待)"))
                put("close", pathP("是否只负责关闭已打开的相机界面(可选):是/否,默认否。填「是」时不再打开相机,直接收起界面"))
            }, noReq)
        fn(TOOL_OPEN_REAR_CAMERA,
            "打开后置摄像头。适用于:用户说「打开后置摄像头」「打开相机」「用后置摄像头拍一张」「拍张照」等。" +
            "若未授予相机权限,本工具会自动弹出系统授权并等待用户同意,然后才打开相机。" +
            "若本机没有后置摄像头,会直接返回可用摄像头列表,不会失败。\n" +
            "可选「等待秒数(wait_sec)」:填 0(默认)表示只负责把相机界面打开," +
            "用户拍完后照片会存到工作区 camera_captures 目录(可用 list_files 查看);" +
            "填 60 等数值则会等待用户拍照,拍完直接把照片的绝对路径返回。",
            JSONObject().apply {
                put("wait_sec", pathP("等待拍照的秒数(可选):0~300,默认 0(只打开界面不等待)"))
                put("close", pathP("是否只负责关闭已打开的相机界面(可选):是/否,默认否。填「是」时不再打开相机,直接收起界面"))
            }, noReq)
        // ===== 设备控制与系统开关 =====
        fn(TOOL_SET_VOLUME,
            "调节设备音量。参数:音量(level,0-100,必填);" +
            "音频流(stream,可选):媒体 / 铃声 / 闹钟 / 通话 / 通知 / 系统,默认媒体;" +
            "显示系统音量条(show_ui,可选):是/否,默认否。返回当前音量与最大音量。",
            JSONObject().apply {
                put("level", pathP("目标音量,0-100"))
                put("stream", pathP("音频流(可选):媒体 / 铃声 / 闹钟 / 通话 / 通知 / 系统,默认媒体"))
                put("show_ui", pathP("是否弹出系统音量条(可选):是/否,默认否"))
            }, arrayOf("level"))
        fn(TOOL_PLAY_AUDIO,
            "播放工作区内的音频文件。参数:路径(path,必填);" +
            "音量(volume,可选,0-100,默认 80);循环(loop,可选):是/否;" +
            "播放秒数(duration_sec,可选):到点自动停止,0 表示播到结束。" +
            "支持 mp3/wav/m4a/aac/ogg/flac/amr/mid。返回播放文件与总时长。",
            JSONObject().apply {
                put("path", pathP("工作区内音频文件路径,如 music/a.mp3"))
                put("volume", pathP("播放音量(可选):0-100,默认 80"))
                put("loop", pathP("是否循环播放(可选):是/否,默认否"))
                put("duration_sec", pathP("播放秒数(可选):到点自动停止,0 表示播到结束"))
            }, pathReq)
        fn(TOOL_PLAY_VIDEO,
            "为用户直接弹出应用内全屏视频播放器播放指定视频(用户不用自己去找、不用手点)。" +
            "当用户说「播放这个视频」「打开视频」「我想看那个视频」「帮我放一下刚才的视频」等," +
            "或你已生成/取出一个视频想直接放给用户看时,调用本工具。" +
            "参数:路径(path,必填)——填视频文件的工作区相对路径(如 video/demo.mp4);也支持直接填 http/https 在线视频地址。" +
            "文件在本机工作区时会直接读本地文件播放,不走网络、不下载。" +
            "支持 mp4/mov/avi/mkv/webm/3gp/mpg/mpeg 等。返回播放的文件名与大小。",
            JSONObject().apply {
                put("path", pathP("视频文件的工作区相对路径(如 video/demo.mp4),或 http/https 在线视频地址"))
            }, pathReq)
        fn(TOOL_VIBRATE,
            "控制振动马达,支持自定义节奏与强度。参数:节奏(pattern,可选):毫秒序列,如 300,150,300,150 " +
            "表示振动 300ms、停顿 150ms 交替,可继续追加;省略时改用 duration_ms 做连续振动;" +
            "重复次数(repeat,可选,默认 1);强度(amplitude,可选,1-255,默认 255)。振动总时长上限 60 秒。",
            JSONObject().apply {
                put("pattern", pathP("振动节奏(可选):毫秒序列,逗号分隔,如 300,150,300,150"))
                put("repeat", pathP("节奏重复次数(可选):默认 1"))
                put("duration_ms", pathP("未提供节奏时的连续振动毫秒数(可选),默认 300"))
                put("amplitude", pathP("振动强度(可选):1-255,默认 255"))
            }, noReq)
        fn(TOOL_SET_BRIGHTNESS,
            "调节当前界面屏幕亮度,支持按节奏闪烁。参数:亮度(brightness,0-100,必填);" +
            "节奏(rhythm,可选):格式「亮毫秒,暗毫秒,次数」,如 600,600,3 表示亮 0.6 秒、暗 0.6 秒、共 3 次。",
            JSONObject().apply {
                put("brightness", pathP("目标亮度,0-100"))
                put("rhythm", pathP("闪烁节奏(可选):亮毫秒,暗毫秒,次数,如 600,600,3"))
            }, arrayOf("brightness"))
        fn(TOOL_KEEP_SCREEN_ON,
            "控制屏幕常亮,支持定时恢复与按节奏开关。参数:开启(enabled,是/否,必填);" +
            "持续时间(duration_ms,可选):到点自动恢复自动息屏,0 表示一直保持;" +
            "节奏(rhythm,可选):格式「常亮毫秒,熄灭毫秒,次数」。",
            JSONObject().apply {
                put("enabled", pathP("是否开启屏幕常亮:是/否"))
                put("duration_ms", pathP("保持毫秒数后自动恢复(可选):0 表示一直保持"))
                put("rhythm", pathP("节奏(可选):常亮毫秒,熄灭毫秒,次数"))
            }, arrayOf("enabled"))
        fn(TOOL_SET_SCREEN_ORIENTATION,
            "设置屏幕方向,支持按节奏轮换。参数:方向(mode):竖屏 / 横屏 / 自动 / 跟随传感器;" +
            "节奏(rhythm,可选):格式「竖屏毫秒,横屏毫秒,次数」。" +
            "若当前界面被应用清单锁定为竖屏则无法旋转,工具会如实报告。",
            JSONObject().apply {
                put("mode", pathP("屏幕方向:竖屏 / 横屏 / 自动 / 跟随传感器"))
                put("rhythm", pathP("轮换节奏(可选):竖屏毫秒,横屏毫秒,次数"))
            }, arrayOf("mode"))
        fn(TOOL_SET_BLUETOOTH,
            "管理蓝牙开关。参数:开启(on,是/否,必填)。" +
            "Android 12 起系统禁止第三方应用直接开关蓝牙,工具会先尝试直接切换,失败则打开系统蓝牙设置页,并返回当前状态。",
            JSONObject().apply {
                put("on", pathP("是否开启蓝牙:是/否"))
            }, arrayOf("on"))
        fn(TOOL_SET_GPS,
            "管理 GPS / 系统定位服务。参数:开启(on,是/否,必填)。" +
            "系统定位开关禁止应用直接改写,工具会打开系统定位设置页并返回当前定位服务状态。",
            JSONObject().apply {
                put("on", pathP("是否开启定位服务:是/否"))
            }, arrayOf("on"))
        fn(TOOL_SET_WIFI,
            "管理 Wi-Fi 开关。参数:开启(on,是/否,必填)。" +
            "Android 10 起系统禁止应用直接开关 Wi-Fi,工具会打开系统 Wi-Fi 面板,并返回当前 Wi-Fi 状态。",
            JSONObject().apply {
                put("on", pathP("是否开启 Wi-Fi:是/否"))
            }, arrayOf("on"))
        fn(TOOL_SET_NFC,
            "管理 NFC 开关。参数:开启(on,是/否,必填)。" +
            "NFC 开关禁止应用直接改写,工具会打开系统 NFC 设置页,并返回当前 NFC 状态与设备是否支持。",
            JSONObject().apply {
                put("on", pathP("是否开启 NFC:是/否"))
            }, arrayOf("on"))
        fn(TOOL_SET_FLASHLIGHT,
            "控制闪光灯(手电筒)开关。参数:开启(on,是/否,必填)。返回是否切换成功。",
            JSONObject().apply {
                put("on", pathP("是否开启手电筒:是/否"))
            }, arrayOf("on"))
        fn(TOOL_BATTERY,
            "查询电池状态并管理电池优化。参数:忽略电池优化(exempt,可选):填「是」会打开系统「忽略电池优化」确认页;" +
            "打开电池设置(open_settings,可选):是/否。" +
            "返回电量百分比、是否充电、充电方式、温度、健康状态、电压、电池技术与是否已忽略电池优化。",
            JSONObject().apply {
                put("exempt", pathP("是否申请忽略电池优化(可选):是/否"))
                put("open_settings", pathP("是否打开系统电池设置页(可选):是/否"))
            }, noReq)
        // ===== 闹钟 / 定时提醒 =====
        fn(TOOL_SET_ALARM,
            "为用户设置系统闹钟/定时提醒,真正落到系统 AlarmManager——到点会响闹钟铃声 + 震动 + 高优先级通知," +
            "App 被划掉、手机重启后依然有效。三种用法任选其一:\n" +
            "① 绝对时刻:time=\"07:30\"(24 小时制);今天该时刻已过则自动顺延到明天。\n" +
            "② 相对时间:in_minutes=10 表示 10 分钟后响(用于「十分钟后提醒我」)。\n" +
            "③ 周期闹钟:配 days=\"1,2,3,4,5\"(1=周一…7=周日)、daily=true(每天)、weekdays=true(工作日)。\n" +
            "label 填闹钟名称,会显示在通知里(如 开会 / 吃药 / 起床),省略则为「闹钟」。\n" +
            "前置条件:必须已授予「闹钟和提醒」精确闹钟权限和通知权限。没授权时本工具会直接返回失败(ok=false)," +
            "并自动打开系统授权页或弹出授权弹窗;此时不要谎称已设置成功,要告诉用户去授权,授权后重新调用一次即可。\n" +
            "注意:它能跨小时/跨天真实触发,所以「10 分钟后提醒我」也用它,不要用 wait(那是阻塞等待,App 一关就失效)。",
            JSONObject().apply {
                put("time", pathP("响铃时刻,24 小时制,如 07:30(与 in_minutes 二选一)"))
                put("in_minutes", pathP("多少分钟后响(与 time 二选一),如 10"))
                put("label", pathP("闹钟名称(可选),如 开会 / 吃药 / 起床"))
                put("days", pathP("周期闹钟的星期(可选):逗号分隔,1=周一…7=周日,如 1,3,5"))
                put("daily", pathP("可选:填是/true 表示每天重复"))
                put("weekdays", pathP("可选:填是/true 表示只在工作日(周一至周五)重复"))
            }, noReq)
        fn(TOOL_CANCEL_ALARM,
            "取消已设置的闹钟。alarm_id 从 list_alarms 的返回结果里取;all=true 时取消全部闹钟。" +
            "用户说「把闹钟关了」「取消明天那个闹钟」而没给 ID 时,先 list_alarms 查到 id 再取消。",
            JSONObject().apply {
                put("alarm_id", pathP("要取消的闹钟 id(可选,与 all 二选一)"))
                put("all", pathP("可选:填是/true 取消全部闹钟"))
            }, noReq)
        fn(TOOL_LIST_ALARMS,
            "列出当前已设置的全部闹钟,返回 id、响铃时间、重复规则与名称。用于回答「我设了几个闹钟」「下一个闹钟几点」," +
            "或配合 cancel_alarm 定位要取消的闹钟 id。",
            JSONObject(), noReq)
        // ===== App 内部业务工具(AI 操作本 App 自身功能) =====
        fn("switch_tab", "切换主界面底部板块到指定页(tab):chat=聊天 / contacts=联系人 / community=社区 / profile=我的。tab 支持中文写法:聊天/消息=chat、联系人/好友=contacts、社区/广场/动态=community、我的/个人=profile。无论用户如何表述,最终都映射到这 4 个标准值之一。",
            JSONObject().apply { put("tab", pathP("目标板块:chat / contacts / community / profile,亦可用中文 聊天/联系人/社区/我的")) }, arrayOf("tab"))
        fn("send_message", "以当前用户身份给某个好友(私聊)或某个群(群聊)发送一条消息。target_type=friend 表示发给好友,target_id 为其用户 ID,走私聊;target_type=group 表示发给群,target_id 为群会话 ID(负数群 convID,官方群 -1001),走群聊。返回结果带 message_id,发送后发现内容有问题需在 2 分钟内撤回时,将其传给 recall_message。",
            JSONObject().apply {
                put("target_type", pathP("friend(私聊给好友) / group(群聊)"))
                put("target_id", pathP("目标用户 ID(私聊) 或 群会话 ID(群聊,负数)"))
                put("content", pathP("要发送的消息内容"))
            }, arrayOf("target_type", "target_id", "content"))
        fn(TOOL_SEND_FILE, "将 AI 工作区里的一个文件作为消息发送给好友(私聊)或群(群聊)——即替用户把文件/图片/视频真正发给聊天对象,对方可见。与 get_file 的区别在于:get_file 是把文件发给你自己查看,本工具是把文件发给别人。按类型自动分流:图片渲染为图片消息、视频渲染为视频消息、其它文件(APK/PDF/TXT/ZIP 等)渲染为文件卡片;需强制以文件卡片发送则传 as_file=true。参数:target_type=friend(私聊给好友)/group(群聊);target_id=好友用户 ID(正数)或群会话 ID(负数,官方群 -1001);path=要发送的文件在工作区内的相对路径(如 out/report.pdf);caption=可选,随文件一起发送的文字说明。返回结果带 message_id,需撤回时将其传给 recall_message。",
            JSONObject().apply {
                put("target_type", pathP("friend(私聊给好友) / group(群聊)"))
                put("target_id", pathP("好友用户 ID(私聊,正数) 或 群会话 ID(群聊,负数)"))
                put("path", pathP("要发送的文件在工作区内的相对路径,如 out/report.pdf / pic.png"))
                put("caption", pathP("可选:随文件一起发送的文字说明"))
                put("as_file", pathP("可选:true=即使内容是图片/视频也强制以文件卡片发送(默认按类型自动分流)"))
            }, arrayOf("target_type", "target_id", "path"))
        fn(TOOL_LIST_RECENT_MESSAGES, "查看某个会话的最近消息列表(每条含 message_id/发送者/内容/时间),主要用途:获取消息 ID。用户说「撤回我刚才那条消息」「帮我把那条撤了」但无人知晓 message_id 时,先用本工具查目标会话的最近消息,从内容中定位目标消息并获取其 message_id,再传给 recall_message;也可用于了解某会话最近聊了什么。参数:target_type=friend(私聊,查我与 target_id 好友的对话)/group(群聊,target_id 为群会话 ID 负数,如官方群 -1001);count=要查看的条数(默认 20,最大 50,从最新往前取);keyword=可选过滤词,只返回包含该词的消息。",
            JSONObject().apply {
                put("target_type", pathP("friend(私聊) / group(群聊)"))
                put("target_id", pathP("好友用户 ID(私聊) 或 群会话 ID(群聊,负数,如官方群 -1001)"))
                put("count", pathP("可选:返回条数,默认 20,最大 50"))
                put("keyword", pathP("可选:只返回内容包含该关键词的消息"))
            }, arrayOf("target_type", "target_id"))
        fn(TOOL_RECALL_MESSAGE, "撤回一条已发送的消息(私聊/群聊均可)。message_id 来源:send_message 返回结果中的 message_id、list_recent_messages 查到的消息 ID,或用户明确给出的消息 ID。用户说「撤回刚发的那条」但未给 ID 时,先用 list_recent_messages 查最近消息定位目标,不得凭空猜测 ID。权限规则(服务端强制):开发者(ID=1)可撤回任何人的消息且不限时间;普通用户只能撤回自己发的消息,且必须在发出后 2 分钟内——超过 2 分钟会撤回失败,此时如实告知用户「超过两分钟了,无法撤回」,不得重试,也不得谎报撤回成功。撤回后接收方会看到「XX 撤回了一条消息」。",
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
        fn(TOOL_BATCH_LOOP, "批量循环执行工具(提效专用):当任务需要重复调用同一个工具很多次时(如批量创建 100 个文件、给 30 个好友逐个发消息、批量重命名/复制一批文件),不得逐次手动调用——用本工具一次调用完成整个循环,仅占 1 个工具轮次。参数:tool=要重复调用的工具名;args=该工具的参数模板(JSON 对象,任意字符串值中可写 {i} 占位符,每次执行时替换为当前循环序号,支持 {i:03d} 补零写法);count=重复次数(1~5000,超过上万次的超大批量请改用 shell_exec 写 for 循环);start=起始序号(可选,默认 1);interval_ms=两次执行间隔毫秒(可选,默认 0,最高 10000;发消息类建议 200 以上以避免刷屏);stop_on_error=首次失败即停(可选,默认 false;连续失败 5 次会自动中止以避免任务被拖死)。返回逐条结果明细与成功/失败统计。",
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
        fn(TOOL_CAPTURE_SCREEN, "捕捉当前屏幕内容并交付给用户(推荐用于「把屏幕截下来发我」「看看现在这个页面」「截个图给我」这类需求)。deliver 决定交付形式:deliver=image(默认)——将截到的画面作为图片消息直接发给用户,用户立刻能在聊天里看到并点开大图(与生成图片完全同一条渲染链路);deliver=file——作为文件卡片发给用户(适合画面很长、想看原图细节或需要下载原文件)。keep=true 时额外在工作区留一份副本供后续 list_files / get_file 处理(默认 false,不需要留档)。截屏走无障碍服务(Android 11+ 免 root),失败时自动尝试 screencap(root/adb)。name 可选文件名(不含扩展名);quality 为 JPEG 画质(1-100,默认 85,仅影响发图体积)。注意:支付/密码等敏感页面系统会阻止截屏,此时会返回失败,应如实告知用户。",
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
        fn(TOOL_UNINSTALL_APP, "卸载手机上的应用——可为任意应用,不限于本应用。注意这不是退出,而是把该应用从手机上删除,其数据全部消失且不可恢复。必须用 target 明确指定要卸载谁:应用名(如 微信、抖音、Chrome)或包名(如 com.tencent.mm);仅当用户明确要卸载 Aurora Chat 本身时才传 target=自己。target 缺失会直接报错,绝不会默认卸载本应用——因此「卸载微信」这类请求,必须把 target 填成「微信」,不得留空。两种方式:mode=system(默认)——唤起系统卸载器,弹出「要卸载此应用吗?」由用户自己点确定,最稳妥、兼容所有机型,用户未特别说明时一律用它;mode=auto——全程无障碍自动完成,用户完全不用动手(第三方应用走「系统卸载器 + 自动点确定」,本应用走「回桌面→长按图标→卸载→确定」),仅当用户明确说「你自己删掉,别让我动手」「用无障碍全自动卸载」时才使用。注意:mode=auto 需要无障碍服务已开启且已连接;无障碍手势对系统卸载确认框生效,若自动点击后校验发现目标包仍未消失,工具会明确返回失败原因,必须如实转述失败原因(如无障碍未连接、用户取消了确认框),不得谎称已卸载成功。调用前必须先向用户确认一次(除非用户刚已明确下达卸载指令),并提醒卸载会清除该应用的全部数据。若不确定目标应用的准确名称或包名,先问清楚,不得凭猜测填 target。",
            JSONObject().apply {
                put("target", pathP("要卸载的应用:应用名(如 微信)或包名(如 com.tencent.mm);卸载本应用本身请传「自己」"))
                put("mode", pathP("system(默认,唤起系统卸载器让用户自己确认) / auto(无障碍全程自动,用户不介入)"))
                put("force", pathP("可选:true 时允许静默卸载系统预装应用,默认 false(危险,仅当用户明确要求卸载系统应用时才用)"))
            }, arrayOf("target"))
        // ===== 多 AI 模式:规划者 + 并行派发 + 独立验收者(仅多 AI 模式声明,普通模式不可见) =====
        if (includeMultiAI) {
        fn(TOOL_AGENT_PLAN, "【多 AI 模式·规划者】把一个较复杂或含多步操作的任务交给独立规划 AI 拆解,返回结构化执行计划(步骤清单 + 风险点)。适用:3 步及以上的任务、要创建/修改多个文件的任务、你不确定从何下手的任务。调用后按计划逐步执行,仍需在完成后调用 agent_review 验收。简单的单步任务(如新建 1 个文件、纯问答)无需规划,直接干。",
            JSONObject().apply {
                put("task", pathP("用户的原始任务描述(原话或忠实转述)"))
            }, arrayOf("task"))
        fn(TOOL_AGENT_FORK, "【多 AI 模式·并行派发】把互不依赖的子任务并行派发给多个独立执行 AI 同时干(多协程并发,最多 4 个)。何时派发:(1) 任务可自然拆成互不依赖的几部分且并行明显更快(如「同时建 3 个不同的文件」);(2) 用户明令要求并行/同时/一起干。串行依赖的任务(后一步要用前一步的结果)禁止派发。tasks 每项必须写清:该子任务做什么 + 只允许操作哪些路径(各子任务的路径范围不得重叠,防止并行中互相覆盖)。全部完成后返回各子任务结果汇总,个别失败要自行补做或如实告知;之后你仍必须调用 agent_review 验收整体成果。",
            JSONObject().apply {
                put("tasks", JSONObject()
                    .put("type", "array")
                    .put("description", "子任务描述数组,每项格式:「做什么 + 只允许操作的路径范围」")
                    .put("items", JSONObject().put("type", "string")))
            }, arrayOf("tasks"))
        fn(TOOL_AGENT_REVIEW, "启动一个【完全独立上下文】的验收 AI,对你的工作成果做独立核验(多 AI 模式专用)。验收者只拥有只读权限,会亲自读取文件/检索来源核验,绝不轻信你的汇报。参数:task=用户的原始任务描述;summary=你的工作汇报(做了什么、改了哪些文件、关键操作与结果)。返回 JSON:verdict=PASS(通过)/FAIL(不通过),FAIL 时 detail 里是逐条可执行的修复清单。收到 FAIL 必须修复后再次调用本工具复验(同一任务最多复验 2 次);收到 PASS 后才能给用户交付最终总结。",
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
        fn(TOOL_PLUGIN_SUBMIT, "提交插件到插件市场:把一个「声明式 HTTP 工具插件」提交给开发者审核,审核通过后自动上架。本工具无条件可用,不需要任何执行模式。参数:name=插件名(40 字内,不能与已有待审/已上架插件重名);description=一句描述(300 字内,写清这个工具能干什么,审核的人要看);pluginJson=完整的插件 JSON 字符串,字段:type=\"http_tool\"、method(GET/POST)、url(URL 模板,参数用 {参数名} 占位)、responsePath(可选,从响应 JSON 取值的点分路径,如 data.result)、params(参数数组,每项含 name[字母开头的字母/数字/下划线]、label[显示名]、required、default[可选])。提交前应先用 http_request 真实调试过接口,确认能取到数据。",
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
                put("text", pathP("可选:小窗文案,应主动将其写为你当前正在做的事(如「正在操控手机」),建议不超过 8 字;省略则显示「AI 运行中」"))
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
        // 覆盖率审计（进程内一次）：schema 里有、但既没目标规则也不在「确实无目标」白名单的工具，
        // 说明新增工具忘了配展示信息。用既有日志系统留痕，开发期即可发现，避免上线后「又一个工具没显示具体文件」。
        if (toolTargetAudited.compareAndSet(false, true)) {
            val names = (0 until tools.length()).mapNotNull { i ->
                runCatching { tools.getJSONObject(i).getJSONObject("function").getString("name") }.getOrNull()
            }
            val gaps = toolTargetCoverageGaps(names)
            if (gaps.isNotEmpty()) {
                com.aurora.chat.ErrorReporter.debug(
                    "TOOL_TARGET_AUDIT",
                    "以下工具没有具体目标映射（请补 ToolActionTargets.TARGET_RULES 或加入 NO_TARGET_TOOLS）：${gaps.joinToString("、")}"
                )
            }
        }
        if (!minimal) return tools
        // 极简 schema:只保留核心+聊天扩展+模式切换,其余低频/设备/主机/网络诊断工具不声明
        // (大幅压缩普通对话的上下文;模型看不到就不会调用,减少误触设备/主机类工具的几率)
        // ⚠️ update_plan_step 必须**始终**保留：方案执行阶段全靠它把步骤状态回写进方案卡片。
        // 它此前既不在 CORE_TOOL_NAMES 也不在 CHAT_EXT_TOOL_NAMES → 普通/单 AI 模式下(即 minimal=true)
        // 根本没声明给模型 → 模型**调不了**，方案卡片永远不会出现「进行中/已完成」
        // （用户看到的就是"AI 从不把步骤标成进行中"）。schema 很小(2 个参数)，始终注入代价可忽略。
        val keep = CORE_TOOL_NAMES + CHAT_EXT_TOOL_NAMES + "set_agent_mode" + "update_plan_step"
        val kept = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            val name = t.getJSONObject("function").getString("name")
            if (name in keep) kept.put(t)
        }
        // 意图级裁剪：LEAN 档（纯对话/写代码）再剔除设备/媒体/相机/定位等死重工具，降低每轮固定输入。
        // CRAFT/DEVICE_MEDIA 档保持 minimal 全集不变。
        if (intentTier == ToolIntentTier.LEAN) {
            val leanKept = JSONArray()
            for (i in 0 until kept.length()) {
                val t = kept.getJSONObject(i)
                val name = t.getJSONObject("function").getString("name")
                if (name !in LEAN_TIER_EXCLUDE) leanKept.put(t)
            }
            return leanKept
        }
        return kept
    }

    /** 仅包含模式切换的 function schema:即使处于 Ask(无文件工具权限)也能让 AI 帮用户切换语义模式。 */
    private fun buildAIModeOnlySchema(): JSONArray = JSONArray().put(modeSwitchFunction())

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
                .put("description", "切换 AI 语义模式:ask=仅对话(没有访问、修改、创建文件的工具权限)、craft=拥有完整 AI 能力可正常调用工具(默认)、plan=先判断是否需要方案,需要则先在工作区创建规划方案文档再继续执行。mode 取值 ask / craft / plan。")
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
    fun resolveAIPath(ctx: Context, userId: Long, root: File, raw: String): String {
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
     * 交付核对路径解析:与真实写入共用 resolveAIPath + sandboxFile,保证「声称路径→核对路径」
     * 与「模型路径→实际写入路径」走同一消毒/规范化逻辑(非 root 模式下前导斜杠、绝对盘符路径
     * 会先消毒为沙盒内相对路径再核对,消除误判)。非法/空返回 null,由调用方按「该路径不可核」处理。
     */
    private fun deliveredFileFor(ctx: Context, userId: Long, root: File, raw: String): File? {
        val resolved = resolveAIPath(ctx, userId, root, raw)
        if (resolved == "\u0000" || resolved.isEmpty()) return null
        return sandboxFile(root, resolved)
    }

    /**
     * 路径安全校验(兜底层,独立于 resolveAIPath)。
     * - 非 root 模式:禁止绝对路径(以 "/" 开头)与 ".." 上跳,只允许锚定在 ai_files 内的相对路径;
     * - root 模式:放行绝对路径与上跳(root 模式下本就有意开放全域访问),但备份目录(workspace_backups)除外——
     *   无论何种模式,AI 一律禁止读取/修改/删除备份,从路径层彻底隔离。
     * 这样即使某条调用链漏调 resolveAIPath,也不会直接落到任意绝对路径上。
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
    private suspend fun doFetchUrl(ctx: Context, userId: Long, call: AIToolCall): String {
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
     * **设备 Python 直跑**(回退路径)下 Python 代码的禁止项。
     *
     * ⚠️ 适用范围(重要,别搞错):
     * - **只作用于「回退到设备自带 Python(如 Termux)」这条路径** —— 那时脚本真实跑在设备上,
     *   绝对路径与 subprocess 确实能越出工作区;
     * - **内置沙盒(proot)不套用本表**:沙盒只绑 /dev、/proc、/sys、/opt/python 与工作区,
     *   设备文件系统没有映射进去,guest 里的 "/sdcard/..." 动不了设备,拦 socket/subprocess
     *   只会白白掐掉「脚本内起服务、调外部命令、复制文件」等正当能力,还会让 AI 误以为
     *   「非 root 模式导致不能运行」(见 doExecutePython 里的 useBuiltinSandbox 分支)。
     * - root 访问模式下不做本项拦截(用户已显式交出全权)。
     * 每项为 名称 to 正则;命中即拒绝执行,并把名称回给模型便于它自我修正。
     *
     * ⚠️ 定位要说清楚:这是**静态文本检测**,只能"**防误伤**"(拦住模型手滑写出越界/破坏性代码),
     * **不是"防对抗"** —— 与 eval/exec/getattr 同级的绕过手法无法穷尽(拼字符串拼出关键字、
     * 走标准库的间接入口等都能绕)。真正的兜底不在这张表,而在:非 root 下整个进程受 Android
     * 沙盒与 SELinux 约束,即便绕过也只能破坏本 app 自己工作区里的文件,动不了系统、动不了其它应用。
     * root 访问模式下**不做**本项拦截(用户已显式交出全权),两者合起来才是纵深防御。
     */
    private val PY_SANDBOX_DENY: List<Pair<String, Regex>> = listOf(
        "subprocess 模块(可执行任意系统命令)" to Regex("""(?i)\bsubprocess\b"""),
        "os.system / os.popen / os.exec* 等命令执行调用" to Regex("""(?i)\bos\s*\.\s*(system|popen|exec\w*|spawn\w*|fork|startfile)\b"""),
        "os.remove / unlink / rmdir / rename / chmod 等文件改动调用" to Regex("""(?i)\bos\s*\.\s*(remove|unlink|rmdir|removedirs|rename|renames|chmod|chown|truncate|link|symlink|replace)\b"""),
        "shutil 移动/删除类操作" to Regex("""(?i)\bshutil\s*\.\s*(rmtree|move|copy\w*|chown|disk_usage)\b"""),
        "绝对路径写法(如 open(\"/sdcard/...\"))" to Regex("""(?i)\b(open|Path|File|makedirs|mkdir|remove|unlink|rmdir|listdir|scandir|walk|stat)\s*\(\s*[rbfu]{0,2}['"]\s*/"""),
        "\"..\" 上跳路径写法" to Regex("""(?i)['"]\s*\.\./"""),
        "pathlib/fileinput 等模块的绝对路径构造" to Regex("""(?i)\b(pathlib|fileinput|tempfile)\s*\.\s*\w+\s*\(\s*[rbfu]{0,2}['"]\s*/"""),
        // ===== 动态/反射逃逸通道(静态检测最常被绕过的手法,一并抬高门槛) =====
        // 负向后顾 (?<![.\w]) 避免误伤 obj.getattr(...) 这类正常写法
        "getattr / setattr / delattr 等动态属性访问" to Regex("""(?i)(?<![.\w])(getattr|setattr|delattr)\s*\("""),
        // 同样避免误伤 re.compile(...) / obj.exec(...) 这类带点的正常调用
        "eval / exec / compile / __import__ 等动态执行入口" to Regex("""(?i)(?<![.\w])(eval|exec|compile|__import__)\s*\("""),
        "__globals__ / __subclasses__ / __builtins__ 等反射逃逸通道" to Regex("""(?i)__(globals|subclasses|builtins|code|mro|bases|closure|func_globals)__"""),
        "ctypes / cffi 等原生调用(可绕过 Python 层的一切限制)" to Regex("""(?i)\b(ctypes|cffi)\b"""),
        "原始 socket 直连(脚本内不得私自建立底层网络连接;需要联网请用 fetch_webpage / http_request 工具)" to Regex("""(?i)\bsocket\b""")
    )

    // 运行环境已移除：保留函数签名以维持工具执行器骨架。
    private fun doShellExec(ctx: Context, userId: Long, call: AIToolCall): String {
        return toolErr("运行环境已移除")
    }

    /** 执行 ADB 命令:依赖 adb 可执行且设备已开启调试。执行目录为应用工作区 ai_files。 */
    private fun doRunAdb(ctx: Context, userId: Long, call: AIToolCall): String {
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
    private suspend fun doHttpRequest(ctx: Context, userId: Long, call: AIToolCall): String {
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
    private fun doProcessList(ctx: Context, userId: Long, call: AIToolCall): String {
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
    private fun doPing(ctx: Context, userId: Long, call: AIToolCall): String {
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

    private suspend fun doSearchWeb(ctx: Context, userId: Long, call: AIToolCall): String {
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

    // ===== 深度研究:一次调用完成多源调研(多角度并发检索 + 跨源去重 + 抓取正文 + 聚合)=====
    /** 把研究课题拆成多个互补的检索角度(代码强制多角度,不依赖模型自觉);模型可用 angles 参数覆盖。 */
    private fun buildResearchAngles(query: String, custom: List<String>): List<String> {
        val cleaned = custom.map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isNotEmpty()) return cleaned.take(6)
        val q = query.trim()
        val base = if (q.length > 60) q.take(60) else q
        return listOf(
            q,
            "$base 是什么 原理",
            "$base 优缺点 对比",
            "$base 最新进展",
            "$base 常见问题 争议",
            "$base 实践 案例"
        ).map { it.trim() }.distinct().take(6)
    }

    /** 抓取一个 URL 的正文纯文本(去脚本/样式/标签,截断到 limit 字符);失败返回空串。 */
    private suspend fun fetchPageText(url: String, limit: Int): String = try {
        if (!url.startsWith("http")) "" else {
            val req = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; AuroraChatAgent/1.0)")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            val html = withContext(Dispatchers.IO) {
                searchClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) "" else (resp.body?.string() ?: "")
                }
            }
            if (html.isBlank()) "" else {
                var t = html
                    .replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<!--[\s\S]*?-->"""), " ")
                    .replace(Regex("""(?i)</(p|div|br|li|tr|h[1-6]|section|article)>"""), "\n")
                    .replace(Regex("""<[^>]+>"""), " ")
                t = android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                    .replace(Regex("""[ \t]+"""), " ").replace(Regex("""\n{3,}"""), "\n\n").trim()
                if (t.length > limit) t.take(limit) + "…（已截断）" else t
            }
        }
    } catch (_: Exception) { "" }

    /**
     * deep_research 实现:多角度【并发】检索 → 跨源去重 → 【并发】抓取 top N 正文 → 聚合结构化材料返回。
     * 关键:检索与抓取都在客户端真实执行,一次工具调用替代模型反复单次搜索——是有实际作用的工具,不是摆设。
     */
    private suspend fun doDeepResearch(ctx: Context, userId: Long, call: AIToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        var q = args.optString("query", "").trim()
        if (q.isBlank()) q = call.path.trim()
        if (q.isBlank()) return toolErr("deep_research 的 query 为空")
        val maxSources = argInt(call, "max_sources", 6).coerceIn(3, 12)
        val customAngles = try {
            val a = args.optJSONArray("angles") ?: JSONArray()
            (0 until a.length()).mapNotNull { i -> a.optString(i, "").takeIf { it.isNotBlank() } }
        } catch (_: Exception) { emptyList<String>() }
        val angles = buildResearchAngles(q, customAngles)

        // ① 多角度并发检索(每路先 Bing 再 DuckDuckGo 回退);单个角度失败不影响其它角度
        val hitsByAngle: List<Pair<String, List<JSONObject>>> = coroutineScope {
            angles.map { angle ->
                async {
                    val enc = java.net.URLEncoder.encode(angle, "UTF-8")
                    val list = try {
                        val j = searchViaBing(enc, angle, 5) ?: searchViaDuckDuckGo(enc, angle, 5)
                        val arr = j?.optJSONArray("results") ?: JSONArray()
                        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
                    } catch (_: Exception) { emptyList<JSONObject>() }
                    angle to list
                }
            }.awaitAll()
        }

        // ② 跨源去重(按去掉锚点/尾部斜杠的 URL 归一化),保留首次出现的标题与摘要
        val merged = LinkedHashMap<String, JSONObject>()
        for ((_, list) in hitsByAngle) {
            for (r in list) {
                val u = r.optString("url", "").trim()
                if (u.isBlank() || !u.startsWith("http")) continue
                val key = u.substringBefore("#").trimEnd('/')
                if (!merged.containsKey(key)) {
                    merged[key] = JSONObject()
                        .put("title", r.optString("title", ""))
                        .put("url", u)
                        .put("snippet", r.optString("snippet", ""))
                }
            }
        }
        val deduped = merged.values.take(maxSources)
        if (deduped.isEmpty()) {
            return toolErr("深度研究未检索到任何结果")
        }

        // ③ 并发抓取正文(篇数多时单篇截断更短,避免撑爆上下文)
        val perPage = if (deduped.size <= 4) 1800 else 1200
        val sources: List<JSONObject> = coroutineScope {
            deduped.map { r ->
                async {
                    val url = r.optString("url", "")
                    val text = try { fetchPageText(url, perPage) } catch (_: Exception) { "" }
                    JSONObject()
                        .put("title", r.optString("title", ""))
                        .put("url", url)
                        .put("snippet", r.optString("snippet", ""))
                        .put("text", text)
                }
            }.awaitAll()
        }

        // ④ 聚合返回结构化材料
        val out = JSONObject()
            .put("query", q)
            .put("source_count", sources.size)
            .put("angles", JSONArray().apply { angles.forEach { put(it) } })
            .put("sources", JSONArray().apply { sources.forEach { put(it) } })
            .put("hint", "")
        return toolOk(out)
    }

    // 运行环境已移除：保留函数签名以维持工具执行器骨架。
    private fun doGit(ctx: Context, userId: Long, call: AIToolCall): String {
        return toolErr("运行环境已移除")
    }

    /** 对比两段文本差异（统一 diff 格式）。 */
    private fun doDiff(ctx: Context, userId: Long, call: AIToolCall): String {
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
    private suspend fun doDownloadFile(ctx: Context, userId: Long, call: AIToolCall): String {
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
            val rel = resolveAIPath(ctx, userId, root, destRel)
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
            // 下载到工作区内的文件记入「查看改动」,与解压/压缩等实际生产操作同等级可见
            try {
                val dlRel = target.relativeTo(root).invariantSeparatorsPath
                if (dlRel.isNotBlank()) {
                    val dlChildren = JSONArray().put(JSONObject().put("path", dlRel).put("size", size).put("isDir", false))
                    recordFileChange(dlRel, "download", children = dlChildren.toString())
                }
            } catch (_: Exception) {}
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
    private fun doInstallApk(ctx: Context, userId: Long, call: AIToolCall): String {
        val path = argString(call, "path")?.trim()?.ifBlank { null }
        if (path == null) return toolErr("install_apk 的 path 不能为空")
        val root = agentRoot(ctx)
        val file: File = if (path.startsWith("/")) {
            // 绝对路径仅 root 模式放行:避免非 root 下被诱导安装任意位置的 APK
            if (!isRootAccess(ctx, userId)) {
                return toolErr("非 root 访问模式下 install_apk 不接受设备绝对路径,只接受工作区内的相对路径(如 app.apk)。请先用 download_file 把 APK 拉到工作区,或用「导入」把本地 APK 带进工作区,再安装。")
            }
            File(path)
        } else {
            val rel = resolveAIPath(ctx, userId, root, path)
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

    /**
     * get_current_location:获取用户当前位置。
     * 没有定位权限时,通过 App 自动弹出系统授权请求,并等待用户在弹窗里做出选择。
     */
    private suspend fun doGetCurrentLocation(ctx: Context, userId: Long, call: AIToolCall): String {
        val timeout = argInt(call, "timeout_sec", 60).coerceIn(5, 180)

        fun granted(perm: String): Boolean =
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        val FINE = android.Manifest.permission.ACCESS_FINE_LOCATION
        val COARSE = android.Manifest.permission.ACCESS_COARSE_LOCATION

        if (!granted(FINE) && !granted(COARSE)) {
            val act = com.aurora.chat.AppVisibility.currentActivity?.get()
            if (act == null || act.isFinishing || act.isDestroyed) {
                return toolErr("还没有定位权限,而且当前没有可用的前台界面来弹出授权请求。请先把 AuroraChat 切到前台,再说一次。")
            }
            runCatching {
                androidx.core.app.ActivityCompat.requestPermissions(
                    act, arrayOf(FINE, COARSE), 9201
                )
            }.onFailure {
                return toolErr("弹出定位权限授权失败:${it.message ?: it.javaClass.simpleName}")
            }
            val deadline = System.currentTimeMillis() + timeout * 1000L
            var ok = false
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(1000)
                if (granted(FINE) || granted(COARSE)) { ok = true; break }
            }
            if (!ok) {
                return toolErr("等待定位授权超时(等了 $timeout 秒)。请在系统弹窗里选择「允许」,或到系统设置里手动开启定位权限,然后再说一次。")
            }
        }

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return toolErr("无法获取系统定位服务(LOCATION_SERVICE)")

        val loc = withContext(kotlinx.coroutines.Dispatchers.IO) {
            var l: android.location.Location? = null
            for (p in listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER
            )) {
                l = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
                if (l != null) break
            }
            if (l == null && android.os.Build.VERSION.SDK_INT >= 30) {
                // 实时定位一次(Android 11+):异步取位置 + 最多等 8 秒
                val exec = java.util.concurrent.Executor { it.run() }
                for (p in listOf(
                    android.location.LocationManager.NETWORK_PROVIDER,
                    android.location.LocationManager.GPS_PROVIDER
                )) {
                    val got = runCatching {
                        var r: android.location.Location? = null
                        val latch = java.util.concurrent.CountDownLatch(1)
                        lm.getCurrentLocation(p, android.os.CancellationSignal(), exec) { loc ->
                            r = loc
                            latch.countDown()
                        }
                        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                        r
                    }.getOrNull()
                    if (got != null) { l = got; break }
                }
            }
            l
        }

        if (loc == null) {
            var opened = false
            if (argBool(call, "open_settings", false)) {
                opened = runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                }.getOrDefault(false)
            }
            return toolErr(
                (if (opened) "已打开系统定位设置页,请开启 GPS/定位服务后再说一次。 " else "") +
                    "已经拿到定位权限,但暂时取不到位置。请确认手机定位服务已打开(网络定位还需要联网),稍后再试一次。"
            )
        }

        val address = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.location.Geocoder(ctx, java.util.Locale.getDefault())
                    .getFromLocation(loc.latitude, loc.longitude, 1)
                    ?.firstOrNull()?.getAddressLine(0)
            }.getOrNull()
        } ?: "${loc.latitude},${loc.longitude}"

        val gpsOn = runCatching {
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)

        return toolOk(JSONObject().apply {
            put("纬度", loc.latitude)
            put("经度", loc.longitude)
            put("地址", address)
            put("精度(米)", if (loc.hasAccuracy()) loc.accuracy.toInt() else JSONObject.NULL)
            put("定位来源", loc.provider ?: "未知")
            put("定位时间", runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(loc.time))
            }.getOrDefault(""))
            put("GPS是否开启", gpsOn)
        })
    }

    /** open_front_camera / open_rear_camera:拉起 AI 相机界面,可选择等待用户拍照并回传照片路径。 */
    private suspend fun doOpenCamera(
        ctx: Context,
        userId: Long,
        call: AIToolCall,
        front: Boolean
    ): String {
        val waitSec = argInt(call, "wait_sec", 0).coerceIn(0, 300)
        val wantClose = argBool(call, "close", false)
        val lensCn = if (front) "前置" else "后置"
        // ⚠️ 必须用「类字面量」拿 Activity 的 Class：AICameraActivity 带 companion object，
        //    `val act = AICameraActivity` 绑定的是伴生对象实例，`act::class.java` 会指向
        //    AICameraActivity$Companion（未在清单注册）→ startActivity 必抛 ActivityNotFoundException。
        val actClass = com.aurora.chat.ui.AICameraActivity::class.java
        val act = com.aurora.chat.ui.AICameraActivity

        // 「只关相机」模式：AI 想收起相机界面时单独调用，不用重新走一遍权限/镜头预检。
        if (wantClose) {
            act.requestClose()
            // 等界面真正结束（最多 3 秒），把结果如实带回
            val dl = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < dl) {
                if (act.lastError != null || act.lastResult != null) break
                kotlinx.coroutines.delay(150)
            }
            return toolOk(JSONObject().apply {
                put("状态", "已请求关闭相机界面")
                put("说明", "若界面仍未收起,用户可手动点右上角「关闭」。")
            })
        }

        // ① 预检本机是否真有这颗镜头：CameraX 启动失败最常见的原因就是「没有对应镜头」，
        //    提前探测可直接把「可用摄像头」列表返回给调用方，避免误判成「界面/入口异常」。
        val lensCheck = probeCameraLens(ctx, front)
        if (!lensCheck.ok) {
            return toolErr(
                if (lensCheck.list.isEmpty()) {
                    "本机未检测到可用摄像头(或相机被系统策略禁用),无法打开${lensCn}摄像头。"
                } else {
                    "本机没有${lensCn}摄像头,无法打开。本机可用摄像头:${lensCheck.list.joinToString("、")}。"
                }
            )
        }

        // ② 相机权限：没有就先自动弹系统授权并等待用户选择，再拉起相机界面
        //    （与 get_current_location 同一套逻辑：不要求用户手动去设置里开）。
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            val host = com.aurora.chat.AppVisibility.currentActivity?.get()
            if (host == null || host.isFinishing || host.isDestroyed) {
                return toolErr("还没有相机权限,而且当前没有可用的前台界面来弹出授权请求。请先把 AuroraChat 切到前台,再说一次。")
            }
            runCatching {
                androidx.core.app.ActivityCompat.requestPermissions(
                    host, arrayOf(android.Manifest.permission.CAMERA), 9202
                )
            }.onFailure {
                return toolErr("弹出相机权限授权失败:${it.message ?: it.javaClass.simpleName}")
            }
            val deadline = System.currentTimeMillis() + 60_000L
            var ok = false
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(1000)
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.CAMERA
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) { ok = true; break }
            }
            if (!ok) {
                return toolErr("等待相机授权超时(等了 60 秒)。请在系统弹窗里选择「允许」,或到系统设置里手动授予相机权限,然后再说一次。")
            }
        }

        act.reset()
        val intent = android.content.Intent(ctx, actClass).apply {
            putExtra(act.EXTRA_LENS, if (front) act.LENS_FRONT else act.LENS_BACK)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 启动前先确认这个界面在当前安装的版本里确实注册过：没注册时明确说「装的是旧版本」，
        // 而不是让系统抛一句 ActivityNotFoundException 让人误以为是代码写错。
        val resolved = runCatching {
            ctx.packageManager.resolveActivity(
                intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
        }.getOrNull()
        if (resolved == null) {
            return toolErr(
                "当前安装的 AuroraChat 里没有注册相机界面(com.aurora.chat.ui.AICameraActivity)。" +
                    "这通常是装的是旧安装包导致的，请重新编译安装最新版本后再试。"
            )
        }
        runCatching { ctx.startActivity(intent) }.onFailure { e ->
            // 区分「界面起不来」与「镜头打不开」，并把真实原因带回给调用方。
            val msg = if (e is android.content.ActivityNotFoundException) {
                "找不到相机界面(${e.message ?: "ActivityNotFoundException"})"
            } else {
                e.message ?: e.javaClass.simpleName
            }
            return toolErr("打开${lensCn}摄像头失败:$msg")
        }
        val saveDirHint = "工作区 ${act.DIR_NAME} 目录"
        if (waitSec <= 0) {
            return toolOk(JSONObject().apply {
                put("状态", "已打开${lensCn}摄像头")
                put("说明", "用户拍完照后照片会保存到$saveDirHint,可稍后用 list_files 查看;若想直接拿到照片路径,把「等待秒数」设成 60 左右再调用一次即可。")
            })
        }
        val deadline = System.currentTimeMillis() + waitSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            act.lastResult?.let { p ->
                val f = File(p)
                act.requestClose()   // 已拿到照片：收起相机，让用户回到对话
                return toolOk(JSONObject().apply {
                    put("状态", "已拍照")
                    put("照片路径", p)
                    put("大小(KB)", if (f.exists()) f.length() / 1024 else JSONObject.NULL)
                    put("说明", "照片可用 get_file 发给用户查看或保存。")
                })
            }
            act.lastError?.let {
                // 用户关闭 / 启动失败：同样是终态，立刻收尾，绝不再傻等
                return toolErr("打开${lensCn}摄像头后出错:$it")
            }
            kotlinx.coroutines.delay(1000)
        }
        // 等待超时：主动收起相机，别让界面一直挂在上面
        act.requestClose()
        return toolOk(JSONObject().apply {
            put("状态", "已打开${lensCn}摄像头,但等待 $waitSec 秒内没有拍照")
            put("说明", "相机界面已为你收起。照片若稍后拍下,会保存到$saveDirHint,可稍后用 list_files 查看。")
        })
    }

    /** 相机镜头预检结果：是否找到目标镜头 + 本机全部可用镜头中文名。 */
    private data class LensProbe(val ok: Boolean, val list: List<String>)

    /**
     * 用 Camera2 的 CameraManager 探测本机镜头。
     * @param front true=要前置,false=要后置
     */
    private fun probeCameraLens(ctx: Context, front: Boolean): LensProbe {
        val want = if (front) {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        } else {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
        return runCatching {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                ?: return LensProbe(false, emptyList())
            val names = cm.cameraIdList.map { cid ->
                when (cm.getCameraCharacteristics(cid)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
                    else -> "其它"
                }
            }.distinct()
            val has = cm.cameraIdList.any { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == want
            }
            LensProbe(has, names)
        }.getOrDefault(LensProbe(false, emptyList()))
    }

    // ── 音量 ──
    private fun doSetVolume(ctx: Context, userId: Long, call: AIToolCall): String {
        val lvl = argInt(call, "level", -1)
        if (lvl !in 0..100) return toolErr("音量必须在 0-100 之间")
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return toolErr("无法获取音频服务")
        val stream = when (argString(call, "stream")?.trim()?.lowercase()) {
            "铃声", "ring" -> android.media.AudioManager.STREAM_RING
            "闹钟", "alarm" -> android.media.AudioManager.STREAM_ALARM
            "通话", "call" -> android.media.AudioManager.STREAM_VOICE_CALL
            "通知", "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
            "系统", "system" -> android.media.AudioManager.STREAM_SYSTEM
            else -> android.media.AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(stream)
        val target = (lvl * max / 100f).toInt().coerceIn(0, max)
        runCatching {
            am.setStreamVolume(
                stream, target,
                if (argBool(call, "show_ui", false)) android.media.AudioManager.FLAG_SHOW_UI else 0
            )
        }.onFailure { return toolErr("设置音量失败:${it.message ?: it.javaClass.simpleName}") }
        return toolOk(JSONObject().apply {
            put("当前音量", am.getStreamVolume(stream))
            put("最大音量", max)
            put("百分比", lvl)
        })
    }

    // ── 音频播放 ──
    private suspend fun doPlayAudio(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val p = argString(call, "path")?.trim()?.ifBlank { null } ?: return toolErr("path 不能为空")
        val rel = resolveAIPath(ctx, userId, root, p)
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法 path:禁止 \"..\" 或绝对路径越界")
        val f = sandboxFile(root, rel)
        if (!f.exists() || !f.isFile) return toolErr("音频文件不存在:${f.absolutePath}")
        val okExt = listOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "amr", "mid")
            .any { f.name.lowercase().endsWith(".$it") }
        if (!okExt) return toolErr("不支持的音频格式:${f.name}(支持 mp3/wav/m4a/aac/ogg/flac/amr/mid)")
        val vol = argInt(call, "volume", 80).coerceIn(0, 100)
        val loop = argBool(call, "loop", false)
        val durSec = argInt(call, "duration_sec", 0).coerceIn(0, 600)
        com.aurora.chat.ui.AIAudioPlayer.stop()
        val mp = android.media.MediaPlayer()
        val prep = runCatching {
            mp.setDataSource(f.absolutePath)
            mp.isLooping = loop
            mp.setVolume(vol / 100f, vol / 100f)
            mp.prepare()
            mp.start()
        }
        if (prep.isFailure) {
            runCatching { mp.release() }
            return toolErr("播放失败:${prep.exceptionOrNull()?.message ?: prep.exceptionOrNull()?.javaClass?.simpleName}")
        }
        com.aurora.chat.ui.AIAudioPlayer.player = mp
        val totalMs = runCatching { mp.duration }.getOrDefault(0)
        if (durSec > 0) {
            val limit = durSec * 1000L
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < limit &&
                runCatching { mp.isPlaying }.getOrDefault(false)
            ) {
                kotlinx.coroutines.delay(300)
            }
            com.aurora.chat.ui.AIAudioPlayer.stop()
            return toolOk(JSONObject().apply {
                put("播放文件", f.absolutePath)
                put("总时长(毫秒)", totalMs)
                put("说明", "已按指定秒数播放并停止")
            })
        }
        return toolOk(JSONObject().apply {
            put("播放文件", f.absolutePath)
            put("总时长(毫秒)", totalMs)
            put("音量", vol)
            put("循环", loop)
            put("说明", "正在后台播放,播到结束自动停止")
        })
    }

    // ── 全屏播放视频（本地优先：工作区内的视频直接读本地文件，不走网络） ──
    private suspend fun doPlayVideo(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val p = argString(call, "path")?.trim()?.ifBlank { null } ?: return toolErr("path 不能为空")
        // 直接给 http/https 在线地址：交给 App 内全屏播放器按网络源播放
        if (p.startsWith("http://") || p.startsWith("https://")) {
            return toolOk(JSONObject().apply {
                put("play_url", p)
                put("is_local", false)
                put("说明", "已请求为用户全屏播放该在线视频")
            })
        }
        // 工作区相对/绝对路径 → 解析到沙盒内真实文件
        val rel = resolveAIPath(ctx, userId, root, p)
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法 path:禁止 \"..\" 或绝对路径越界")
        val f = sandboxFile(root, rel)
        if (!f.exists() || !f.isFile) return toolErr("视频文件不存在：${f.absolutePath}")
        val okExt = listOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "3gpp", "mpg", "mpeg", "flv", "wmv")
            .any { f.name.lowercase().endsWith(".$it") }
        if (!okExt) return toolErr("不支持的视频格式：${f.name}(支持 mp4/mov/avi/mkv/webm/3gp/mpg/mpeg 等)")
        // 返回**本地绝对路径**：UI 侧据此直接用本机文件起播，完全不经过网络
        return toolOk(JSONObject().apply {
            put("play_url", f.absolutePath)
            put("is_local", true)
            put("file_name", f.name)
            put("size", f.length())
            put("说明", "已为用户弹出全屏播放器(本地文件,不消耗网络)")
        })
    }

    // ── 振动 ──
    private suspend fun doVibrate(ctx: Context, userId: Long, call: AIToolCall): String {
        val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            ?: return toolErr("设备不支持振动")
        if (!runCatching { vib.hasVibrator() }.getOrDefault(false)) return toolErr("设备没有振动马达")
        val amp = argInt(call, "amplitude", 255).coerceIn(1, 255)
        val repeat = argInt(call, "repeat", 1).coerceIn(1, 20)
        val patternStr = argString(call, "pattern")?.trim().orEmpty()
        val unit: List<Long> = if (patternStr.isNotBlank()) {
            val one = patternStr.split(',', '、').mapNotNull { it.trim().toLongOrNull() }
            if (one.isEmpty()) return toolErr("振动节奏格式错误:应为毫秒序列,如 300,150,300")
            listOf(0L) + one.map { it.coerceIn(1, 10_000) }
        } else {
            listOf(0L, argInt(call, "duration_ms", 300).coerceIn(1, 5000).toLong())
        }
        val timings = List(repeat) { unit }.flatten()
        val total = timings.sum()
        if (total > 60_000) return toolErr("振动总时长 ${total} 毫秒超过 60 秒上限")
        val hasAmp = runCatching { vib.hasAmplitudeControl() }.getOrDefault(false)
        val start = runCatching {
            if (hasAmp) {
                val amps = IntArray(timings.size) { i -> if (i % 2 == 1) amp else 0 }
                vib.vibrate(android.os.VibrationEffect.createWaveform(timings.toLongArray(), amps, -1))
            } else {
                vib.vibrate(android.os.VibrationEffect.createWaveform(timings.toLongArray(), -1))
            }
            true
        }
        if (start.isFailure) {
            return toolErr("启动振动失败:${start.exceptionOrNull()?.message ?: start.exceptionOrNull()?.javaClass?.simpleName}")
        }
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < total) kotlinx.coroutines.delay(100)
        return toolOk(JSONObject().apply {
            put("振动时长(毫秒)", total)
            put("强度", if (hasAmp) amp else JSONObject.NULL)
            put("节奏", if (patternStr.isNotBlank()) "$patternStr × $repeat" else "连续振动 × $repeat")
            put("说明", "振动已结束")
        })
    }

    /** 取当前前台界面,不可用时返回中文报错。 */
    private fun foregroundActivity(ctx: Context): android.app.Activity? {
        val act = com.aurora.chat.AppVisibility.currentActivity?.get()
        return if (act != null && !act.isFinishing && !act.isDestroyed) act else null
    }

    // ── 屏幕亮度(支持闪烁节奏) ──
    private suspend fun doSetBrightness(ctx: Context, userId: Long, call: AIToolCall): String {
        val v = argInt(call, "brightness", -1)
        if (v !in 0..100) return toolErr("亮度必须在 0-100 之间")
        val act = foregroundActivity(ctx)
            ?: return toolErr("当前没有可用的前台界面,无法调节亮度")
        val target = v / 100f
        fun apply(b: Float) {
            act.runOnUiThread {
                runCatching {
                    val lp = act.window.attributes
                    lp.screenBrightness = b
                    act.window.attributes = lp
                }
            }
        }
        val rhythm = argString(call, "rhythm")?.trim().orEmpty()
        var cycles = 0
        if (rhythm.isNotBlank()) {
            val parts = rhythm.split(',', '、').mapNotNull { it.trim().toLongOrNull() }
            if (parts.size < 3) return toolErr("节奏格式应为「亮毫秒,暗毫秒,次数」,如 600,600,3")
            val onMs = parts[0].coerceIn(50, 10_000)
            val offMs = parts[1].coerceIn(50, 10_000)
            val times = parts[2].toInt().coerceIn(1, 30)
            repeat(times) {
                apply(target)
                kotlinx.coroutines.delay(onMs)
                apply(0.05f)
                kotlinx.coroutines.delay(offMs)
                cycles++
            }
        }
        apply(target)
        return toolOk(JSONObject().apply {
            put("当前亮度", v)
            if (rhythm.isNotBlank()) put("闪烁次数", cycles)
        })
    }

    // ── 屏幕常亮(支持定时与节奏) ──
    private suspend fun doKeepScreenOn(ctx: Context, userId: Long, call: AIToolCall): String {
        val on = argBool(call, "enabled", true)
        val act = foregroundActivity(ctx)
            ?: return toolErr("当前没有可用的前台界面,无法控制屏幕常亮")
        fun apply(flag: Boolean) {
            act.runOnUiThread {
                runCatching {
                    if (flag) {
                        act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
        val rhythm = argString(call, "rhythm")?.trim().orEmpty()
        if (rhythm.isNotBlank()) {
            val parts = rhythm.split(',', '、').mapNotNull { it.trim().toLongOrNull() }
            if (parts.size < 3) return toolErr("节奏格式应为「常亮毫秒,熄灭毫秒,次数」")
            val onMs = parts[0].coerceIn(200, 60_000)
            val offMs = parts[1].coerceIn(200, 60_000)
            val times = parts[2].toInt().coerceIn(1, 20)
            repeat(times) {
                apply(true)
                kotlinx.coroutines.delay(onMs)
                apply(false)
                kotlinx.coroutines.delay(offMs)
            }
            apply(true)
            return toolOk(JSONObject().apply {
                put("屏幕常亮", true)
                put("轮换次数", times)
                put("说明", "节奏执行完毕,已恢复为常亮")
            })
        }
        apply(on)
        val dur = argInt(call, "duration_ms", 0).coerceIn(0, 600_000)
        if (on && dur > 0) {
            kotlinx.coroutines.delay(dur.toLong())
            apply(false)
            return toolOk(JSONObject().apply {
                put("屏幕常亮", false)
                put("保持时长(毫秒)", dur)
                put("说明", "已到设定时长,恢复自动息屏")
            })
        }
        return toolOk(JSONObject().put("屏幕常亮", on))
    }

    // ── 屏幕方向(支持轮换节奏) ──
    private suspend fun doSetScreenOrientation(ctx: Context, userId: Long, call: AIToolCall): String {
        val mode = argString(call, "mode")?.trim() ?: return toolErr("mode 不能为空")
        val target = normalizeEnum(
            mode,
            "竖屏" to "portrait", "portrait" to "portrait", "纵向" to "portrait",
            "横屏" to "landscape", "landscape" to "landscape", "横向" to "landscape",
            "自动" to "auto", "auto" to "auto",
            "传感器" to "sensor", "跟随传感器" to "sensor", "sensor" to "sensor"
        ) ?: return toolErr("不支持的屏幕方向:$mode(可选 竖屏 / 横屏 / 自动 / 跟随传感器)")
        val act = foregroundActivity(ctx)
            ?: return toolErr("当前没有可用的前台界面,无法设置屏幕方向")
        fun codeOf(m: String): Int = when (m) {
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "auto" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        fun applyTo(m: String) {
            act.runOnUiThread { runCatching { act.requestedOrientation = codeOf(m) } }
        }
        val rhythm = argString(call, "rhythm")?.trim().orEmpty()
        var cycles = 0
        if (rhythm.isNotBlank()) {
            val parts = rhythm.split(',', '、').mapNotNull { it.trim().toLongOrNull() }
            if (parts.size < 3) return toolErr("节奏格式应为「竖屏毫秒,横屏毫秒,次数」")
            val pMs = parts[0].coerceIn(200, 20_000)
            val lMs = parts[1].coerceIn(200, 20_000)
            val times = parts[2].toInt().coerceIn(1, 10)
            repeat(times) {
                applyTo("portrait")
                kotlinx.coroutines.delay(pMs)
                applyTo("landscape")
                kotlinx.coroutines.delay(lMs)
                cycles++
            }
        }
        applyTo(target)
        kotlinx.coroutines.delay(150)
        val took = act.requestedOrientation == codeOf(target)
        return toolOk(JSONObject().apply {
            put("目标方向", when (target) {
                "portrait" -> "竖屏"
                "landscape" -> "横屏"
                "auto" -> "自动"
                else -> "跟随传感器"
            })
            put("是否生效", took)
            if (rhythm.isNotBlank()) put("轮换次数", cycles)
            if (!took) put("说明", "当前界面可能被应用固定为竖屏,无法按指令旋转")
        })
    }

    // ── 蓝牙 ──
    private fun doSetBluetooth(ctx: Context, userId: Long, call: AIToolCall): String {
        val on = argBool(call, "on", true)
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val state = runCatching {
            when (adapter?.state) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> "已开启"
                android.bluetooth.BluetoothAdapter.STATE_OFF -> "已关闭"
                android.bluetooth.BluetoothAdapter.STATE_TURNING_ON -> "正在开启"
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF -> "正在关闭"
                else -> "未知"
            }
        }.getOrDefault("未知")
        val direct = adapter != null &&
            runCatching { if (on) adapter.enable() else adapter.disable() }.isSuccess
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return toolOk(JSONObject().apply {
            put("目标状态", if (on) "开启" else "关闭")
            put("直接切换", if (direct) "已尝试直接切换" else "系统不允许应用直接切换")
            put("操作", "已打开系统蓝牙设置页")
            put("当前状态", state)
            put("说明", "Android 12 起第三方应用无法直接控制蓝牙开关,请在弹出的设置页手动切换。")
        })
    }

    // ── GPS / 定位服务 ──
    private fun doSetGps(ctx: Context, userId: Long, call: AIToolCall): String {
        val on = argBool(call, "on", true)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val gpsOn = runCatching {
            lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val netOn = runCatching {
            lm?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return toolOk(JSONObject().apply {
            put("目标状态", if (on) "开启" else "关闭")
            put("操作", "已打开系统定位设置页,请手动切换")
            put("GPS当前状态", gpsOn)
            put("网络定位当前状态", netOn)
        })
    }

    // ── Wi-Fi ──
    private fun doSetWifi(ctx: Context, userId: Long, call: AIToolCall): String {
        val on = argBool(call, "on", true)
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val enabled = runCatching { wm?.isWifiEnabled }.getOrDefault(false)
        val opened = runCatching {
            val panel = android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (panel.resolveActivity(ctx.packageManager) != null) ctx.startActivity(panel)
            else throw IllegalStateException("no panel")
            true
        }.getOrDefault(false)
        if (!opened) {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        return toolOk(JSONObject().apply {
            put("目标状态", if (on) "开启" else "关闭")
            put("操作", "已打开系统 Wi-Fi 面板,请手动切换")
            put("当前状态", enabled)
            put("说明", "Android 10 起应用无法直接开关 Wi-Fi。")
        })
    }

    // ── NFC ──
    private fun doSetNfc(ctx: Context, userId: Long, call: AIToolCall): String {
        val on = argBool(call, "on", true)
        val nfc = android.nfc.NfcAdapter.getDefaultAdapter(ctx)
        val enabled = runCatching { nfc?.isEnabled }.getOrDefault(false)
        runCatching {
            val panel = android.content.Intent(android.provider.Settings.Panel.ACTION_NFC)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (panel.resolveActivity(ctx.packageManager) != null) ctx.startActivity(panel)
            else throw IllegalStateException("no panel")
        }.onFailure {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        return toolOk(JSONObject().apply {
            put("目标状态", if (on) "开启" else "关闭")
            put("设备是否支持NFC", nfc != null)
            put("当前状态", enabled)
            put("操作", "已打开系统 NFC 设置页,请手动切换")
        })
    }

    // ── 闪光灯 ──
    private fun doSetFlashlight(ctx: Context, userId: Long, call: AIToolCall): String {
        val on = argBool(call, "on", true)
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return toolErr("尚未授予相机权限,无法控制闪光灯。可先调用「打开后置摄像头」触发授权,或到系统设置里授予相机权限。")
        }
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            ?: return toolErr("无法获取相机服务")
        val id = runCatching {
            cm.cameraIdList?.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return toolErr("设备没有可用的闪光灯")
        runCatching { cm.setTorchMode(id, on) }
            .onFailure { return toolErr("切换手电筒失败:${it.message ?: it.javaClass.simpleName}") }
        return toolOk(JSONObject().put("手电筒", if (on) "已开启" else "已关闭"))
    }

    // ── 电池 ──
    private fun doBattery(ctx: Context, userId: Long, call: AIToolCall): String {
        if (argBool(call, "exempt", false)) {
            val ok = runCatching {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
            if (!ok) {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        if (argBool(call, "open_settings", false)) {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_POWER_USAGE_SUMMARY)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        val st = runCatching {
            ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = st?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = st?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100f / scale).toInt() else -1
        val code = st?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = code == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            code == android.os.BatteryManager.BATTERY_STATUS_FULL
        val plug = when (st?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1)) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "交流电源"
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
            0 -> "未插电"
            else -> "未知"
        }
        val health = when (st?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1)) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "已损坏"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
            android.os.BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
            android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "未知故障"
            else -> "未知"
        }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return toolOk(JSONObject().apply {
            put("电量百分比", pct)
            put("是否充电", charging)
            put("充电方式", plug)
            put("温度(摄氏度)", (st?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f)
            put("电压(伏)", (st?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f)
            put("健康状态", health)
            put("电池技术", st?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY) ?: "未知")
            put("省电模式", runCatching { pm?.isPowerSaveMode }.getOrDefault(false))
            put("已忽略电池优化", runCatching { pm?.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false))
        })
    }

    // ── 闹钟 / 定时提醒 ──

    /**
     * 设置系统闹钟。三种时间表达：time="HH:mm"（绝对时刻，过了顺延到明天）、
     * in_minutes=N（N 分钟后）、配上 days/daily/weekdays 做周期闹钟。
     *
     * 权限是硬前置（本轮修复重点）：没授权就是失败，绝不再"降级成功"。
     *  - 缺「闹钟和提醒」精确闹钟权限 → 直接打开系统授权页，返回 ok=false，不落闹钟；
     *  - 缺通知权限（Android 13+）→ 当场弹系统授权弹窗并等待，用户拒绝/超时也返回 ok=false。
     * 用户授权后重新调用一次才会真正设置成功。
     */
    private suspend fun doSetAlarm(ctx: Context, call: AIToolCall): String {
        val timeStr = argString(call, "time")?.trim().orEmpty()
        val inMin = argInt(call, "in_minutes", -1)
        val label = argString(call, "label")?.trim().orEmpty().ifBlank { "闹钟" }
        val daily = argBool(call, "daily", false)
        val weekdays = argBool(call, "weekdays", false)
        val daysRaw = argString(call, "days")?.trim().orEmpty()

        val days = when {
            daily -> (1..7).toList()
            weekdays -> listOf(1, 2, 3, 4, 5)
            daysRaw.isNotBlank() -> parseAlarmDays(daysRaw)
                ?: return toolErr("days 格式无法识别：$daysRaw（应为 1-7 逗号分隔，1=周一，如 1,3,5）")
            else -> emptyList()
        }

        // 先校验时间参数，避免参数错误也跑去要权限
        val hm: Pair<Int, Int>? = if (inMin > 0) null else {
            if (timeStr.isBlank()) {
                return toolErr("缺少时间：请传 time=\"HH:mm\" 或 in_minutes（如 10 表示 10 分钟后）")
            }
            parseAlarmClock(timeStr)
                ?: return toolErr("time 格式无法识别：$timeStr（应为 24 小时制 HH:mm，如 07:30）")
        }
        if (inMin > 60 * 24 * 365) return toolErr("in_minutes 过大（上限一年）")

        val perm = com.aurora.chat.alarm.AlarmScheduler.permState(ctx)

        // ① 精确闹钟权限：只能跳系统设置页，必须用户自己打开 → 不等待，直接失败并引导
        if (!perm.exact) {
            val opened = com.aurora.chat.alarm.AlarmScheduler.requestExactPermission(ctx)
            return toolErr(
                buildString {
                    append("闹钟没有设置成功：系统没有授予「闹钟和提醒」权限，未授权时无法保证到点准时响铃，所以没有创建。")
                    if (opened) append("已为你打开系统授权页，请打开「允许设置闹钟和提醒」，然后让我再设置一次。")
                    else append("请到 系统设置 → 应用 → Aurora Chat → 闹钟和提醒，打开该权限后再让我设置一次。（也可在 App 的「启动调试 → 权限」页里打开。）")
                }
            )
        }

        // ② 通知权限：运行时权限，直接弹系统弹窗，等用户点完再决定
        if (!perm.notify) {
            val act = com.aurora.chat.AppVisibility.currentActivity?.get()
            if (act == null || act.isFinishing || act.isDestroyed) {
                return toolErr("闹钟没有设置成功：通知权限未开启，到点你收不到任何提醒。请先把 Aurora Chat 切到前台，或到系统设置里打开通知权限后再让我设置一次。")
            }
            runCatching {
                androidx.core.app.ActivityCompat.requestPermissions(
                    act, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9210
                )
            }
            val deadline = System.currentTimeMillis() + 30_000L
            var ok = com.aurora.chat.alarm.AlarmScheduler.canNotify(ctx)
            while (!ok && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(500)
                ok = com.aurora.chat.alarm.AlarmScheduler.canNotify(ctx)
            }
            if (!ok) {
                return toolErr("闹钟没有设置成功：通知权限仍未开启（等了 30 秒），到点不会响铃。请在系统弹窗里点「允许」，或到设置里打开通知权限后再让我设置一次。")
            }
        }

        // ③ 权限齐了才真正落闹钟（schedule/scheduleAt 权限不齐或排程失败时返回 null）
        val info = when {
            inMin > 0 -> com.aurora.chat.alarm.AlarmScheduler.scheduleAt(
                ctx, System.currentTimeMillis() + inMin * 60_000L, label, days
            )
            hm != null -> com.aurora.chat.alarm.AlarmScheduler.schedule(ctx, hm.first, hm.second, label, days)
            else -> null
        } ?: return toolErr("闹钟没有设置成功：系统拒绝了这次排程（权限可能刚被关闭），请确认「闹钟和提醒」与通知权限都已开启后重试。")

        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", java.util.Locale.CHINA)
        return toolOk(JSONObject().apply {
            put("alarm_id", info.id)
            put("响铃时间", fmt.format(java.util.Date(info.atMillis)))
            put("距现在(分钟)", ((info.atMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0))
            put("名称", info.label)
            put("重复", com.aurora.chat.alarm.AlarmScheduler.repeatText(info.days))
            put("精确闹钟", true)
            put("通知权限", true)
            put(
                "说明",
                "已设置系统闹钟，到点会响铃并震动，App 被划掉或手机重启后依然有效。"
            )
        })
    }

    private fun doCancelAlarm(ctx: Context, call: AIToolCall): String {
        val all = argBool(call, "all", false)
        val id = argInt(call, "alarm_id", -1)
        if (all) {
            val n = com.aurora.chat.alarm.AlarmScheduler.cancelAll(ctx)
            return toolOk(JSONObject().apply {
                put("已取消数量", n)
                put("说明", if (n == 0) "当前没有已设置的闹钟" else "已取消全部 $n 个闹钟")
            })
        }
        if (id <= 0) return toolErr("请传 alarm_id（可先用 list_alarms 查询），或传 all=true 取消全部")
        val ok = com.aurora.chat.alarm.AlarmScheduler.cancel(ctx, id)
        if (!ok) return toolErr("没有找到 id=$id 的闹钟（可能已被取消或已响过）")
        return toolOk(JSONObject().apply {
            put("已取消", id)
            put("说明", "闹钟已取消")
        })
    }

    private fun doListAlarms(ctx: Context): String {
        val arr = com.aurora.chat.alarm.AlarmScheduler.list(ctx)
        val perm = com.aurora.chat.alarm.AlarmScheduler.permState(ctx)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", java.util.Locale.CHINA)
        val items = JSONArray()
        arr.forEach { a ->
            items.put(JSONObject().apply {
                put("alarm_id", a.id)
                put("响铃时间", fmt.format(java.util.Date(a.atMillis)))
                put("时刻", "%02d:%02d".format(a.hour, a.minute))
                put("重复", com.aurora.chat.alarm.AlarmScheduler.repeatText(a.days))
                put("名称", a.label)
            })
        }
        return toolOk(JSONObject().apply {
            put("闹钟数量", arr.size)
            put("闹钟", items)
            put("精确闹钟权限", perm.exact)
            put("通知权限", perm.notify)
            put(
                "说明",
                buildString {
                    append(
                        if (arr.isEmpty()) "当前没有已设置的闹钟"
                        else "共 ${arr.size} 个闹钟，最近一个在 ${fmt.format(java.util.Date(arr.first().atMillis))}"
                    )
                    if (!perm.exact || !perm.notify) {
                        append("。注意：缺少${perm.missingText()}，现在新建闹钟会被拒绝，已有闹钟也可能无法准时响铃/提醒，需先让用户授权。")
                    }
                }
            )
        })
    }

    /** 解析 24 小时制时刻，容忍 7:30 / 07：30 / 7点30 / 下午3点 / 15点 等写法。 */
    private fun parseAlarmClock(raw: String): Pair<Int, Int>? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val afternoon = s.contains("下午") || s.contains("傍晚") || s.contains("晚上") || s.contains("pm", true)
        val m = Regex("(\\d{1,2})\\s*(?:[:：点时]\\s*(\\d{1,2})?)?").find(s) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        if (afternoon && h in 1..11) h += 12
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    /** 解析重复星期："1,3,5" / "周一,周三" / "每天" / "工作日"。 */
    private fun parseAlarmDays(raw: String): List<Int>? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.contains("每天") || s.equals("daily", true)) return (1..7).toList()
        if (s.contains("工作日")) return listOf(1, 2, 3, 4, 5)
        if (s.contains("周末")) return listOf(6, 7)
        val nums = Regex("\\d+").findAll(s).mapNotNull { it.value.toIntOrNull() }.filter { it in 1..7 }
            .toList()
        if (nums.isNotEmpty()) return nums.distinct().sorted()
        val names = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7
        )
        val out = mutableListOf<Int>()
        names.forEach { (k, v) -> if (s.contains("周$k") || s.contains("星期$k") || s.contains("礼拜$k")) out.add(v) }
        return out.distinct().sorted().ifEmpty { null }
    }

    /** 依据已解析路径解析目标文件：root 模式下 rel 可为绝对路径；rel="" 时返回根目录 */
    private fun sandboxFile(root: File, rel: String): File {        if (rel.startsWith("/")) return File(rel)
        var f = root
        for (seg in rel.split('/')) { if (seg.isNotBlank()) f = File(f, seg) }
        return f
    }

    data class AIToolCall(val tool: String, val path: String, val content: String, val argsJson: String = "")

    /** 提取文本中 [TOOL_START]..[TOOL_END] 包裹的首个工具调用;无则返回 null */
    fun extractFirstToolCall(text: String): AIToolCall? {
        val s = text.indexOf(TOOL_START)
        if (s < 0) return null
        val e = text.indexOf(TOOL_END, s)
        if (e < 0) return null
        val json = text.substring(s + TOOL_START.length, e).trim()
        return try {
            val obj = JSONObject(json)
            AIToolCall(
                obj.optString("tool", "").trim(),
                if (obj.has("path")) obj.optString("path") else "",
                if (obj.has("content")) obj.optString("content") else "",
                obj.toString()
            )
        } catch (_: Exception) { null }
    }

    /**
     * 是否存在「未闭合 / 解析失败」的工具调用块。
     *
     * 典型场景：模型用 @tool:start 文本协议写一个大文件（整页 HTML / 脚本），内容还没吐完就被
     * max_tokens 截断 → @tool:end 缺失，或夹在中间的 JSON 不完整。此时 [extractFirstToolCall]
     * 只能返回 null；调用方若只当成"没调工具"处理，就会陷入
     * 「空轮补催 → 模型再一次被截断」的来回空转（用户实测：单独派创建文件的任务没事，
     * AI 自己决定写大文件时就卡住不动、界面既无输出也没中断）。
     * 用本函数把这种情况显式识别出来，交给调用方定向引导「分块写入」。
     */
    fun hasBrokenToolCall(text: String): Boolean {
        val s = text.indexOf(TOOL_START)
        if (s < 0) return false
        val e = text.indexOf(TOOL_END, s)
        if (e < 0) return true
        val json = text.substring(s + TOOL_START.length, e).trim()
        return runCatching { JSONObject(json) }.isFailure
    }

    /**
     * Qwen 系 XML 文本协议兜底：模型把工具调用以纯文本输出在正文里（免费 API 不透传原生 tool_calls 时常见）：
     *   <tool_call>\n<function=write_file>\n<parameter=path>…</parameter>\n<parameter=content>…</parameter>\n</function>\n</tool_call>
     * 旧解析链（原生 tool_calls / @tool 哨兵 / JSON 代码块）都不认这种格式 → 协议原文漏进气泡、工具也不执行
     * （用户截图：write_file 整段协议被当正文渲染，文件根本没写进去）。
     * 参数值截取用「到下一个 <parameter= 或块尾为止」而非「最近的 </parameter>」，兼容 value 里本身
     * 含有 </parameter>/</function> 等字样的文本（逆向任务里被写入的文件内容就可能带这类标记）。
     */
    fun extractXmlStyleToolCall(text: String): AIToolCall? {
        val s = text.indexOf("<tool_call>")
        if (s < 0) return null
        val close = text.indexOf("</tool_call>", s)
        val body = text.substring(s + "<tool_call>".length, if (close > s) close else text.length)
        val fn = Regex("<function\\s*=\\s*([A-Za-z0-9_.-]+)\\s*>").find(body) ?: return null
        val name = fn.groupValues[1].trim()
        if (name.isEmpty()) return null
        val paramsStr = body.substring(fn.range.last + 1)
        // 顺序扫描参数区：每个 <parameter=key> 的值取到「其后第一个 </parameter>」为止，扫描指针跳过整个
        // 已消费区间——值里恰好含 <parameter=…> 字样（逆向任务写 XML/提示词文件很常见）不会被误判成新参数。
        // 未闭合（流式中途截断）时值取到块尾，再循环剥掉尾部残留的闭合标记。
        val paramStartRe = Regex("<parameter\\s*=\\s*([A-Za-z0-9_]+)\\s*>")
        val paramCloseRe = Regex("</parameter\\s*>")
        val args = JSONObject()
        var scan = 0
        while (true) {
            val st = paramStartRe.find(paramsStr, scan) ?: break
            val key = st.groupValues[1]
            val from = st.range.last + 1
            val cm = paramCloseRe.find(paramsStr, from)
            var v: String
            if (cm != null) {
                v = paramsStr.substring(from, cm.range.first).trimEnd()   // </parameter> 前的换行是格式不是值
                scan = cm.range.last + 1
            } else {
                v = paramsStr.substring(from).trimEnd()
                while (true) {
                    val tail = listOf("</parameter>", "</function>", "</tool_call>").firstOrNull { v.endsWith(it) } ?: break
                    v = v.removeSuffix(tail).trimEnd()
                }
                scan = paramsStr.length
            }
            if (v.startsWith("\n")) v = v.substring(1)               // <parameter=k> 后紧跟的换行是格式不是值
            args.put(key, v)
        }
        if (args.length() == 0) return null
        return AIToolCall(
            tool = name,
            path = if (args.has("path")) args.optString("path") else "",
            content = if (args.has("content")) args.optString("content") else "",
            argsJson = args.put("tool", name).toString()
        )
    }

    /**
     * 兜底安全网:扫描文本中的 ```json 代码块,识别含有已知工具名的 JSON(模型可能把工具调用当普通 JSON 输出)。
     * 仅当原生 function calling 和 @tool 文本协议都没命中时才作为最后手段使用。
     * 匹配条件:JSON 里有 "tool" 字段,值在已知工具名列表中。
     */
    fun extractToolCallFromCodeBlock(text: String): AIToolCall? {
        val knownTools = setOf(
            "list_files", "read_file", "write_file", "edit_file", "delete_file", "restore_file",
            "create_folder", "delete_folder",
            "rename_file", "copy_file", "append_file", "stat_file", "find_files", "search_in_files", "query_environment", "shutdown_device", "reboot_device", TOOL_GET_FILE, TOOL_GENERATE_MEDIA, TOOL_EXECUTE_PYTHON, "set_agent_mode",
            TOOL_FETCH_URL, TOOL_SHELL_EXEC, TOOL_RUN_ADB, TOOL_HTTP_REQUEST, TOOL_PROCESS_LIST, TOOL_PING, TOOL_SEARCH_WEB, TOOL_DEEP_RESEARCH, TOOL_GIT, TOOL_DIFF, TOOL_DOWNLOAD_FILE, TOOL_INSTALL_APK, TOOL_FTP_TRANSFER,
            TOOL_GET_CURRENT_LOCATION, TOOL_OPEN_FRONT_CAMERA, TOOL_OPEN_REAR_CAMERA,
            TOOL_SET_VOLUME, TOOL_PLAY_AUDIO, TOOL_PLAY_VIDEO, TOOL_VIBRATE, TOOL_SET_BRIGHTNESS,
            TOOL_KEEP_SCREEN_ON, TOOL_SET_SCREEN_ORIENTATION, TOOL_SET_BLUETOOTH,
            TOOL_SET_GPS, TOOL_SET_WIFI, TOOL_SET_NFC, TOOL_SET_FLASHLIGHT, TOOL_BATTERY,
            TOOL_SWITCH_TAB, TOOL_SEND_MESSAGE, TOOL_SEND_FILE, TOOL_RECALL_MESSAGE, TOOL_SEND_FRIEND_REQUEST, TOOL_CREATE_POST,
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
                    return AIToolCall(
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
                    return AIToolCall(
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
        // 【性能】快速通道：没有协议标记时一行正则都不跑。
        // 本函数在流式链路里每个节流帧都会对**整段累积文本**调用一次，而
        // `[\s\S]*?` 这类惰力量词在长文本上代价很高——纯正文输出(最常见)时这就是白烧主线程，
        // 是「输出非常卡」的直接来源之一。indexOf 是线性扫描但没有回溯，代价低一个量级。
        if (raw.indexOf("@tool:start") < 0 && raw.indexOf("<tool_call>") < 0) return stripToolSentinels(raw)
        var s = raw.replace(Regex("@tool:start[\\s\\S]*?@tool:end"), "")
        // 未闭合的 @tool:start（大文件写入被 max_tokens 拦腰截断）也必须整段剥掉：
        // 否则协议原文连同样式漏进气泡，且 cleanedFull 非空会让编排层误判「模型已作答」而不补催
        // （用户实测：单独派创建文件的任务没事，AI 自己决定写大文件时就卡住不动）。
        val st = s.indexOf(TOOL_START)
        if (st >= 0) s = s.substring(0, st)
        // Qwen XML 文本协议整块剥除；流式中未闭合时从 <tool_call> 起截断——
        // 工具协议块绝不能以正文形式展示（用户截图：write_file 的 <tool_call>…</tool_call> 整段漏进气泡）
        s = s.replace(Regex("<tool_call>[\\s\\S]*?</tool_call>"), "")
        val tc = s.indexOf("<tool_call>")
        if (tc >= 0) s = s.substring(0, tc)
        // 块解析失败时残留的孤立协议标记清扫（含未闭合残片）
        s = s.replace(Regex("</?\\s*(?:tool_call|function|parameter)\\s*=?[^>\\n]{0,60}>"), "")
        return stripToolSentinels(s).trim()
    }

    /**
     * 注入模型上下文**之前**的历史清洗：剥掉交付块哨兵（保留可读正文）与工具状态哨兵。
     *
     * 为什么必须在注入前清：模型会在历史里看到上一轮的**哨兵原文**（`\u0001DELIVER\u0001 …`），
     * 而系统提示每轮又要求它交付 —— 两者叠加会诱导它"把上一轮的交付块整段抄进这一轮"，
     * 形成**嵌套交付块**（渲染边界错位、内容以纯文本外漏的根源）。清掉哨兵后它只能看到可读正文。
     */
    /**
     * 剥掉 DeepSeek「DSML」文本协议块（模型把工具调用当纯文本输出时的兜底）。
     *
     * 症状（用户截图）：聊天正文里裸露出这种内部标记 ——
     *   <｜｜DSML｜｜calls> … <｜｜DSML｜｜invoke name="execute_python"> …整段源码… 

     */

    /** 剥掉 DeepSeek DSML 文本协议块（模型把工具调用当纯文本输出时的兜底）。未闭合时截断到文末。 */
    fun stripDsmlBlocks(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = raw
        // ① 已闭合的整块（全角/半角竖线都认）
        s = s.replace(Regex("<\\s*[|｜]+\\s*DSML[^>]*>[\\s\\S]*?<\\s*/\\s*[|｜]+\\s*DSML[^>]*>"), "")
        // ② 残留的单个标记
        s = s.replace(Regex("<\\s*/?\\s*[|｜]+\\s*DSML[^>]*>"), "")
        // ③ 未闭合（流式中途）：从起始标记处截断
        val m = Regex("<\\s*[|｜]*\\s*DSML").find(s)
        if (m != null && s.indexOf('>', m.range.first) < 0) s = s.substring(0, m.range.first)
        return s.trim()
    }

    /** 注入模型上下文之前的历史清洗：剥掉交付块、工具状态哨兵与 DSML 协议块。 */
    fun sanitizeTextForModel(text: String): String {
        val cleaned = stripToolSentinels(stripDsmlBlocks(stripInlineReasoning(text)))
        // 交付块（目标/已完成/产物/验证/遗留）是**渲染给用户看的 UI 卡片**，不是给模型看的历史。
        // 只去哨兵、把内容留在上下文里，模型下一轮就会照这个格式再写一遍 —— 表现就是"随便问一个问题，
        // 回答也带一段产物清单/验证结果/遗留限制"，Ask、极速这类本该零提示的场景同样中招（纯模仿历史）。
        // 因此这里改成连内容一起剔除；只有块已闭合时才剔除，流式中未闭合的块保守保留，避免误删正在写的内容。
        val parsed = AITaskCompletion.parseDeliveryBlocks(cleaned)
        return (if (parsed.closed) parsed.bodyText else parsed.bodyWithContent).trim()
    }

    /** 长期记忆单次注入的字符预算:超预算的条目不再静默丢弃(会如实告知条数),可用 read_file 读全文 */
    private const val MEMORY_INJECT_MAX_CHARS = 6000

    /** 记忆条目的最小长度:过短的行(空行/分隔符)不单独成条 */
    private const val MEMORY_MIN_ENTRY_CHARS = 2

    /**
     * 把 memory.md 切成「条目」:优先按空行分段;整篇只有一段时退化为按行切
     * (提示词里已要求 AI 用「一行一条」的写法组织记忆)。
     */
    private fun splitMemoryEntries(content: String): List<String> {
        val t = content.replace("\r\n", "\n").trim()
        if (t.isEmpty()) return emptyList()
        val byBlank = t.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.length >= MEMORY_MIN_ENTRY_CHARS }
        if (byBlank.size > 1) return byBlank
        return t.split('\n').map { it.trim() }.filter { it.length >= MEMORY_MIN_ENTRY_CHARS }
    }

    /** 字符 bigram 集合(中英文通用:小写字母数字按相邻两字符切),零依赖的相关度打分用 */
    private fun bigramSet(s: String): Set<String> {
        val t = s.lowercase().filter { it.isLetterOrDigit() }
        if (t.isEmpty()) return emptySet()
        if (t.length == 1) return setOf(t)
        val out = HashSet<String>(t.length)
        for (i in 0 until t.length - 1) out.add(t.substring(i, i + 2))
        return out
    }

    /**
     * 长期记忆的按需注入:按条目与「本轮用户文本」的 bigram 重合度排序,在字符预算内注入最相关的若干条,
     * 并如实告知还有多少条未注入(可用 read_file 读 memory.md 全文)。
     *
     * 取代原先的 `memContent.take(4000)` 硬截断 —— 旧写法在记忆超过 4000 字时会把后面的条目**静默丢掉**,
     * 模型连"有东西被丢了"都不知道;query 为空时退化为按文件原顺序注入(但仍会告知省略条数)。
     */
    private fun buildMemoryInjection(memContent: String, query: String): String {
        val entries = splitMemoryEntries(memContent)
        if (entries.isEmpty()) return ""
        val q = if (query.isBlank()) emptySet() else bigramSet(query)
        // 相关度降序;同分保持原文顺序
        val ordered = entries.withIndex()
            .map { (i, e) -> Triple(e, if (q.isEmpty()) 0 else bigramSet(e).count { it in q }, i) }
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
        val sb = StringBuilder()
        var used = 0
        var omitted = 0
        for ((text, _, _) in ordered) {
            if (used + text.length + 1 > MEMORY_INJECT_MAX_CHARS) { omitted++; continue }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(text)
            used += text.length + 1
        }
        val injectedCount = entries.size - omitted
        val budgetNote = if (omitted == 0) ""
        else "\n(注:记忆共 ${entries.size} 条,以上只注入了与本轮最相关的 $injectedCount 条;另有 $omitted 条因预算未注入——确需时用 read_file 读 memory.md 全文)"
        return "\n当前记忆内容如下(可能过时,与实际情况冲突时以你实际核验到的为准):\n---\n$sb\n---$budgetNote"
    }

    /** 供系统提示注入:应用身份 + 工作区意识 + 工具协议 + 外部文件权限 + 访问控制语义 + 语义模式说明
     *
     *  【前缀静态化约束·硬规矩】本函数只允许依赖**跨回合稳定**的输入(访问模式/执行模式/语义模式/
     *  记忆开关/环境安装态/HTML 预览绑定等)。任何每回合会变的内容——当前时间、记忆检索结果、
     *  工具轮数提示、方案执行约束——一律不许写进这里,必须改用下方的
     *  buildMemoryReminder / buildRoundsReminder / buildPlanExecutionReminder,
     *  由调用方作为**最后一条 user 消息**追加进 history。原因:system 里混入动态内容会让
     *  整段前缀缓存每轮作废(DeepSeek 等上游按前缀匹配计费,命中价约为未命中的 1/10)。 */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun buildAIAwarenessPrompt(ctx: Context? = null, userId: Long = 0L): String = ""

    // ════════════════════════════════════════════════════════════════
    //  前缀静态化:每回合动态信息改为「末尾 user 提醒」,不再注入 system
    //  ---------------------------------------------------------------
    //  system 提示逐字节稳定,上游(DeepSeek/OpenAI 兼容端)的自动前缀缓存才能跨轮、跨回合命中:
    //  命中价约为未命中的 1/10,长任务末轮输入破 100k 时首 token 延迟也差一个量级。
    //  三个函数都自带 Ask / 极速模式分流(与原先藏在 awareness 内部的行为完全一致),
    //  调用方把返回值作为**最后一条 user 消息**追加进 history 即可(空串表示无需注入)。
    // ════════════════════════════════════════════════════════════════

    /**
     * 长期记忆按需检索(记忆开关开启且 memory.md 非空时才返回非空):
     * 按条目与本轮用户文本的 bigram 重合度挑最相关的若干条,在字符预算内注入。
     *
     * 原先这段注入在 awareness 内 —— query 每轮不同,会把整段 system 前缀的缓存全部作废,故外移。
     */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun buildMemoryReminder(ctx: Context?, userId: Long, userText: String): String = ""

    /**
     * 工具轮数提示:随 planActive / 用户设置逐回合变化,属动态信息,故外移(文案与原 awareness 内一致)。
     */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun buildRoundsReminder(ctx: Context?, userId: Long, planActive: Boolean): String = ""

    /**
     * 方案执行·逐步勾选约束:仅在会话已呈现方案卡片时注入(触发条件与原 awareness 内一致)。
     * 该块随方案卡片的出现/交付翻转,属会话级动态信息,故外移为末尾提醒。
     */
    // 工具调用系统提示已清理：保留签名以维持 AI 骨架，恒返回空串。
    fun buildPlanExecutionReminder(ctx: Context?, userId: Long, planActive: Boolean, planCardExists: Boolean): String = ""

    // ===== AI 工具内部实现(全部限定在沙盒根内;失败返回 {ok:false,...},不抛异常) =====
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
    private fun doReadFile(ctx: Context, userId: Long, root: File, rel: String, argsJson: String = ""): String {
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        val f = sandboxFile(root, rel)
        if (!f.exists() || !f.isFile) return toolErr("文件不存在:$rel")
        return try {
            // 解析可选行段参数:start_line/end_line(1-based,含端点)/max_lines
            var startLine = 0
            var endLine = 0
            var maxLines = 0
            if (argsJson.isNotBlank()) {
                val obj = JSONObject(argsJson)
                if (obj.has("start_line")) startLine = obj.optInt("start_line", 0)
                if (obj.has("end_line")) endLine = obj.optInt("end_line", 0)
                if (obj.has("max_lines")) maxLines = obj.optInt("max_lines", 0)
            }
            val hasRange = startLine > 0 || endLine > 0 || maxLines > 0
            if (!hasRange) {
                val content = f.readText(Charsets.UTF_8)
                return toolOk(JSONObject().put("path", rel).put("content", content))
            }
            val lines = f.readLines(Charsets.UTF_8)
            val total = lines.size
            val from = maxOf(1, startLine)
            val rawTo = if (endLine > 0) endLine else total
            val to = minOf(total, rawTo)
            if (from > to) return toolErr("行段无效:start_line=$from 大于文件总行数 $total")
            val slice = if (to >= total) lines.drop(from - 1) else lines.subList(from - 1, to)
            val limited = if (maxLines > 0 && slice.size > maxLines) slice.take(maxLines) else slice
            val actualEnd = minOf(to, from + limited.size - 1)
            toolOk(JSONObject()
                .put("path", rel)
                .put("content", limited.joinToString("\n"))
                .put("line_start", from)
                .put("line_end", actualEnd)
                .put("total_lines", total)
                .put("truncated", limited.size < slice.size))
        } catch (e: Exception) { toolErr("读取失败:${e.message}") }
    }
    /** 删改前自动备份:把工作区现有文件复制到应用私有 agent_backups 目录(命名含时间戳),
     *  用于 write_file/edit_file/delete_file 等破坏性操作前兜底,杜绝"整个文本被覆盖且找不回"。 */
    private fun backupWorkspaceFile(ctx: Context, userId: Long, root: File, rel: String): String? {
        return try {
            val f = sandboxFile(root, rel)
            if (!f.exists() || !f.isFile) return null
            val dir = File(ctx.filesDir, "agent_backups")
            dir.mkdirs()
            val safeName = rel.replace('/', '_').replace('\\', '_')
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val dest = File(dir, "${stamp}__${safeName}")
            f.copyTo(dest, overwrite = true)
            dest.absolutePath
        } catch (_: Exception) { null }
    }

    /** 删改前整体备份文件夹:把整个目录复制到 agent_backups(命名含 __folder_ 前缀,内部保留目录结构)。
     *  用于 delete_folder 的颗粒化退回:单文件恢复 = 备份根下对应相对路径;整体恢复 = 备份根全部内容。 */
    private fun backupWorkspaceFolder(ctx: Context, root: File, dir: File): String {
        return try {
            if (!dir.isDirectory) return ""
            val bdir = File(ctx.filesDir, "agent_backups")
            bdir.mkdirs()
            val rel = try { dir.relativeTo(root).invariantSeparatorsPath } catch (_: Exception) { dir.name }
            val safeName = rel.replace('/', '_').replace('\\', '_').ifBlank { "root" }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val dest = File(bdir, "${stamp}__folder_$safeName")
            dir.copyRecursively(dest, overwrite = true)
            dest.absolutePath
        } catch (_: Exception) { "" }
    }

    /**
     * 单次文件写入 / 追加的内容上限（字符数）。
     *
     * 为什么需要：超过这个长度的内容几乎必然被上游 max_tokens 拦腰截断——截断后 @tool:end 缺失、
     * JSON 不完整，工具解析直接失败，任务表现为「无声卡住：既不报错也不继续」
     * （用户实测：单独派创建文件的任务没事，AI 自己决定写大文件就卡住不动）。
     * 16000 字符 ≈ 6.5k token，即使叠加推理模型的思考也能稳落在输出预算内(普通模式已抬到 24576)。
     * 超限时工具直接拒绝并回给模型可执行的分块方案，比事后补救更可靠。
     */
    private const val MAX_WRITE_CHUNK_CHARS = 16000

    /**
     * HTML 落盘自检：分块写入时最后一段忘写闭合标签，页面打开就是半成品。
     * 只做最基本的 `</html>` 检查（正文里有 `<!DOCTYPE`/`<html` 才判），命中即返回提示文案。
     */
    private fun htmlCloseWarning(rel: String, content: String): String? {
        val lower = rel.lowercase(Locale.ROOT)
        if (!lower.endsWith(".html") && !lower.endsWith(".htm")) return null
        val c = content.lowercase(Locale.ROOT)
        if (!c.contains("<!doctype") && !c.contains("<html")) return null
        if (c.contains("</html>")) return null
        return "该 HTML 尚未闭合（缺少 </html>）：若这是分块写入的最后一段，请用 append_file 补上收尾标签；若后面还要续写，可忽略本提示。"
    }

    private fun doWriteFile(ctx: Context, userId: Long, root: File, rel: String, content: String): String {
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        if (rel.isBlank()) return toolErr("缺少文件名")
        // 【大文件硬护栏】单次写入超限直接拒绝，绝不留半个文件在工作区。
        // 超过 MAX_WRITE_CHUNK_CHARS 的内容几乎必然被上游 max_tokens 拦腰截断：截断后
        // @tool:end 缺失、JSON 不完整 → 工具解析失败、任务无声卡住（用户实测的「一到创建文件就卡壳」）。
        // 这里在入口处就挡住，并把可执行的分块方案直接回给模型，它下一轮就能照着做。
        if (content.length > MAX_WRITE_CHUNK_CHARS) {
            com.aurora.chat.ModelLogger.warn(ctx, "GUARD", "write_file 被拒:内容 ${content.length} 字符 > 上限 $MAX_WRITE_CHUNK_CHARS | 文件=$rel → 已要求分块写入")
            return toolErr(
                "单次写入内容过长(${content.length} 字符 > 上限 $MAX_WRITE_CHUNK_CHARS)，已拒绝执行。请分块写入：" +
                "① 用 write_file 只写文件开头与骨架(如 <!DOCTYPE html> + <head> + 第一个 <script> 起始)；" +
                "② 再用 append_file 逐段续写，每段同样不超过 $MAX_WRITE_CHUNK_CHARS 字符；" +
                "③ 或拆成多个文件(index.html / style.css / game.js)分别写。" +
                "严禁把整个大文件塞进单次调用——会被输出长度上限截断而根本写不进去。"
            )
        }
        val f = sandboxFile(root, rel)
        val oldText = if (f.exists() && f.isFile) try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" } else ""
        val backupPath = if (f.exists() && f.isFile) backupWorkspaceFile(ctx, userId, root, rel) else null
        return try {
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            recordFileChange(rel, if (oldText.isNotEmpty()) "modified" else "created", oldText = oldText, newText = content)
            val ok = JSONObject().put("path", rel).put("bytes", f.length())
                .put("overwritten", oldText.isNotEmpty())
                .put("backup", backupPath ?: JSONObject.NULL)
            // 【落盘自检·网页】分块写入时最后一段很容易忘了闭合标签，页面打开就是半成品。
            // 这里对 HTML 做最基本的闭合检查，把 warning 直接回给模型，它下一轮就会补上。
            htmlCloseWarning(rel, content)?.let { ok.put("warning", it) }
            toolOk(ok)
        } catch (e: Exception) { toolErr("写入失败:${e.message}") }
    }
    /** 定点编辑:把 old_string 替换为 new_string,绝不整体覆盖。找不到/歧义则报错,不静默改写。 */
    private fun doEditFile(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        if (!isSafeRel(ctx, userId, call.path)) return toolErr("非法路径")
        if (call.path.isBlank()) return toolErr("缺少 path")
        val args = argsOf(call)
        val oldStr = args.optString("old_string", "").ifBlank { return toolErr("缺少 old_string") }
        val newStr = args.optString("new_string", "")
        val replaceAll = args.optBoolean("replace_all", false)
        val f = sandboxFile(root, call.path)
        if (!f.exists() || !f.isFile) return toolErr("文件不存在:${call.path}")
        val text = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { return toolErr("读取失败:${e.message}") }
        val count = text.split(oldStr).size - 1
        if (count == 0) return toolErr("old_string 未在文件中找到,请确认与文件内容完全一致(含空白/缩进)")
        if (count > 1 && !replaceAll) return toolErr("old_string 出现 $count 次存在歧义;请加长上下文使其唯一,或传 replace_all=true")
        backupWorkspaceFile(ctx, userId, root, call.path)
        val newText = if (replaceAll) text.replace(oldStr, newStr) else text.replaceFirst(oldStr, newStr)
        return try {
            f.writeText(newText, Charsets.UTF_8)
            recordFileChange(call.path, "modified", oldText = text, newText = newText)
            toolOk(JSONObject().put("path", call.path).put("replacements", if (replaceAll) count else 1).put("bytes", f.length()))
        } catch (e: Exception) { toolErr("写入失败:${e.message}") }
    }
    private fun doDeleteFile(ctx: Context, userId: Long, root: File, relIn: String): String {
        if (relIn == "\u0000") return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        if (relIn.isBlank()) return toolErr("缺少文件名")
        // 【特殊字符鲁棒化】模型给的路径常与磁盘上的真实文件名有细微差异：前后空格、反斜杠、
        // ai_files 前缀、全角分隔符／、全角空格、多余首尾斜杠…… 只拿"原样一个字面量"去 File().exists()
        // 就会误报「文件不存在」，于是删不掉（用户反馈的正是这个）。这里按候选顺序逐个试，取真实存在的那个。
        val candidates = buildList {
            add(relIn)
            add(relIn.trim())
            add(relIn.replace('\\', '/'))
            add(relIn.trim().replace('\\', '/').trim('/'))
            val half = relIn.replace('\uFF0F', '/').replace('\u3000', ' ')
            if (half != relIn) {
                add(half)
                add(half.trim().replace('\\', '/').trim('/'))
            }
            add(relIn.trim().replace('\\', '/').trim('/').removePrefix("ai_files/"))
        }.distinct()
        val hit = candidates.firstOrNull { c -> c.isNotBlank() && sandboxFile(root, c).exists() }
        if (hit == null) {
            com.aurora.chat.ModelLogger.warn(
                ctx, "TOOL",
                "delete_file 找不到目标 | 模型给的路径=[$relIn] 已尝试=${candidates.joinToString(" | ")}"
            )
            return toolErr("文件不存在:$relIn")
        }
        val rel = hit
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        val f = sandboxFile(root, rel)
        if (!f.isFile) return toolErr("目标不是文件:$rel")
        val size = try { f.length() } catch (_: Exception) { 0L }
        // 二进制判定:含 NUL 字节或读取失败 → 不存文本,只记大小(删除 zip 等二进制文件时「查看改动」不再误当文本 diff)
        var oldText = ""
        if (size in 1..SNAP_TEXT_MAX) {
            try {
                val bytes = f.readBytes()
                if (!bytes.contains(0.toByte())) oldText = String(bytes, Charsets.UTF_8)
            } catch (_: Exception) { }
        }
        val backup = backupWorkspaceFile(ctx, userId, root, rel) ?: ""
        if (!f.delete()) {
            // 删除失败时把**判因所需的事实**记进模型日志：可写性 / 是否目录 / 绝对路径，
            // 避免下次又要靠猜（是权限问题、还是路径对不上、还是目标是目录）。
            com.aurora.chat.ModelLogger.warn(
                ctx, "TOOL",
                "delete_file 删除失败 | 路径=$rel 绝对=${f.absolutePath} canWrite=${f.canWrite()} isDir=${f.isDirectory} exists=${f.exists()}"
            )
            return toolErr("删除失败:$rel")
        }
        recordFileChange(rel, "deleted", oldText = oldText, size = size, backup = backup)
        return toolOk(JSONObject().put("path", rel))
    }
    /** 从自动备份恢复工作区文件(兜底"被覆盖/误删")。 */
    private fun doRestoreFile(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        if (!isSafeRel(ctx, userId, call.path)) return toolErr("非法路径")
        if (call.path.isBlank()) return toolErr("缺少 path")
        val dir = File(ctx.filesDir, "agent_backups")
        if (!dir.exists()) return toolErr("无可用备份")
        val safeName = call.path.replace('/', '_').replace('\\', '_')
        val wanted = argString(call, "backup")?.trim()?.ifBlank { null }
        val candidates = dir.listFiles().orEmpty().filter { it.name.endsWith("__$safeName") }
            .sortedByDescending { it.name }
        val src = (if (wanted != null) candidates.firstOrNull { it.name == wanted } ?: candidates.firstOrNull()
                  else candidates.firstOrNull())
            ?: return toolErr("未找到 ${call.path} 的备份")
        val dest = sandboxFile(root, call.path)
        // 恢复前先抓取目标路径的「改动前内容」，写入 oldText，使 restore_file 可被退回：
        // 原本不存在(新建型恢复)→ 退回=删除；原本存在(覆盖型恢复)→ 退回=还原覆盖前内容。
        val preRestoreText = if (dest.exists() && dest.isFile) {
            try { dest.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
        } else ""
        return try {
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            val restored = try { dest.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
            recordFileChange(call.path, "restored", oldText = preRestoreText, newText = restored)
            toolOk(JSONObject().put("path", call.path).put("backup", src.name).put("bytes", dest.length()))
        } catch (e: Exception) { toolErr("恢复失败:${e.message}") }
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
        // 删除前收集目录内容,供「查看改动」展开文件夹内容(用户能看清删了哪些东西)
        val children = collectDirChildrenJson(root, f)
        // 删除前整体备份到 agent_backups(保留目录结构),供「查看改动」颗粒化退回(单文件/整文件夹)
        val backup = backupWorkspaceFolder(ctx, root, f)
        return if (f.deleteRecursively()) {
            recordFileChange(rel, "deleted_folder", children = children, backup = backup)
            toolOk(JSONObject().put("path", rel))
        } else toolErr("删除失败:$rel")
    }
    private fun doRenameFile(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val rel = resolveAIPath(ctx, userId, root, call.path)
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
    private fun doCopyFile(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val srcRel = resolveAIPath(ctx, userId, root, argString(call, "source") ?: "")
        val dstRel = resolveAIPath(ctx, userId, root, argString(call, "target") ?: "")
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
    private fun doExtractArchive(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val srcRel = resolveAIPath(ctx, userId, root, argString(call, "source") ?: "")
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
            val tRel = resolveAIPath(ctx, userId, root, targetArg)
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
            // 解压产物记入「查看改动」,并在 children 里带上解压出的子项清单,供展开查看
            recordFileChange(targetRel, "extract", children = collectDirChildrenJson(root, targetDir))
            toolOk(JSONObject()
                .put("source", srcRel)
                .put("target", targetRel)
                .put("extracted", extracted)
                .put("skipped", skipped)
                .put("bytes", src.length()))
        } catch (e: Exception) { toolErr("解压失败:${e.message}") }
    }

    /** move_dir_contents:一次性把源目录所有直接子项搬到目标目录。target 空=上一级目录。自动处理重名。 */
    private fun doMoveDirContents(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val srcRel = resolveAIPath(ctx, userId, root, argString(call, "source") ?: "")
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
            val tRel = resolveAIPath(ctx, userId, root, targetArg)
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
        // 被搬移子项记入「查看改动」(children 用移动后的相对路径),供展开查看
        val movedChildren = JSONArray()
        for (child in children) {
            val newRel = if (targetRel.isEmpty()) child.name else "$targetRel/${child.name}"
            movedChildren.put(JSONObject()
                .put("path", newRel)
                .put("size", try { if (child.isFile) child.length() else 0L } catch (_: Exception) { 0L })
                .put("isDir", child.isDirectory))
        }
        recordFileChange(srcRel, "moved_dir", children = movedChildren.toString())
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
    private fun doCompressArchive(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val args = argsOf(call)
        val sourcesArr = args.optJSONArray("sources")
            ?: return toolErr("sources 必须是路径数组")
        val targetArg = args.optString("target", "").trim()
        if (targetArg.isBlank()) return toolErr("缺少 target")
        val targetRel = resolveAIPath(ctx, userId, root, targetArg)
        if (!isSafeRel(ctx, userId, targetRel)) return toolErr("非法 target 路径")
        val target = sandboxFile(root, targetRel)
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        val packedFiles = ArrayList<File>()
        try {
            target.outputStream().use { fos ->
                ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                    for (i in 0 until sourcesArr.length()) {
                        val srcRel = sourcesArr.getString(i).trim()
                        if (srcRel.isBlank()) continue
                        if (!isSafeRel(ctx, userId, srcRel)) continue
                        val src = sandboxFile(root, srcRel)
                        if (!src.exists()) continue
                        packedFiles.add(src)
                        val baseName = args.optString("root", "").ifBlank { null }
                        addToZip(zos, src, baseName ?: src.name)
                    }
                }
            }
            // 压缩产物记入「查看改动」,children 里列出被打包的内容,供展开查看
            recordFileChange(targetRel, "compress", children = collectPathsJson(root, packedFiles))
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
    private fun doArchiveList(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val rel = resolveAIPath(ctx, userId, root, argString(call, "path") ?: "")
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
    private fun doArchiveRead(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val args = argsOf(call)
        val rel = resolveAIPath(ctx, userId, root, argString(call, "path") ?: "")
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
    private fun doKillProcess(ctx: Context, userId: Long, call: AIToolCall): String {
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
    private fun doStopApp(ctx: Context, userId: Long, call: AIToolCall): String {
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
    private fun doUploadFile(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val args = argsOf(call)
        val rel = resolveAIPath(ctx, userId, root, argString(call, "path") ?: "")
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
    private fun doFtpTransfer(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
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
                    val rel = resolveAIPath(ctx, userId, root, args.optString("local_path", ""))
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
                    val rel = resolveAIPath(ctx, userId, root, args.optString("local_path", ""))
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

    /** work_log:查看 / 清空工作日志。日志目录存应用私有沙盒 /work_logs/,大小硬限 2MB。 */
    private fun doWorkLog(ctx: Context, userId: Long, call: AIToolCall): String {
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
    private fun doBatchReplace(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
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
    private fun doDnsLookup(call: AIToolCall): String {
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
    private fun doPortScan(call: AIToolCall): String {
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
    private fun doTraceroute(ctx: Context, userId: Long, call: AIToolCall): String {
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
    private fun doBandwidthTest(call: AIToolCall): String {
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
    private fun doSendNotification(ctx: Context, userId: Long, call: AIToolCall): String {
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
    private fun doApplyPatch(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
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
    private fun doQrDecode(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val rel = resolveAIPath(ctx, userId, root, argString(call, "path") ?: "")
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
    private fun doCryptoEncrypt(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
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
                val rel = resolveAIPath(ctx, userId, root, args.optString("path", ""))
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
            if (!isSafeRel(ctx, userId, resolveAIPath(ctx, userId, root, destArg)))
                return toolErr("非法 dest")
            val dest = sandboxFile(root, resolveAIPath(ctx, userId, root, destArg))
            dest.parentFile?.mkdirs()
            dest.writeBytes(full)
            toolOk(JSONObject().put("dest", destArg).put("bytes", full.size))
        }
    }

    /** crypto_decrypt:AES 256-CBC 解密。密文前 16 字节为 IV(加密时拼上的)。 */
    private fun doCryptoDecrypt(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
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
                val rel = resolveAIPath(ctx, userId, root, args.optString("path", ""))
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
            if (!isSafeRel(ctx, userId, resolveAIPath(ctx, userId, root, destArg)))
                return toolErr("非法 dest")
            val dest = sandboxFile(root, resolveAIPath(ctx, userId, root, destArg))
            dest.parentFile?.mkdirs()
            dest.writeBytes(plain)
            toolOk(JSONObject().put("dest", destArg).put("bytes", plain.size))
        }
    }

    private fun doAppendFile(ctx: Context, userId: Long, root: File, rel: String, content: String): String {
        if (!isSafeRel(ctx, userId, rel) || rel.isBlank()) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        // 与 write_file 同一把尺：续写段同样不许超长，否则模型会用 append_file 一次性塞回整个大文件，
        // 再次触发「被 max_tokens 截断 → 解析失败 → 无声卡住」。
        if (content.length > MAX_WRITE_CHUNK_CHARS) {
            com.aurora.chat.ModelLogger.warn(ctx, "GUARD", "append_file 被拒:内容 ${content.length} 字符 > 上限 $MAX_WRITE_CHUNK_CHARS | 文件=$rel → 已要求拆更小")
            return toolErr("单次追加内容过长(${content.length} 字符 > 上限 $MAX_WRITE_CHUNK_CHARS)，已拒绝执行。请把这一段再拆小，每次追加不超过 $MAX_WRITE_CHUNK_CHARS 字符。")
        }
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
    private fun doFindFiles(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val keyword = (argString(call, "keyword") ?: "").trim()
        if (keyword.isBlank()) return toolErr("缺少搜索关键字")
        val baseRel = resolveAIPath(ctx, userId, root, argString(call, "path") ?: "")
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

    /** 内容搜索(search_in_files)跳过的目录:依赖/缓存/构建产物,搜它们只会噪声淹没真结果 */
    private val SEARCH_SKIP_DIRS = setOf(
        "node_modules", "bower_components", "vendor", "dist", "build", "out", "target",
        "__pycache__", "venv", ".venv", "env", "site-packages", "Pods", ".gradle", ".idea",
        ".git", "agent_backups", "workspace_backups"
    )
    /** 内容搜索跳过的二进制/媒体扩展名(按内容搜索这些文件无意义,还会把结果刷满乱码行) */
    private val SEARCH_SKIP_EXTS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "heif", "svgz",
        "mp3", "wav", "ogg", "m4a", "aac", "flac", "mp4", "mov", "avi", "mkv", "webm", "3gp",
        "zip", "rar", "7z", "gz", "bz2", "xz", "tar", "jar", "apk", "aab", "dex", "so", "dll",
        "exe", "bin", "class", "o", "a", "pyc", "woff", "woff2", "ttf", "otf", "db", "sqlite",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"
    )

    /**
     * 工作区**文件内容**搜索(grep 语义):返回「相对路径 + 行号 + 该行内容」。
     * 与 find_files(只搜文件名)互补,用于定位函数/变量/字符串/报错出现在哪些文件、核对改动是否真的落盘。
     * 只读;自动跳过二进制名、超大文件(>2MB)与依赖/缓存目录;命中达上限立即停止并标记 truncated。
     */
    private fun doSearchInFiles(ctx: Context, userId: Long, root: File, call: AIToolCall): String {
        val query = (argString(call, "query") ?: argString(call, "keyword") ?: "").trim()
        if (query.isBlank()) return toolErr("缺少搜索内容 query")
        val baseRel = resolveAIPath(ctx, userId, root, argString(call, "path") ?: "")
        if (!isSafeRel(ctx, userId, baseRel)) return toolErr("非法路径")
        val base = if (baseRel.isBlank()) root else sandboxFile(root, baseRel)
        if (!base.exists() || !base.isDirectory) return toolErr("目录不存在:$baseRel")

        val useRegex = argBool(call, "regex", false)
        val caseSensitive = argBool(call, "case_sensitive", false)
        val maxResults = argInt(call, "max_results", 60).coerceIn(1, 200)
        val globs = (argString(call, "glob") ?: "").split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

        val regex: Regex? = if (useRegex) {
            try {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } catch (e: Exception) {
                return toolErr("正则表达式无法编译:${e.message}(可把 regex 设为 false 改按纯文本搜索)")
            }
        } else null
        val needle = if (caseSensitive) query else query.lowercase()

        val matches = JSONArray()
        var truncated = false
        var scanned = 0
        val isRoot = isRootAccess(ctx, userId)

        fun globMatch(name: String): Boolean {
            if (globs.isEmpty()) return true
            val n = name.lowercase()
            return globs.any { g ->
                val pat = g.lowercase()
                when {
                    pat.startsWith("*.") -> n.endsWith(pat.substring(1))
                    pat.startsWith("*") -> n.contains(pat.trimStart('*'))
                    else -> n == pat
                }
            }
        }

        fun walk(d: File, depth: Int) {
            if (truncated || depth > 12) return
            val children = d.listFiles() ?: return
            for (f in children) {
                if (truncated) return
                if (f.isDirectory) {
                    if (f.name.startsWith(".") || f.name in SEARCH_SKIP_DIRS) continue
                    walk(f, depth + 1)
                    continue
                }
                if (!f.isFile) continue
                if (!globMatch(f.name)) continue
                if (f.extension.lowercase() in SEARCH_SKIP_EXTS) continue
                val len = f.length()
                if (len <= 0L || len > 2_000_000L) continue
                val lines = try { f.readLines(Charsets.UTF_8) } catch (_: Exception) { continue }
                scanned++
                val rel = if (isRoot) f.absolutePath else relativeOf(root, f)
                for ((i, line) in lines.withIndex()) {
                    val hit = if (regex != null) regex.containsMatchIn(line)
                    else (if (caseSensitive) line else line.lowercase()).contains(needle)
                    if (!hit) continue
                    val t = line.trim()
                    matches.put(JSONObject()
                        .put("path", rel)
                        .put("line", i + 1)
                        .put("text", if (t.length > 300) t.take(300) + "…" else t))
                    if (matches.length() >= maxResults) { truncated = true; return }
                }
            }
        }
        walk(base, 0)

        val obj = JSONObject()
            .put("query", query)
            .put("regex", useRegex)
            .put("scanned_files", scanned)
            .put("count", matches.length())
            .put("truncated", truncated)
            .put("matches", matches)
        if (matches.length() == 0) obj.put(
            "hint",
            "没有命中。可尝试:①换更短/更通用的关键字;②去掉 glob 限制;③确认 path 起点是否正确(当前起点:${
                baseRel.ifBlank { "工作区根目录" }
            })——注意本工具搜的是**文件内容**,只找文件名请用 find_files。"
        )
        if (truncated) obj.put("hint", "命中已达上限 $maxResults 条,结果已截断")
        return toolOk(obj)
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
    private suspend fun doReadBackupFile(ctx: Context, call: AIToolCall): String {
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
    private suspend fun doRestoreBackup(ctx: Context, call: AIToolCall): String {
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

    // 运行环境已移除：保留函数签名以维持工具执行器骨架。
    private fun doQueryEnvironment(ctx: Context, userId: Long): String {
        return toolErr("运行环境已移除")
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
            else toolErr("电源命令未能执行(exit=$exit)")
        } catch (e: Exception) {
            toolErr("电源命令执行失败:缺少 root 权限或系统不支持(${e.message ?: e.javaClass.simpleName})")
        }
    }

    // 运行环境已移除：保留函数签名以维持工具执行器骨架。
    private fun doExecutePython(ctx: Context, userId: Long, call: AIToolCall): String {
        return toolErr("运行环境已移除")
    }

    // 运行环境已移除：保留函数签名以维持工具执行器骨架。
    private suspend fun doRunScript(ctx: Context, userId: Long, call: AIToolCall): String {
        return toolErr("运行环境已移除")
    }

    // 运行环境已移除：保留函数签名以维持工具执行器骨架。
    private fun noRunnerMsg(ctx: Context, ext: String): String {
        return toolErr("运行环境已移除")
    }

    // 运行环境已移除：原通用脚本结果组装函数已删除。

    /** 组装 Python 执行结果(stdout/stderr 过长截断;退出码非 0 视为错误)。 */
    private fun buildPythonResult(exit: Int, rawOut: String, rawErr: String, timedOut: Boolean): String {
        if (timedOut) return toolErr("Python 执行超时(30 秒),已强制终止")
        val out = rawOut.trim()
        val err = rawErr.trim()
        val outClip = if (out.length > 4000) out.take(4000) + "\n...(输出过长已截断,总长 ${out.length})" else out
        val errClip = if (err.length > 2000) err.take(2000) + "\n...(错误过长已截断,总长 ${err.length})" else err
        val j = JSONObject().put("exit", exit)
        if (out.isNotBlank()) j.put("stdout", outClip)
        if (err.isNotBlank()) j.put("stderr", errClip)
        return if (exit == 0) toolOk(j) else toolErr("Python 执行退出码 $exit${if (err.isNotBlank()) ":$errClip" else ""}")
    }

    // ===== 逆向分析工具实现 =====
    // 复用与 doExecutePython 同一套 Python 引擎(内置 Debian 环境优先,启动失败回退设备 Python);
    // 默认超时更长(60s)、不做脚本快照 diff(只读分析,不写工作区源码)。各工具把完整报告写入
    // 工作区 reverse_reports/,返回摘要 + 报告路径,绕开 stdout 4000 字截断,让大样本也能产出完整结果。
    private suspend fun execPythonScript(ctx: Context, userId: Long, code: String, timeoutSec: Int = 60): String {
        return toolErr("运行环境已移除")
    }

    private const val REVERSE_STRINGS_PY = """
import sys, re, json
inp="__INPUT__"
report="__REPORT__"
minlen=int("__MINLEN__")
try:
    with open(inp,'rb') as f: data=f.read()
    runs=re.findall(rb'[\x20-\x7e]{%d,}' % minlen, data)
    strings=[r.decode('latin-1') for r in runs]
    pats={
      'url':re.compile(r'https?://[^\s\x00-\x1f"\x27]+'),
      'ip':re.compile(r'\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b'),
      'email':re.compile(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'),
      'apikey':re.compile(r'(?i)(?:api[_-]?key|apikey|access[_-]?token|secret|password|passwd|pwd|token|authorization|bearer|private[_-]?key)\s*[:=]\s*[\x27"]?[\w\-./+]{8,}'),
      'base64':re.compile(r'(?:[A-Za-z0-9+/]{24,}={0,2})'),
      'jni':re.compile(r'Java_[A-Za-z0-9_]+'),
      'package':re.compile(r'(?:[a-z][a-z0-9_]*\.)+[a-z][a-z0-9_]{2,}'),
      'path':re.compile(r'(?:/[\w.\-]+){2,}'),
      'sql':re.compile(r'(?i)\b(select|insert|update|delete|create table|drop table|alter table)\b.*?\b(from|table)\b'),
    }
    res={k:[] for k in pats}
    for s in strings:
        for k,p in pats.items():
            for m in p.findall(s):
                if isinstance(m,tuple): m=m[0]
                if m not in res[k]: res[k].append(m)
    for k in res:
        if len(res[k])>50: res[k]=res[k][:50]
    full={'file':inp,'size':len(data),'total_printable':len(strings),'classified':res,'sample':strings[:40]}
    with open(report,'w') as f: json.dump(full,f,ensure_ascii=False,indent=2)
    print('SUMMARY')
    print('file: '+inp)
    print('size: '+str(len(data))+' bytes; printable strings: '+str(len(strings)))
    for k in pats: print('  '+k+': '+str(len(res[k])))
    print('report: '+report)
except Exception as e:
    print('ERROR: '+str(e))
"""

    private const val REVERSE_DEX_PY = """
import sys, re, json, zipfile, struct
inp="__INPUT__"
report="__REPORT__"
try:
    z=zipfile.ZipFile(inp)
    names=z.namelist()
    dex=[n for n in names if n.endswith('.dex')]
    solibs=[n for n in names if n.startswith('lib/') and n.endswith('.so')]
    out={'file':inp,'entries':len(names),'dex_files':dex,'native_libs':solibs,'has_arsc':any(n.endswith('.arsc') for n in names)}
    def u32(b,o): return struct.unpack_from('<I',b,o)[0]
    dex_info=[]; blob=None
    for dn in dex:
        raw=z.read(dn)
        if raw[:4]!=b'dex\n': continue
        info={'name':dn,'string_ids':u32(raw,0x38),'type_ids':u32(raw,0x3c),'proto_ids':u32(raw,0x40),'field_ids':u32(raw,0x44),'method_ids':u32(raw,0x48),'class_defs':u32(raw,0x4c)}
        dex_info.append(info); blob=raw
    out['dex_header']=dex_info
    if blob:
        text=blob.decode('latin-1')
        keys={'crypto_cipher':'Ljavax/crypto/Cipher','message_digest':'Ljava/security/MessageDigest','mac':'Ljavax/crypto/Mac','secret_key_spec':'Ljavax/crypto/spec/SecretKeySpec','iv_param_spec':'LIvParameterSpec','reflection_forname':'Class.forName','reflection_invoke':'Method.invoke','get_declared_method':'getDeclaredMethod','http_urlconn':'Ljava/net/HttpURLConnection','okhttp':'Lokhttp3/OkHttpClient','retrofit':'Lretrofit2/Retrofit','load_library':'System.loadLibrary','sqlite':'Landroid/database/sqlite/SQLite','shared_pref':'Landroid/content/SharedPreferences'}
        hits={}
        for k,p in keys.items():
            c=len(re.findall(re.escape(p),text))
            if c: hits[k]=c
        out['api_hits']=hits
        runs=re.findall(rb'[\x20-\x7e]{6,}',blob)
        strs=[r.decode('latin-1') for r in runs]
        urls=set(); secrets=set()
        for s in strs:
            for u in re.findall(r'https?://[^\s\x00-\x1f"\x27]+',s): urls.add(u)
            for tok in re.findall(r'(?i)(?:api[_-]?key|token|secret|password|authorization)\s*[:=]\s*[\x27"]?[\w\-./+]{8,}',s): secrets.add(tok)
        out['sampled_urls']=list(urls)[:40]
        out['sampled_secrets']=list(secrets)[:20]
    try:
        from androguard.core.apk import APK
        a=APK(inp)
        out['package']=a.get_package()
        out['permissions']=a.get_permissions()
        out['activities']=a.get_activities()
        out['services']=a.get_services()
        out['receivers']=a.get_receivers()
        out['providers']=a.get_providers()
        out['androguard']='ok'
    except Exception as e:
        out['androguard']='unavailable: '+str(e)[:120]
    with open(report,'w') as f: json.dump(out,f,ensure_ascii=False,indent=2)
    print('SUMMARY')
    print('file: '+inp+' | entries: '+str(out['entries'])+' | dex: '+str(len(dex))+' | native_libs: '+str(len(solibs)))
    print('dex_header: '+json.dumps(dex_info))
    print('api_hits: '+json.dumps(out.get('api_hits',{})))
    print('sampled_urls: '+str(len(out.get('sampled_urls',[])))+' | sampled_secrets: '+str(len(out.get('sampled_secrets',[]))))
    if 'package' in out: print('package: '+out['package'])
    print('manifest: activities='+str(len(out.get('activities',[])))+' services='+str(len(out.get('services',[])))+' receivers='+str(len(out.get('receivers',[])))+' providers='+str(len(out.get('providers',[])))+' permissions='+str(len(out.get('permissions',[]))))
    print('androguard: '+str(out.get('androguard')))
    print('report: '+report)
except Exception as e:
    print('ERROR: '+str(e))
"""

    private const val REVERSE_NATIVE_PY = """
import sys, re, json, struct
inp="__INPUT__"
report="__REPORT__"
try:
    with open(inp,'rb') as f: head=f.read(20)
    out={'file':inp}
    if head[:4]==b'\x7fELF':
        out['format']='ELF'
        ei_class=head[4]; ei_data=head[5]
        machine=struct.unpack_from('<H',head,18)[0]
        arch={0x28:'ARM',0xB7:'ARM64/AArch64',0x3E:'x86-64',0x03:'x86',0x08:'MIPS',0x32:'IA-64',0x16:'POWERPC'}.get(machine,'?')
        out['bits']=32 if ei_class==1 else 64
        out['endian']='LE' if ei_data==1 else 'BE'
        out['arch']=arch
        try:
            import lief
            b=lief.parse(inp)
            try: out['entrypoint']=hex(b.entrypoint)
            except Exception: pass
            try: out['sections']=[{'name':s.name,'size':s.size,'vaddr':hex(s.virtual_address)} for s in b.sections[:80]]
            except Exception: pass
            try: out['imported_functions']=list(b.imported_functions)[:300]
            except Exception: pass
            try: out['exported_functions']=list(b.exported_functions)[:300]
            except Exception: pass
            try: out['libraries']=list(b.libraries)[:100]
            except Exception: pass
            out['lief']='ok'
        except Exception as e:
            out['lief']='unavailable: '+str(e)[:120]
            out['note']='lief 不可用，仅给出 ELF 基础头信息'
        data=open(inp,'rb').read()
        runs=re.findall(rb'[\x20-\x7e]{5,}',data)
        strs=[r.decode('latin-1') for r in runs]
        out['jni_symbols']=[s for s in strs if 'Java_' in s][:80]
        urls=set()
        for s in strs:
            for u in re.findall(r'https?://[^\s\x00-\x1f"\x27]+',s): urls.add(u)
        out['urls']=list(urls)[:40]
        exp=out.get('exported_functions') or []
        imp=out.get('imported_functions') or []
        out['has_jni_onload']='JNI_OnLoad' in exp
        out['has_register_natives']='RegisterNatives' in imp
    else:
        out['format']='unknown (not ELF)'
        out['head_hex']=head.hex()
    with open(report,'w') as f: json.dump(out,f,ensure_ascii=False,indent=2)
    print('SUMMARY')
    print('file: '+inp+' | format: '+str(out.get('format'))+' | arch: '+str(out.get('arch'))+' '+str(out.get('bits'))+'bit '+str(out.get('endian')))
    if 'sections' in out: print('sections: '+str(len(out['sections'])))
    if 'imported_functions' in out: print('imported_functions: '+str(len(out['imported_functions'])))
    if 'exported_functions' in out: print('exported_functions: '+str(len(out['exported_functions'])))
    print('has_jni_onload: '+str(out.get('has_jni_onload'))+' | has_register_natives: '+str(out.get('has_register_natives')))
    print('jni_symbols: '+str(len(out.get('jni_symbols',[])))+' | urls: '+str(len(out.get('urls',[]))))
    print('lief: '+str(out.get('lief')))
    print('report: '+report)
except Exception as e:
    print('ERROR: '+str(e))
"""

    private const val REVERSE_DISASM_PY = """
import sys, json, struct
inp="__INPUT__"
report="__REPORT__"
off=int("__OFFSET__",0)
count=int("__COUNT__")
try:
    with open(inp,'rb') as f: data=f.read()
    import capstone
    order=[(capstone.CS_ARCH_ARM64,capstone.CS_MODE_ARM),(capstone.CS_ARCH_ARM,capstone.CS_MODE_THUMB),(capstone.CS_ARCH_X86,capstone.CS_MODE_64),(capstone.CS_ARCH_X86,capstone.CS_MODE_32)]
    arch_pref=[]
    if data[:4]==b'\x7fELF':
        machine=struct.unpack_from('<H',data,18)[0]
        if machine==0xB7: arch_pref=[(capstone.CS_ARCH_ARM64,capstone.CS_MODE_ARM)]
        elif machine==0x28: arch_pref=[(capstone.CS_ARCH_ARM,capstone.CS_MODE_THUMB)]
        elif machine==0x3E: arch_pref=[(capstone.CS_ARCH_X86,capstone.CS_MODE_64)]
        elif machine==0x03: arch_pref=[(capstone.CS_ARCH_X86,capstone.CS_MODE_32)]
    arch_pref+=order
    md=None
    blob=data[off:off+count*16+0x2000]
    for a,m in arch_pref:
        try:
            md=capstone.Cs(a,m); md.detail=True; break
        except Exception: continue
    if md is None:
        print('ERROR: capstone 无法初始化任一架构')
    else:
        insns=[]
        for i in md.disasm(blob,off):
            insns.append({'addr':hex(i.address),'mnemonic':i.mnemonic,'op_str':i.op_str})
            if len(insns)>=count: break
        out={'file':inp,'offset':hex(off),'count':len(insns),'arch':str(arch_pref[0]),'insns':insns}
        with open(report,'w') as f: json.dump(out,f,ensure_ascii=False,indent=2)
        print('SUMMARY')
        print('file: '+inp+' | offset: '+hex(off)+' | arch_pref_first: '+str(arch_pref[0])+' | disassembled: '+str(len(insns))+' instructions')
        print('report: '+report)
        for x in insns[:80]:
            print(x['addr']+'  '+x['mnemonic']+'  '+x['op_str'])
except Exception as e:
    print('ERROR: '+str(e))
"""

    private suspend fun doReverseStrings(ctx: Context, userId: Long, call: AIToolCall): String {
        val rawPath = argString(call, "path") ?: return toolErr("缺少 path 参数(待扫描文件的工作区相对路径)")
        if (!isSafeRel(ctx, userId, rawPath)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        val root = agentRoot(ctx)
        val file = sandboxFile(root, rawPath)
        if (!file.exists() || !file.isFile) return toolErr("文件不存在:$rawPath")
        val minlen = argInt(call, "min_length", 4).coerceIn(1, 64)
        val reportFile = File(root, "reverse_reports/rx_${System.currentTimeMillis()}_strings.json")
        reportFile.parentFile?.mkdirs()
        val code = REVERSE_STRINGS_PY
            .replace("__INPUT__", JSONObject.quote(file.absolutePath))
            .replace("__REPORT__", JSONObject.quote(reportFile.absolutePath))
            .replace("__MINLEN__", minlen.toString())
        return execPythonScript(ctx, userId, code, 60)
    }

    private suspend fun doReverseDex(ctx: Context, userId: Long, call: AIToolCall): String {
        val rawPath = argString(call, "path") ?: return toolErr("缺少 path 参数(APK 的工作区相对路径)")
        if (!isSafeRel(ctx, userId, rawPath)) return toolErr("非法路径")
        val root = agentRoot(ctx)
        val file = sandboxFile(root, rawPath)
        if (!file.exists() || !file.isFile) return toolErr("文件不存在:$rawPath")
        if (!rawPath.endsWith(".apk", ignoreCase = true)) return toolErr("reverse_dex 仅支持 APK 文件:$rawPath")
        val reportFile = File(root, "reverse_reports/rx_${System.currentTimeMillis()}_dex.json")
        reportFile.parentFile?.mkdirs()
        val code = REVERSE_DEX_PY
            .replace("__INPUT__", JSONObject.quote(file.absolutePath))
            .replace("__REPORT__", JSONObject.quote(reportFile.absolutePath))
        return execPythonScript(ctx, userId, code, 90)
    }

    private suspend fun doReverseNative(ctx: Context, userId: Long, call: AIToolCall): String {
        val rawPath = argString(call, "path") ?: return toolErr("缺少 path 参数(.so/ELF 的工作区相对路径)")
        if (!isSafeRel(ctx, userId, rawPath)) return toolErr("非法路径")
        val root = agentRoot(ctx)
        val file = sandboxFile(root, rawPath)
        if (!file.exists() || !file.isFile) return toolErr("文件不存在:$rawPath")
        val reportFile = File(root, "reverse_reports/rx_${System.currentTimeMillis()}_native.json")
        reportFile.parentFile?.mkdirs()
        val code = REVERSE_NATIVE_PY
            .replace("__INPUT__", JSONObject.quote(file.absolutePath))
            .replace("__REPORT__", JSONObject.quote(reportFile.absolutePath))
        return execPythonScript(ctx, userId, code, 60)
    }

    private suspend fun doReverseDisasm(ctx: Context, userId: Long, call: AIToolCall): String {
        val rawPath = argString(call, "path") ?: return toolErr("缺少 path 参数(待反汇编文件的工作区相对路径)")
        if (!isSafeRel(ctx, userId, rawPath)) return toolErr("非法路径")
        val root = agentRoot(ctx)
        val file = sandboxFile(root, rawPath)
        if (!file.exists() || !file.isFile) return toolErr("文件不存在:$rawPath")
        val off = argString(call, "offset") ?: "0"
        val count = argInt(call, "count", 64).coerceIn(1, 2000)
        val reportFile = File(root, "reverse_reports/rx_${System.currentTimeMillis()}_disasm.json")
        reportFile.parentFile?.mkdirs()
        val code = REVERSE_DISASM_PY
            .replace("__INPUT__", JSONObject.quote(file.absolutePath))
            .replace("__REPORT__", JSONObject.quote(reportFile.absolutePath))
            .replace("__OFFSET__", off)
            .replace("__COUNT__", count.toString())
        return execPythonScript(ctx, userId, code, 60)
    }

    // ===== 娱乐类工具参数解析(从调用方传入的完整参数 JSON 读取;无则回退 path/content 字段) =====
    private fun agentArgs(call: AIToolCall): JSONObject =
        try { if (call.argsJson.isNotBlank()) JSONObject(call.argsJson) else JSONObject() } catch (_: Exception) { JSONObject() }

    private fun argString(call: AIToolCall, key: String): String? {
        val o = agentArgs(call)
        if (o.has(key) && !o.isNull(key)) return o.optString(key)
        return when (key) {
            "path" -> call.path
            "content", "text", "expr" -> call.content.ifBlank { call.path }
            else -> null
        }
    }
    private fun argLong(call: AIToolCall, key: String, def: Long): Long =
        try { argString(call, key)?.trim()?.toLong() ?: def } catch (_: Exception) { def }
    private fun argInt(call: AIToolCall, key: String, def: Int): Int =
        try { argString(call, key)?.trim()?.toInt() ?: def } catch (_: Exception) { def }

    /** 布尔参数:模型常传字符串 true/false,也允许 1/0、是/否、开/关。 */
    private fun argBool(call: AIToolCall, key: String, def: Boolean): Boolean {
        val s = argString(call, key)?.trim()?.lowercase() ?: return def
        return when (s) {
            "true", "1", "yes", "y", "on", "是", "开", "需要", "启用" -> true
            "false", "0", "no", "n", "off", "否", "关", "不需要", "禁用" -> false
            else -> def
        }
    }

    /** 枚举参数归一:同时接受中文与英文写法(如「横屏」「landscape」),统一映射到内部取值。 */
    private fun normalizeEnum(raw: String?, vararg pairs: Pair<String, String>): String? {
        val s = raw?.trim()?.lowercase()?.replace(" ", "") ?: return null
        if (s.isBlank()) return null
        for ((k, v) in pairs) if (s == k.lowercase()) return v
        return null
    }



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
    private suspend fun doGenerateMedia(ctx: Context, userId: Long, call: AIToolCall): String {
        val args = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val type = args.optString("type", "").trim().lowercase()
        var prompt = args.optString("prompt", "").trim()
        if (prompt.isBlank()) prompt = call.content.trim().ifBlank { call.path.trim() }
        if (type != "image" && type != "video") return toolErr("generate_media 的 type 参数必须为 image 或 video(当前:$type)")
        if (prompt.isBlank()) return toolErr("generate_media 缺少 prompt 参数(用户想生成的内容描述)")
        val keep = args.optBoolean("keep", false) // true 时把生成结果额外落工作区 ai_files,供后续 list_files/get_file 等工具处理(默认 false)
        // agnes-image-2.5-flash 新参数：宽高比(白名单校验) + 图生图参考图(字符串或数组,上限 4 张)
        val ratio = args.optString("ratio", "").trim()
        if (ratio.isNotBlank() && ratio !in IMAGE_RATIOS) {
            return toolErr("generate_media 的 ratio 参数不合法(当前:$ratio;可用:${IMAGE_RATIOS.joinToString(" / ")})")
        }
        val images = mutableListOf<String>()
        when (val rawImg = args.opt("image")) {
            is String -> if (rawImg.isNotBlank()) images.add(rawImg.trim())
            is org.json.JSONArray -> for (i in 0 until rawImg.length()) {
                rawImg.optString(i, "").trim().takeIf { it.isNotBlank() }?.let { images.add(it) }
            }
        }
        if (images.size > MAX_REFERENCE_IMAGES) {
            return toolErr("generate_media 的 image 参考图最多 $MAX_REFERENCE_IMAGES 张(多图合成),当前 ${images.size} 张")
        }
        return try {
            val size = args.optString("size", "1024x1024").trim().ifBlank { "1024x1024" }
            com.aurora.chat.ErrorReporter.debug("AiGen", "generate_media type=$type size=$size ratio=$ratio refs=${images.size} prompt=${prompt.take(160)}")
            val res = if (type == "video") generateVideo(ctx, userId, prompt) else generateImage(ctx, userId, prompt, size, ratio, images)
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
    //  后台常驻服务（沙盒里跑「不退出的服务」）
    //  与 execute_python 的分工：execute_python 是「跑完就退」的一次性任务；
    //  本组工具起的是常驻进程，用于本地后端 / API 服务，配合反向代理给网页调用。
    // ════════════════════════════════════════════════════════════════════

    // 运行环境已移除：保留函数签名以维持工具执行器骨架。
    private suspend fun doServeStart(ctx: Context, call: AIToolCall): String {
        return toolErr("运行环境已移除")
    }

    // 运行环境已移除：保留函数签名以维持工具执行器骨架。
    private suspend fun doServeStop(ctx: Context, call: AIToolCall): String {
        return toolErr("运行环境已移除")
    }

    // 运行环境已移除：保留函数签名以维持工具执行器骨架。
    private suspend fun doServeList(ctx: Context): String {
        return toolErr("运行环境已移除")
    }

    // 运行环境已移除：保留函数签名以维持工具执行器骨架。
    private suspend fun doServeLog(ctx: Context, call: AIToolCall): String {
        return toolErr("运行环境已移除")
    }

    /** 执行一个工具调用,返回结果 JSON 字符串;整个过程在 IO 调度器上运行,内部捕获异常不会抛出 */
    suspend fun executeAITool(ctx: Context, userId: Long, call: AIToolCall, appActionHandler: (suspend (AppAction) -> AppActionResult)? = null): String {
        // 当前 AI 身份(协程上下文携带;非派发/嵌套场景为 null → 下面的并行与准入机制全部自动失效)
        val principal = kotlin.coroutines.coroutineContext[AIPrincipal]
        val agentId = principal?.id
        // 【子 AI 工具准入·执行层硬拦截】规划者只能只读、子执行者不能碰元工具与独占型人机工具。
        // 主手段是把这些工具从它的 schema 里裁掉(见 toolSchemaFor),这里是**兜底**——
        // 模型仍可能用文本协议硬塞一个工具名进来,少了这层就会出现"递归 fork / 嵌套验收"这类失控。
        if (principal != null) {
            AIRoles.admit(principal.role, principal.depth, call.tool).toToolResult()?.let { denied ->
                com.aurora.chat.ModelLogger.warn(
                    ctx, "MULTI",
                    "工具准入拒绝 | ${principal.describe()} 请求 ${call.tool}"
                )
                return denied
            }
        }
        // 并行写入冲突预检:目标路径已被另一个同时在跑的 AI 占用 → 直接拒绝,把静默覆盖变成显式失败
        AISharedWorkspace.beginTool(call.tool, call.argsJson, agentId)?.let { return it }
        // 【Plan 模式·执行层硬拦截】用户切到 Plan = 要"先看方案、点了「构建」才动手"这个保证。
        // 只靠提示词会被模型自我说服跳过 → 这里确定性拒绝改动类工具，并引导它先出方案卡片。
        // 用户点「构建」后会自动 set_agent_mode(craft)，那时这些工具自然放开，不会卡死。
        if (getAIMode(ctx, userId) == MODE_PLAN && call.tool in PLAN_MODE_BLOCKED_TOOLS) {
            return toolErr("Plan 模式下「${call.tool}」属于改动类操作，已被拦截。")
        }
        // 【自我保护】逆向 / 反编译 / 拆包 / 安装 / 执行类工具：先确认目标不是"本应用自己"。
        // 用户会把各种 APK 丢给 AI 逆向，里面完全可能混进本应用自己的 APK 或它的改包 ——
        // 真去拆等于让 AI 分析自家产品。提示词拦不住（模型会自我说服跳过），所以在工具层做确定性判定：
        // 命中 DIRECT 时工具根本不执行，分析结果也不会产生。
        if (call.tool in SELF_GUARD_TOOLS) {
            when (SelfGuard.evaluateCall(ctx, agentRoot(ctx), call.argsJson)) {
                SelfGuard.Match.DIRECT -> {
                    com.aurora.chat.ModelLogger.warn(ctx, "SELFGUARD", "拦截逆向/拆包目标:确认是本应用自身 | 工具=${call.tool}")
                    return toolErr(SelfGuard.REFUSAL_TEXT)
                }
                SelfGuard.Match.SIMILAR -> {
                    com.aurora.chat.ModelLogger.warn(ctx, "SELFGUARD", "目标与本应用特征相似,要求先核实包名与签名 | 工具=${call.tool}")
                    return toolErr(SelfGuard.similarText(ctx))
                }
                SelfGuard.Match.NONE -> {}
            }
        }
        val raw = withContext(Dispatchers.IO) {
        try {
            val root = agentRoot(ctx)
            val rel = resolveAIPath(ctx, userId, root, call.path)
            when (call.tool) {
                "list_files" -> doListFiles(ctx, userId, root, rel)
                "read_file" -> doReadFile(ctx, userId, root, rel, call.argsJson)
                "write_file" -> doWriteFile(ctx, userId, root, rel, call.content)
                "edit_file" -> doEditFile(ctx, userId, root, call)
                "delete_file" -> doDeleteFile(ctx, userId, root, rel)
                "restore_file" -> doRestoreFile(ctx, userId, root, call)
                "create_folder" -> doCreateFolder(ctx, userId, root, rel)
                "delete_folder" -> doDeleteFolder(ctx, userId, root, rel)
                "rename_file" -> doRenameFile(ctx, userId, root, call)
                "copy_file" -> doCopyFile(ctx, userId, root, call)
                "append_file" -> doAppendFile(ctx, userId, root, rel, call.content)
                "stat_file" -> doStatFile(ctx, userId, root, rel)
                "find_files" -> doFindFiles(ctx, userId, root, call)
                "search_in_files" -> doSearchInFiles(ctx, userId, root, call)
                "extract_archive" -> doExtractArchive(ctx, userId, root, call)
                "move_dir_contents" -> doMoveDirContents(ctx, userId, root, call)
                TOOL_COMPRESS_ARCHIVE -> doCompressArchive(ctx, userId, root, call)
                TOOL_ARCHIVE_LIST -> doArchiveList(ctx, userId, root, call)
                TOOL_ARCHIVE_READ -> doArchiveRead(ctx, userId, root, call)
                TOOL_KILL_PROCESS -> doKillProcess(ctx, userId, call)
                TOOL_STOP_APP -> doStopApp(ctx, userId, call)
                TOOL_UPLOAD_FILE -> doUploadFile(ctx, userId, root, call)
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
                "shutdown_device" -> doPowerCommand("reboot -p")
                "reboot_device" -> doPowerCommand("reboot")
                "get_file" -> doGetFile(ctx, userId, root, rel, argsOf(call).optBoolean("as_file", false))
                "generate_media" -> doGenerateMedia(ctx, userId, call)
                "update_plan_step" -> {
                    val a = try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                    val idx = a.optInt("step_index", -1)
                    val st = a.optString("status", "").trim().lowercase()
                    if (idx < 0) toolErr("update_plan_step 失败:step_index 必填(>=1)")
                    else if (st !in setOf("pending", "doing", "done")) toolErr("update_plan_step 失败:status 必须是 pending/doing/done")
                    else toolOk(JSONObject().put("step_index", idx).put("status", st))
                }
                TOOL_EXECUTE_PYTHON -> doExecutePython(ctx, userId, call)
                TOOL_RUN_SCRIPT -> doRunScript(ctx, userId, call)
                TOOL_FETCH_URL -> doFetchUrl(ctx, userId, call)
                TOOL_SHELL_EXEC -> doShellExec(ctx, userId, call)
                TOOL_RUN_ADB -> doRunAdb(ctx, userId, call)
                TOOL_HTTP_REQUEST -> doHttpRequest(ctx, userId, call)
                TOOL_PROCESS_LIST -> doProcessList(ctx, userId, call)
                TOOL_PING -> doPing(ctx, userId, call)
                TOOL_SEARCH_WEB -> doSearchWeb(ctx, userId, call)
                TOOL_DEEP_RESEARCH -> doDeepResearch(ctx, userId, call)
                TOOL_AGENT_REVIEW -> doAIReview(ctx, userId, call)
                TOOL_AGENT_PLAN -> doAIPlan(ctx, userId, call)
                TOOL_AGENT_FORK -> doAIFork(ctx, userId, call)
                TOOL_ASK_USER -> doAskUser(ctx, userId, call)
                TOOL_PLUGIN_SUBMIT -> doPluginSubmit(ctx, call)
                TOOL_PLUGIN_LIST -> doPluginList(ctx, userId, call)
                TOOL_PLUGIN_CALL -> doPluginCall(ctx, userId, call)
                TOOL_GIT -> doGit(ctx, userId, call)
                TOOL_DIFF -> doDiff(ctx, userId, call)
        TOOL_DOWNLOAD_FILE -> doDownloadFile(ctx, userId, call)
        TOOL_INSTALL_APK -> doInstallApk(ctx, userId, call)
        TOOL_GET_CURRENT_LOCATION -> doGetCurrentLocation(ctx, userId, call)
        TOOL_OPEN_FRONT_CAMERA -> doOpenCamera(ctx, userId, call, front = true)
        TOOL_OPEN_REAR_CAMERA -> doOpenCamera(ctx, userId, call, front = false)
        TOOL_SET_VOLUME -> doSetVolume(ctx, userId, call)
        TOOL_PLAY_AUDIO -> doPlayAudio(ctx, userId, root, call)
        TOOL_PLAY_VIDEO -> doPlayVideo(ctx, userId, root, call)
        TOOL_VIBRATE -> doVibrate(ctx, userId, call)
        TOOL_SET_BRIGHTNESS -> doSetBrightness(ctx, userId, call)
        TOOL_KEEP_SCREEN_ON -> doKeepScreenOn(ctx, userId, call)
        TOOL_SET_SCREEN_ORIENTATION -> doSetScreenOrientation(ctx, userId, call)
        TOOL_SET_BLUETOOTH -> doSetBluetooth(ctx, userId, call)
        TOOL_SET_GPS -> doSetGps(ctx, userId, call)
        TOOL_SET_WIFI -> doSetWifi(ctx, userId, call)
        TOOL_SET_NFC -> doSetNfc(ctx, userId, call)
        TOOL_SET_FLASHLIGHT -> doSetFlashlight(ctx, userId, call)
        TOOL_BATTERY -> doBattery(ctx, userId, call)
        TOOL_SET_ALARM -> doSetAlarm(ctx, call)
        TOOL_CANCEL_ALARM -> doCancelAlarm(ctx, call)
        TOOL_LIST_ALARMS -> doListAlarms(ctx)
                TOOL_FTP_TRANSFER -> doFtpTransfer(ctx, userId, root, call)
                TOOL_SWITCH_TAB -> doSwitchTab(call, appActionHandler)
                TOOL_SEND_MESSAGE -> doSendMessage(call)
                TOOL_SEND_FILE -> doSendFile(ctx, userId, root, rel, call)
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
                            setAIMode(ctx, userId, mode)
                            toolOk(JSONObject().put("mode", mode).put("message", "已切换为 $mode 模式"))
                        }
                        else -> toolErr("非法模式:$mode(可选 ask / craft / plan)")
                    }
                }
                TOOL_REVERSE_STRINGS -> doReverseStrings(ctx, userId, call)
                TOOL_REVERSE_DEX -> doReverseDex(ctx, userId, call)
                TOOL_REVERSE_NATIVE -> doReverseNative(ctx, userId, call)
                TOOL_REVERSE_DISASM -> doReverseDisasm(ctx, userId, call)
                TOOL_SERVE_START -> doServeStart(ctx, call)
                TOOL_SERVE_STOP -> doServeStop(ctx, call)
                TOOL_SERVE_LIST -> doServeList(ctx)
                TOOL_SERVE_LOG -> doServeLog(ctx, call)
                else -> toolErr("未知工具:${call.tool}(可用:list_files/read_file/write_file/delete_file/create_folder/delete_folder/rename_file/copy_file/append_file/stat_file/find_files/query_environment/shutdown_device/reboot_device/get_file/execute_python/fetch_webpage/shell_exec/run_adb/http_request/process_list/ping_host/search_web/git_command/diff_text/download_file/install_apk/switch_tab/send_message/send_file/send_friend_request/create_community_post/list_my_contacts/list_my_groups/batch_loop/json_process/regex_test/encode_convert/hash_digest/timestamp_convert/text_stats/size_convert/take_screenshot/capture_screen/open_app/exit_app/uninstall_app/wait/keep_alive/set_agent_mode/ftp_transfer/edit_file/restore_file/reverse_strings/reverse_dex/reverse_native/reverse_disasm/serve_start/serve_stop/serve_list/serve_log)")
            }
        } catch (e: Exception) {
            toolErr("执行异常:${e.message ?: e.javaClass.simpleName}")
        }
        }
        // 失败则释放刚占用的路径(避免一次失败把该路径锁死),并把「其他并行 AI 的改动」附带回去
        AISharedWorkspace.endTool(call.tool, call.argsJson, agentId, raw)
        return AISharedWorkspace.decorate(raw, agentId)
    }

    // ===== App 内部业务工具实现 =====
    private fun argsOf(call: AIToolCall): JSONObject =
        try { JSONObject(call.argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }

    /** batch_loop 内禁止批量调用的工具:有会话/验收副作用,批量执行没有意义甚至有害 */
    private val BATCH_FORBIDDEN_TOOLS = setOf(
        TOOL_BATCH_LOOP, TOOL_AGENT_REVIEW, TOOL_AGENT_FORK, TOOL_AGENT_PLAN, TOOL_ASK_USER, TOOL_DEEP_RESEARCH
    )

    /**
     * batch_loop:批量循环执行同一个工具 N 次。
     * args 模板里任意字符串值的 {i} 会被替换为当前循环序号(支持 {i:03d} 补零),
     * 每次迭代在内部构造 AIToolCall 递归走 executeAITool——外层只占 1 个工具轮次。
     * 连续失败 5 次自动中止;stop_on_error=true 时首次失败即停。结果逐条回报(超 200 条只留前 200 条明细)。
     */
    private suspend fun doBatchLoop(
        ctx: Context, userId: Long, call: AIToolCall,
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
            val innerCall = AIToolCall(innerTool, innerPath, innerContent, innerArgs.toString())
            val result = executeAITool(ctx, userId, innerCall, appActionHandler)
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
    private suspend fun doSwitchTab(call: AIToolCall, appActionHandler: (suspend (AppAction) -> AppActionResult)?): String {
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
    private suspend fun doOpenApp(ctx: Context, call: AIToolCall): String {
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
    private suspend fun doExitApp(ctx: Context, call: AIToolCall): String {
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
    private suspend fun doUninstallApp(ctx: Context, userId: Long, call: AIToolCall): String {
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
    private suspend fun doWait(call: AIToolCall): String {
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
    private suspend fun doKeepAlive(ctx: Context, call: AIToolCall): String {
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

    private suspend fun doPhoneScreen(call: AIToolCall): String {
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

    private suspend fun doPhoneTap(call: AIToolCall): String {
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

    private suspend fun doPhoneSwipe(call: AIToolCall): String {
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

    private suspend fun doPhoneType(call: AIToolCall): String {
        if (!com.aurora.chat.PhoneControl.isReady()) return toolErr(com.aurora.chat.PhoneControl.NOT_READY)
        val text = argsOf(call).optString("text", "")
        if (text.isEmpty()) return toolErr("请提供要写入的 text")
        val hit = com.aurora.chat.PhoneControl.setText(text)
            ?: return toolErr("没有找到可写入的输入框:请先用 phone_tap 点一下目标输入框,再调用本工具")
        return toolOk(JSONObject().put("length", text.length)
            .put("message", "已写入 ${text.length} 个字符到输入框(${hit.optInt("x")}, ${hit.optInt("y")})"))
    }

    private suspend fun doPhoneKey(call: AIToolCall): String {
        if (!com.aurora.chat.PhoneControl.isReady()) return toolErr(com.aurora.chat.PhoneControl.NOT_READY)
        val key = argsOf(call).optString("key", "").trim()
        if (key.isEmpty()) return toolErr("请提供 key(back / home / recents / notifications / quick_settings)")
        if (!com.aurora.chat.PhoneControl.globalAction(key)) return toolErr("按键「$key」无效或未被系统接受")
        return toolOk(JSONObject().put("key", key).put("message", "已发送按键:$key"))
    }

    /** 以当前用户身份给好友(私聊)或群(群聊)发消息。 */
    private suspend fun doSendMessage(call: AIToolCall): String {
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
     * send_file:把工作区里的一个文件作为「消息」发送给好友(私聊)或群(群聊)——替用户把文件/图片/视频真正发给【别人】。
     * 与 get_file 的区别:get_file 把文件发给【当前用户自己】看,本工具发给【聊天对象】。
     * 复用与手动发文件完全相同的链路,保证行为一致、渲染一致:
     *   读工作区文件 → AuroraApi.uploadChatMediaFile 上传换 mediaUrl → sendMessage/sendGroupMessage 带 mediaType+mediaUrl 落一条消息。
     * 安全:路径仍走 resolveAIPath/sandboxFile 同一套沙盒消毒(调用方已解析为 rel),越界/不存在/空文件/超限一律拒绝,不抛异常。
     */
    private suspend fun doSendFile(ctx: Context, userId: Long, root: File, rel: String, call: AIToolCall): String {
        val args = argsOf(call)
        val targetType = args.optString("target_type", "").trim().lowercase()
        val targetId = args.optLong("target_id", 0)
        val caption = args.optString("caption", "").trim()
        val asFile = args.optBoolean("as_file", false)
        // 目标校验(与 send_message 同一套规则,普通用户只能以自己身份发)
        if (targetType !in setOf("friend", "group")) return toolErr("target_type 只能是 friend(私聊) 或 group(群聊)")
        if (targetId == 0L) return toolErr("target_id 非法")
        if (targetType == "group" && targetId > 0) return toolErr("群聊的 target_id 必须为负数群会话 ID(如官方群 -1001)")
        if (targetType == "friend" && targetId < 0) return toolErr("私聊的 target_id 必须为好友用户 ID(正数)")
        if (targetType == "friend" && targetId == userId) return toolErr("target_id 是你自己,不能给自己发文件")
        // 文件校验:路径消毒 + 存在性 + 非空 + 大小
        if (!isSafeRel(ctx, userId, rel)) return toolErr("非法路径:沙盒内禁止使用 \"..\" 或绝对路径越界")
        if (rel.isBlank()) return toolErr("缺少文件路径 path(工作区相对路径,如 out/report.pdf)")
        val f = sandboxFile(root, rel)
        if (!f.exists() || !f.isFile) return toolErr("文件不存在:$rel")
        if (f.length() <= 0L) return toolErr("文件是空的($rel),无法发送")
        if (f.length() > 100L * 1024 * 1024) return toolErr("文件过大(${formatFileSizeForTool(f.length())}),超出 100MB 上传上限")
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val mime = mimeForExt(ext)
        val isImage = mime.startsWith("image/")
        val isVideo = mime.startsWith("video/")
        return try {
            val up = AuroraApi.uploadChatMediaFile(f, f.name, mime)
            val data = up.data
            if (!up.success || data == null) return toolErr("上传失败:${up.message}")
            val rawUrl = data.optString("url", "")
            if (rawUrl.isBlank()) return toolErr("上传失败:服务器未返回文件地址")
            val fullUrl = resolveMediaUrl(rawUrl)
            // 媒体类型:优先服务端测定值(jpg/png/gif/mp4/file,与 UI 渲染约定一致);为空时按扩展名兜底。
            var mt = data.optString("media_type", "").trim()
            if (mt.isBlank()) mt = when { isImage -> "image"; isVideo -> "video"; else -> "file" }
            if (asFile) mt = "file" // 显式要求文件卡片:覆盖自动分流
            val res = if (targetType == "group") AuroraApi.sendGroupMessage(targetId, caption, mediaType = mt, mediaUrl = fullUrl)
                      else AuroraApi.sendMessage(targetId, caption, mediaType = mt, mediaUrl = fullUrl)
            if (!res.success) return toolErr(res.message)
            val msgId = res.data ?: 0L
            // 记录本机发送的真实媒体类型:后端保存文件类消息时可能统一标成 jpg,读取时据此覆盖,避免误显示
            try { if (msgId > 0L) com.aurora.chat.data.local.LocalMediaTypeStore.put(msgId, mt) } catch (_: Exception) {}
            val kind = if (isImage && !asFile) "图片" else if (isVideo && !asFile) "视频" else "文件卡片"
            toolOk(JSONObject()
                .put("target_type", targetType).put("target_id", targetId)
                .put("message_id", msgId).put("name", f.name).put("media_type", mt).put("size", f.length())
                .put("message", "已把「${f.name}」作为${kind}发送给${if (targetType == "group") "群(ID=$targetId)" else "好友(ID=$targetId)"}"))
        } catch (e: Exception) {
            toolErr("发送文件失败:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 由文件扩展名推断 MIME(供上传时声明 Content-Type;服务端还会用 magic bytes 复核真实类型)。 */
    private fun mimeForExt(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp", "3gpp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    /**
     * 撤回消息:权限规则由服务端 /api/messages/recall 强制执行(与手动撤回完全同一条链路):
     * - 开发者(ID=1):可撤回任何人的消息,不限时间;
     * - 普通用户:只能撤回自己发的消息,且发出后 2 分钟内,超时服务端返回「消息已超过2分钟,无法撤回」。
     * 客户端不做二次判定,直接透传服务端结果,避免双端规则漂移。
     */
    /** 查看会话最近消息(含 message_id):撤回前定位目标消息的主要手段。
     *  服务端 /api/messages 按 id 升序 LIMIT/OFFSET 分页(从最旧开始),故拉大窗口后取尾部 count 条。 */
    private suspend fun doListRecentMessages(userId: Long, call: AIToolCall): String {
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

    private suspend fun doRecallMessage(call: AIToolCall): String {
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
    private suspend fun doSendFriendRequest(call: AIToolCall): String {
        val args = argsOf(call)
        val userId = args.optLong("user_id", 0)
        if (userId <= 0) return toolErr("user_id 非法(须为正整数)")
        return try {
            // 走仓库层：AI 查过的用户也进全局资料缓存
            val infoRes = com.aurora.chat.data.repository.ChatRepository.getUserInfo(userId)
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
    private suspend fun doCreatePost(call: AIToolCall): String {
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
    private fun doJsonProcess(call: AIToolCall): String {
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
    private fun doRegexTest(call: AIToolCall): String {
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
    private fun doEncodeConvert(call: AIToolCall): String {
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
    private fun doHashDigest(call: AIToolCall): String {
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
    private fun doTimestampConvert(call: AIToolCall): String {
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
    private fun doTextStats(call: AIToolCall): String {
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
    private fun doSizeConvert(call: AIToolCall): String {
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
    private suspend fun doTakeScreenshot(ctx: Context, userId: Long, call: AIToolCall): String {
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
    private suspend fun doCaptureScreen(ctx: Context, userId: Long, call: AIToolCall): String {
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

    // ===== AI 工具轮询循环(复用:普通发送 / 继续 都走此共享循环;确认发言另用内联实现) =====

    /** 上游单次调用的用量明细(计费/用量统计)。上游未提供对应字段时值为 0 */
    data class AiUsageDetail(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cacheHitTokens: Long = 0,
        val contextLength: Long = 0
    )

    /**
     * 多轮用量累加:AI 每一轮工具循环都是一次真实上游调用(上游按完整上下文逐轮计费),
     * 用量必须逐轮相加。只记「最后一轮」会系统性少扣(长任务按轮数成比例少收,平台直接亏损)。
     * contextLength 取最后一轮——它表示「当时上下文有多长」,累加没有意义。
     */
    fun accumulateUsage(prev: AiUsageDetail?, cur: AiUsageDetail?): AiUsageDetail? {
        if (cur == null) return prev
        return AiUsageDetail(
            inputTokens = (prev?.inputTokens ?: 0L) + cur.inputTokens,
            outputTokens = (prev?.outputTokens ?: 0L) + cur.outputTokens,
            cacheHitTokens = (prev?.cacheHitTokens ?: 0L) + cur.cacheHitTokens,
            contextLength = cur.contextLength
        )
    }

    /** 单轮流式调用的结果(由 round lambda 返回) */
    data class AIRoundResult(
        val done: Boolean,
        val fullText: String = "",
        val usage: Long = 0,
        val thinkingSeconds: Long = 0,
        val totalSeconds: Long = 0,
        /** 本轮用量明细(输入/输出 token、缓存命中、上下文长度);上游未提供时为 null */
        val usageDetail: AiUsageDetail? = null,
        /** 本轮产生的思考片段(reasoning_content),供共享循环跨轮累积为多段思考 */
        val reasoning: String = "",
        /** 上游结束原因(stop/length/tool_calls):length 表示输出被截断,编排层据此自动续写 */
        val finishReason: String = "",
        /** 本轮是否因可重试错误而失败(网络/5xx/断流),编排层据此原地重试而非终结任务 */
        val retriable: Boolean = false,
        /** 本轮错误原文(供上下文溢出识别等精细分支使用;成功时为空) */
        val errorText: String = ""
    )

    /** 整个工具循环的最终结果 */
    data class AILoopOutcome(
        /** 多轮累积后的纯对话文本(已剥离工具块,界面最终展示用) */
        val accumulated: String,
        /** 实际执行过的工具轮数 */
        val toolRounds: Int,
        /** 是否正常收尾(未被手动停止 / 出错打断) */
        val clean: Boolean,
        val usage: Long,
        val thinkingSeconds: Long,
        val totalSeconds: Long,
        /** 多轮累积后的用量明细(各轮 input/output 相加;contextLength 取最后一轮;供计费与用量界面展示) */
        val usageDetail: AiUsageDetail? = null,
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
        val op: String,                 // created / modified / deleted / renamed / copied / deleted_folder / download / extract / compress / moved_dir / restored
        val fromPath: String = "",
        val oldText: String = "",
        val newText: String = "",
        /** 目录类操作的子项清单(JSON 字符串,空表示无):[{path,size,isDir},...],供「查看改动」展开文件夹内容。
         *  deleted_folder=删除前目录内容;extract=解压出的文件;compress=被打包的文件;moved_dir=被搬移的子项。 */
        val children: String = "",
        /** 该文件改动是否已被用户「退回」:true 后界面禁用退回按钮,并持久化回消息 JSON 防止重进丢失 */
        val reverted: Boolean = false,
        /** 文件大小(字节)。deleted 删除二进制/超大文件时 oldText 为空,靠 size 区分「有内容可展示」与「无文本」。 */
        val size: Long = 0L,
        /** 删除前备份路径(绝对路径)。deleted=单文件备份;deleted_folder=整目录备份根(供颗粒化退回)。空表示未备份。 */
        val backup: String = ""
    ) {
        fun toJson() = JSONObject().apply {
            put("path", path); put("op", op)
            put("from", fromPath); put("old", oldText); put("new", newText)
            put("children", children)
            put("reverted", reverted)
            put("size", size)
            put("backup", backup)
        }
        companion object {
            fun fromJson(o: JSONObject) = FileChange(
                o.optString("path", ""), o.optString("op", "modified"),
                o.optString("from", ""), o.optString("old", ""), o.optString("new", ""),
                o.optString("children", ""),
                o.optBoolean("reverted", false),
                o.optLong("size", 0L),
                o.optString("backup", "")
            )
        }
    }

    /** old/new 文本截断上限(单文件):超过则仅保留开头,避免 AI 大文件改动撑爆消息 JSON */
    private const val FC_TEXT_CAP = 100_000

    /** 当前正在执行的 AI 会话的文件改动收集器(模块级,带 save/restore 支持嵌套 agent 循环)。
     *  由 runAIChatLoop 在开始处设入、finally 还原;文件类工具成功后在内部追加记录。 */
    internal var activeFileChangeRecorder: MutableList<FileChange>? = null

    /** 追加一条文件改动记录(仅当收集器处于激活态时) */
    private fun recordFileChange(path: String, op: String, fromPath: String = "", oldText: String = "", newText: String = "", children: String = "", size: Long = 0L, backup: String = "") {
        val rec = activeFileChangeRecorder ?: return
        val change = FileChange(path, op, fromPath,
            if (oldText.length > FC_TEXT_CAP) oldText.take(FC_TEXT_CAP) + "\n…(已截断)" else oldText,
            if (newText.length > FC_TEXT_CAP) newText.take(FC_TEXT_CAP) + "\n…(已截断)" else newText,
            children, false, size, backup)
        addChangeSafe(rec, change)
        // 【崩溃可追溯】同步写一条追加式执行日志。
        // 这里是所有文件类工具的唯一漏斗，挂一次就全覆盖；写日志失败绝不影响工具本身（内部全是 try/catch）。
        // 为什么必须在这里而不是"任务收尾时统一写"：进程被 SIGKILL 时收尾代码根本不会执行，
        // 只有"发生即落盘"才能留住那段时间的改动。
        try { AIJournal.change(change) } catch (_: Throwable) {}
    }

    /**
     * 向改动收集器写入的唯一入口(**必须**从这里走)。
     *
     * 为什么要有它:agent_fork 的多个子执行者是**真的并发**在写工作区,它们共用父层那一个改动列表,
     * 而 `MutableList` 的默认实现是 ArrayList —— 多线程并发 add 会直接丢条目甚至内部结构损坏。
     * 单点加锁比"到处记得加锁"可靠;读侧(序列化/收尾)也统一走快照,不在遍历中被并发改。
     */
    private fun addChangeSafe(rec: MutableList<FileChange>, c: FileChange) {
        synchronized(rec) { rec.add(c) }
    }

    /** 改动列表的安全快照:并发场景下避免"遍历中被另一个 AI 写入"导致 ConcurrentModificationException */
    private fun snapshotFileChanges(rec: MutableList<FileChange>): List<FileChange> =
        synchronized(rec) { rec.toList() }

    /** 收集目录内子项清单 JSON(工作区相对路径 + 大小 + 是否目录)。上限 [maxItems] 条,超出截断并标注。
     *  供 deleted_folder/extract/compress/moved_dir 等目录类改动展开「文件夹内容」使用,只记元数据不读正文,开销可控。 */
    private fun collectDirChildrenJson(root: File, dirFile: File, maxItems: Int = 500): String {
        if (!dirFile.isDirectory) return ""
        val arr = JSONArray()
        var count = 0
        try {
            dirFile.walkTopDown().forEach { f ->
                if (count >= maxItems) return@forEach
                if (f == dirFile) return@forEach
                val rel = try { f.relativeTo(root).invariantSeparatorsPath } catch (_: Exception) { return@forEach }
                if (rel.isBlank() || rel.split('/').any { it == ".." }) return@forEach
                val len = try { if (f.isFile) f.length() else 0L } catch (_: Exception) { 0L }
                arr.put(JSONObject().put("path", rel).put("size", len).put("isDir", f.isDirectory))
                count++
            }
        } catch (_: Exception) {}
        if (count >= maxItems) arr.put(JSONObject().put("truncated", true).put("path", "").put("size", 0).put("isDir", false))
        return arr.toString()
    }

    /** 收集若干 File(文件或目录)展开后的子项清单 JSON,格式同 [collectDirChildrenJson]。供 compress 等打包类改动记录被打包的内容。 */
    private fun collectPathsJson(root: File, items: List<File>, maxItems: Int = 500): String {
        if (items.isEmpty()) return ""
        val arr = JSONArray()
        var count = 0
        for (base in items) {
            if (count >= maxItems) break
            if (!base.exists()) continue
            try {
                if (base.isDirectory) {
                    base.walkTopDown().forEach { f ->
                        if (count >= maxItems) return@forEach
                        val rel = try { f.relativeTo(root).invariantSeparatorsPath } catch (_: Exception) { return@forEach }
                        if (rel.isBlank()) return@forEach
                        arr.put(JSONObject().put("path", rel).put("size", try { if (f.isFile) f.length() else 0L } catch (_: Exception) { 0L }).put("isDir", f.isDirectory))
                        count++
                    }
                } else {
                    val rel = try { base.relativeTo(root).invariantSeparatorsPath } catch (_: Exception) { "" }
                    if (rel.isBlank()) continue
                    arr.put(JSONObject().put("path", rel).put("size", base.length()).put("isDir", false))
                    count++
                }
            } catch (_: Exception) {}
        }
        if (count >= maxItems) arr.put(JSONObject().put("truncated", true).put("path", "").put("size", 0).put("isDir", false))
        return arr.toString()
    }

    // ===== 长任务中断优化(P2:主循环 history 的 token 预算裁剪 / 过度探索检测工具分类)=====

    /** 主循环 history 的 token 预算上限(估算值):每轮发送前裁剪,防止多轮工具任务把上下文撑爆 */
    private const val LOOP_HISTORY_TOKEN_BUDGET = 60_000
    /** 上下文溢出后的强裁剪预算(更激进:保留更少近因消息) */
    private const val LOOP_HISTORY_AGGRESSIVE_BUDGET = 30_000
    /** 预算裁剪时保留的「近因完整消息」条数(不压缩最近几轮,保证模型能连贯续跑) */
    private const val LOOP_HISTORY_KEEP_RECENT = 6
    /** 主循环 history 内单条消息 token 折叠阈值(防单条超长工具结果原文撑爆;与 buildChatContext 的 MSG_TOKEN_FOLD_MAX=1500 区分,避免同名遮蔽) */
    private const val LOOP_MSG_TOKEN_FOLD_MAX = 9_000

    // ===== 上下文压缩模式（用户可在 AI 设置中选择）=====
    /** 结构保活（默认·推荐）：保留全部用户指令，仅折叠工具输出冗余与冗长推理。零额外耗时与费用。 */
    const val CONTEXT_COMPRESS_STRUCTURAL = 0
    /** LLM 摘要：调用模型概括旧上下文（可能产生额外耗时与费用）。 */
    const val CONTEXT_COMPRESS_LLM = 1
    fun getContextCompressMode(ctx: Context, userId: Long): Int =
        prefs(ctx, userId).getInt("context_compress_mode", CONTEXT_COMPRESS_STRUCTURAL)
    fun setContextCompressMode(ctx: Context, userId: Long, mode: Int) {
        prefs(ctx, userId).edit().putInt("context_compress_mode", mode).apply()
    }

    /** 只读探索类工具(过度探索检测用):仅查询不产出 */
    private val LOOP_READ_TOOLS = setOf(
        "list_files", "read_file", "find_files", "stat_file", "archive_list", "archive_read_file",
        "search_in_files", "diff_text", "search_web", "deep_research", "fetch_webpage", "query_environment", "search_chunk", "search_file",
        "search_image", "json_process", "regex_test", "hash_digest", "timestamp_convert", "text_stats",
        "size_convert", "qr_decode", "list_my_contacts", "list_my_groups", "list_recent_messages",
        "get_event_kb_info", "kb_events_list", "kb_events_files"
    )

    /** 写/产出类工具(过度探索检测用):有实质产出/改动 */
    private val LOOP_WRITE_TOOLS = setOf(
        "write_file", "edit_file", "append_file", "batch_replace", "apply_patch", "create_folder",
        "rename_file", "copy_file", "delete_file", "delete_folder", "move_dir_contents",
        "extract_archive", "compress_archive", "restore_file", "upload_file", "download_file",
        "get_file", "generate_media", "capture_screen", "work_log", "batch_loop",
        "agent_plan", "agent_fork", "agent_review", "set_agent_mode", "ask_user", "phone_tap",
        "phone_swipe", "phone_type", "send_file"
    )

    /**
     * 是否存在「客观产出证据」:LOOP_WRITE_TOOLS 写/产出类工具中存在成功记录(state==1)。
     * 作为 nudge 触发判据:只要任务型会话已有成功产出(文件写入/下载/解压/发文件等),
     * 即便模型未给结构化交付,也由编排层兜底合成可读总结,而非强制 nudge 模型补 DELIVER 块。
     */
    fun hasObjectiveCompletion(toolCalls: List<ChatMsg.ToolStageRecord>): Boolean =
        toolCalls.any { it.tool in LOOP_WRITE_TOOLS && it.state == 1 }

    /**
     * 对主循环 history(Pair<role,content>)做 token 预算裁剪(长任务循环内上下文管理核心):
     *  - 估算总 token 未超预算 → 原样返回,零损耗(普通短任务完全不受影响);
     *  - 超预算 → 保留首条(system)与最近 N 条完整,中间旧工具记录折叠为一条归档存根;
     *  - 单条超长消息 → 折叠为头尾片段(防大段文件原文撑爆上下文);
     *  aggressive=true 用于上下文溢出后的强裁剪(预算减半、保留更少近因)。
     */
    /**
     * 对主循环 history 做 token 预算裁剪（长任务循环内上下文管理核心）。
     * 两种压缩模式（用户可在 AI 设置中选择）：
     *  - 结构保活（默认·推荐）：超预算时保留 system + 全部用户消息 + 最近 N 条，中间的 tool/助手消息折叠为头尾，
     *    绝不丢弃任何用户指令（避免“压掉有用的”），仅压缩工具输出冗余。
     *  - LLM 摘要：超预算时把中间旧上下文交给模型概括成一段摘要（保语义），失败自动回退结构保活。
     *  aggressive（上下文溢出强裁）始终走结构保活，避免溢出时还去调模型拖慢恢复。
     */
    suspend fun trimLoopHistory(
        history: MutableList<Pair<String, String>>,
        aggressive: Boolean = false,
        ctx: Context? = null,
        userId: Long = 0
    ) {
        if (history.size <= 2) return
        val budget = if (aggressive) LOOP_HISTORY_AGGRESSIVE_BUDGET else LOOP_HISTORY_TOKEN_BUDGET
        var total = 0
        for ((_, t) in history) total += estimateTokens(t)
        if (total <= budget) return
        val keepRecent = if (aggressive) 3 else LOOP_HISTORY_KEEP_RECENT
        val headIdx = 0
        val recentStart = maxOf(headIdx + 1, history.size - keepRecent)
        val middle = history.subList(headIdx + 1, recentStart)     // 待压缩的中间段
        val recent = history.subList(recentStart, history.size)     // 近因（含用户指令）完整保留
        val archivedCount = middle.size

        // 是否走 LLM 摘要：仅在非 aggressive、有 ctx、且用户选了 LLM 模式时尝试
        val useLlm = !aggressive && ctx != null && getContextCompressMode(ctx, userId) == CONTEXT_COMPRESS_LLM
        var llmSummary: String? = null
        if (useLlm) {
            val middleText = middle.joinToString("\n\n") { (role, t) -> "[$role]\n$t" }
            llmSummary = try { summarizeHistoryMiddle(ctx!!, userId, middleText) } catch (_: Exception) { null }
        }

        val kept = mutableListOf<Pair<String, String>>()
        kept.add(history[headIdx])                                  // system 永远保留
        if (llmSummary != null) {
            kept.add("user" to "〔以下为早期上下文的模型摘要〕\n$llmSummary")
        } else {
            // 结构保活：保留全部用户消息（用户指令永不丢），其余中间消息折叠头尾
            for ((role, t) in middle) {
                if (role == "user") kept.add(role to t)
                else if (estimateTokens(t) > LOOP_MSG_TOKEN_FOLD_MAX) {
                    val h = t.take(3500); val tl = t.takeLast(1400)
                    kept.add(role to "$h\n…〔过长已折叠,原文约 ${estimateTokens(t)} token〕…\n$tl")
                } else kept.add(role to t)
            }
        }
        kept.addAll(recent)

        val note = if (llmSummary != null)
            "〔早期对话/工具记录已由模型摘要:共 $archivedCount 条〕"
        else
            "〔早期对话/工具记录已归档压缩:共 $archivedCount 条,此处保留最近 $keepRecent 条完整消息及全部用户指令〕"
        history.clear()
        history.addAll(kept)
        history.add("user" to note)
        // 单条超长消息折叠为头尾(保留上下文连贯性的前提下控预算)
        for (i in history.indices) {
            val (role, t) = history[i]
            if (estimateTokens(t) > LOOP_MSG_TOKEN_FOLD_MAX) {
                val head = t.take(3500)
                val tail = t.takeLast(1400)
                history[i] = role to "$head\n…〔该条内容过长已折叠,原文约 ${estimateTokens(t)} token〕…\n$tail"
            }
        }
    }

    /**
     * 一次性（非流式）调用模型，把旧上下文概括成一段摘要。
     * 用于 LLM 摘要压缩模式；失败/超时返回 null（调用方回退结构保活）。
     * 请求构造镜像 streamChat（含 paid/free 的 api_id / base_url / model_name 路由）。
     */
    private suspend fun summarizeHistoryMiddle(ctx: Context, userId: Long, middleText: String): String? = withContext(Dispatchers.IO) {
        val (key, baseUrl, model) = resolveConfig(ctx, userId)
        if (key.isEmpty() || baseUrl.isBlank() || model.isBlank()) return@withContext null
        val trimmedBase = baseUrl.trimEnd('/')
        val endpoint = if (trimmedBase.endsWith("/chat/completions")) trimmedBase else "$trimmedBase/chat/completions"
        val body = JSONObject().apply {
            put("model", model)
            val arr = JSONArray()
            // 系统提示已清理：压缩器不再注入任何指令文本，仅把待压缩原文作为 user 下发。
            arr.put(JSONObject().apply { put("role", "user"); put("content", middleText) })
            put("messages", arr)
            put("stream", false)
            put("max_tokens", 1200)
            when (getMode(ctx, userId)) {
                "paid_api" -> {
                    put("api_id", getPaidApiId(ctx, userId))
                    val (_, u, m) = getPaidApiConfig(ctx, userId)
                    if (u.isNotBlank()) put("base_url", u)
                    if (m.isNotBlank()) put("model_name", m)
                }
                "free_api" -> {
                    put("api_id", getFreeApiId(ctx, userId))
                    val (_, u, m) = getFreeApiConfig(ctx, userId)
                    if (u.isNotBlank()) put("base_url", u)
                    if (m.isNotBlank()) put("model_name", m)
                }
            }
        }
        val req = okhttp3.Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Authorization", "Bearer $key")
            .header("Accept-Encoding", "identity")
            .build()
        val client = com.aurora.chat.data.api.HttpClient.client.newBuilder()
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val respBody = resp.body?.string() ?: return@withContext null
                val json = org.json.JSONObject(respBody)
                val choices = json.optJSONArray("choices") ?: return@withContext null
                if (choices.length() == 0) return@withContext null
                val msg = choices.getJSONObject(0).optJSONObject("message") ?: return@withContext null
                msg.optString("content", "").trim().ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ===== 嵌套 agent 循环(agent_review/agent_plan/agent_fork)的改动/计数合并(缺陷A) =====
    // 每个嵌套循环内部会新建自己的 runAIChatLoop 与文件改动收集器(fileChanges),
    // 其 fileChanges/toolCallCount/toolCalls 若不合并回外层,外层最终写回消息时
    // 只有本层自己那几次「agent_review/agent_plan/agent_fork 调用」的记录,
    // 子循环内真实发生的工具调用(读文件/执行脚本/删文件)全部丢失,导致删除类任务
    // 「查看所有改动」为空、「调用 N 个工具」计数为 0 而按钮不出现。

    /** 嵌套 agent 循环的工具计数与调用明细暂存:内层收尾时写入,外层 executeAITool 返回后消费合并。
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
        // 并行派发的子执行者直接写父层那一个列表(见 runAIChatLoop.fileChangeSink),
        // 此时 prevRecorder === fileChanges —— 把列表吸收进自己既无意义,又会在遍历中改动自己。
        if (prevRecorder != null && prevRecorder !== fileChanges && fileChanges.isNotEmpty()) {
            synchronized(prevRecorder) {
                val seen = HashSet<String>()
                prevRecorder.forEach { c ->
                    seen.add(normRelPath(c.path) + "\u0001" + c.op + "\u0001" + normRelPath(c.fromPath))
                }
                fileChanges.forEach { c ->
                    val k = normRelPath(c.path) + "\u0001" + c.op + "\u0001" + normRelPath(c.fromPath)
                    if (k !in seen) { seen.add(k); prevRecorder.add(c) }
                }
            }
        }
        // 仅嵌套子循环（存在父层收集器）才把计数/明细暂存供外层 consumeNestedToolData 消费；
        // 最外层主循环收尾时 prevRecorder == null，若也暂存会污染模块级单槽 nestedPending，
        // 下一次 executeAITool 的 consumeNestedToolData 会把上次任务的计数垒进本条回复
        // （「调用 N 个工具」逐次叠加、虚高到几百）。
        if (prevRecorder != null && (toolCallCount > 0 || toolCalls.isNotEmpty())) {
            // 并行子任务会并发往这里累加,单槽变量必须加锁,否则计数会被并发写覆盖成较小值
            synchronized(NESTED_TOOL_LOCK) {
                nestedPendingCallCount += toolCallCount
                nestedPendingToolCalls.addAll(toolCalls)
            }
        }
    }

    /** 外层消费嵌套暂存:executeAITool 返回后调用,把子循环计数/明细并入本层。 */
    private fun consumeNestedToolData(): Pair<Int, List<ChatMsg.ToolStageRecord>> =
        synchronized(NESTED_TOOL_LOCK) {
            val c = nestedPendingCallCount
            val l = nestedPendingToolCalls.toList()
            nestedPendingCallCount = 0
            nestedPendingToolCalls.clear()
            c to l
        }

    /** 嵌套循环往父层暂存计数/明细时的锁(并行子任务会并发写,单槽变量必须保护) */
    private val NESTED_TOOL_LOCK = Any()

    /** 脚本类工具(shell_exec/execute_python)执行前后局部快照 diff 补录(缺陷B):
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
            // 并发场景(items 可能被其它并行 AI 追加)先取快照再遍历,避免遍历中被改
            snapshotFileChanges(changes).forEach { c ->
                coveredExact.add(normRelPath(c.path))
                if (c.fromPath.isNotEmpty()) coveredExact.add(normRelPath(c.fromPath))
                // 目录类改动只记目录本身,其下文件按前缀覆盖,避免解压/搬移/删除的目录内文件再重复记 created
                if (c.op == "deleted_folder" || c.op == "extract" || c.op == "moved_dir")
                    coveredPrefixes.add(normRelPath(c.path).trimEnd('/') + "/")
            }
            fun covered(rel: String) = rel in coveredExact || coveredPrefixes.any { rel.startsWith(it) }
            // 新建 / 修改
            var addedCreated = 0; var addedModified = 0; var addedDeleted = 0
            for ((rel, a) in after) {
                if (covered(rel)) continue
                val b = before[rel]
                when {
                    b == null -> { addChangeSafe(changes, FileChange(rel, "created", "", "", capSnapText(a.text))); addedCreated++ }
                    b.size != a.size || b.mtime != a.mtime -> {
                        // size/mtime 变了但内容一致(原样重写)不算改动
                        if (b.text != a.text) { addChangeSafe(changes, FileChange(rel, "modified", "", capSnapText(b.text), capSnapText(a.text))); addedModified++ }
                    }
                }
            }
            // 删除(快照有、现在没有);二进制/大文件 text 为空时靠 size 保留信息,供「查看改动」识别为无文本删除
            for ((rel, b) in before) {
                if (rel in after || covered(rel)) continue
                addChangeSafe(changes, FileChange(rel, "deleted", "", capSnapText(b.text), "", size = b.size)); addedDeleted++
            }
            // 追溯透明化:记录本次快照兜底补录数量,便于排查脚本类(缺陷B 之外的)改动。
            // 注(缺陷B):脚本在本轮内「新建又删除」的文件(mkdir+rm / write+rm)在 before/after 快照里都不存在,
            // 属固有盲区,无法被此处捕获,也不会留下残留(已随脚本结束消失)——仅影响「能否在改动列表里看到它」。
            if (addedCreated + addedModified + addedDeleted > 0)
                com.aurora.chat.ErrorReporter.debug(
                    "AIChangeTrace",
                    "快照兜底补录: 新建=$addedCreated 修改=$addedModified 删除=$addedDeleted"
                )
        } catch (_: Exception) {}
    }

    /**
     * 去掉正文里的 [MULTI_OUTPUT_SPLIT] 分段标记。
     *
     * 为什么单独包一层而不用 `String.replace`:`replace` 内部会无条件建 StringBuilder 再逐段搬运,
     * 而这里绝大多数调用（流式每帧的 collect 写回、收尾、落盘）面对的文本里根本没有该标记,
     * 先做一次 indexOf 就能直接返回原字符串,省掉一次整段拷贝。AI 输出动辄几千字,这是白烧的大头。
     */
    fun stripMultiSplit(s: String): String =
        if (s.isEmpty() || s.indexOf(MULTI_OUTPUT_SPLIT) < 0) s else s.replace(MULTI_OUTPUT_SPLIT, "")

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
     * AI 工具执行循环:反复调用 [round] 生成一轮回复,若该轮含工具调用则执行并回喂历史,
     * 直到模型不再调用工具、封顶 MAX_AGENT_TOOL_ROUNDS 或异常/手动停止。
     * @param history 调用方传入的上下文 MutableList,工具结果会就地追加(跨轮保留)。
     * @param onReasoning 多轮思考累积回调:将前面各轮思考(reasoning_content)按哨兵拼接后回传,
     *                    供调用方实时刷新思考块(分段渲染)。空实现表示不展示。传 null 则内部不累积思考。
     * @param round 执行单轮流式请求;返回该轮是否正常完成及累积文本/用量/该轮思考片段。prefix 为截至上一轮已累积的
     *              对话文本(本轮的展示前缀;首轮即空)。检测到的原生 tool_calls 会填入 nativeCalls。
     */
    suspend fun runAIChatLoop(
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
        /** play_video 工具回调:把要播放的视频地址(本地绝对路径或 http(s) 链接)传给 UI,由 UI 直接弹出全屏播放器 */
        onOpenVideo: (suspend (videoUrl: String) -> Unit)? = null,
        /** update_plan_step 工具回调:把步骤序号与状态传给 UI,由 UI 更新对应方案消息的 JSON 并持久化(逐条勾选/隐藏) */
        onPlanStepUpdate: (suspend (stepIndex: Int, status: String) -> Unit)? = null,
        /** 工具调用动态状态回调:「名」+状态(0=调用中 1=成功 2=失败)+参数 JSON + 累计工具调用数 + 累计文件改动数 + 当前工具阶段明细列表。
         *  UI 据此显示蓝/绿/红工具状态行,并从参数里提取**真实目标**(文件名/路径/关键字)显示成可追溯的汉化文案;
         *  累计数用于「固定工具调用与修改日志」在流式期间实时刷新底部胶囊(数字边跑边涨)。 */
        onToolStage: ((tool: String, state: Int, argsJson: String, cumulativeToolCalls: Int, cumulativeChanges: Int, stages: List<ChatMsg.ToolStageRecord>) -> Unit)? = null,
        /** App 内部业务动作回调(切板块等需 UI 协作的动作):透传给 executeAITool。 */
        appActionHandler: (suspend (AppAction) -> AppActionResult)? = null,
        /** 多 AI 模式强制验收开关:执行者动了工作区却没调用 agent_review 时,注入补验指令强制再跑一轮。
         *  验收 AI 自身的嵌套循环必须传 false,否则验收者会被要求验收自己。 */
        enforceReview: Boolean = true,
        /** 工具轮数上限覆写:验收者等嵌套循环传较小值(如 5),防验收 AI 自己无限跑轮 */
        maxRoundsOverride: Int? = null,
        /** 实时进度回调:编排层据此更新气泡底部「任务进行中 · 第 N 轮 / 正在收尾交付」提示 */
        onProgress: ((String) -> Unit)? = null,
        /** 是否启用任务守护（软上限收尾、交付契约、兜底交付）。嵌套子循环（验收/规划/派发）传 false,避免子任务被强制交付。 */
        enableTaskCompletion: Boolean = true,
        /**
         * 墙钟软截止(毫秒,从循环启动算起)。到点后不再调用工具,并注入 [deadlineInstruction] 催促出结论,
         * 让循环**自然收束**出真实结论而不是被超时掐断成半截文本。0 = 不启用(母循环默认不变)。
         *
         * 为什么绕开 [maxRoundsOverride]:它只数「真正跑过工具的轮次」,而续写 / 上下文裁剪重试 /
         * 可重试错误这三条 `continue` 都不计数,叠加 429 退避(单次最长 30s),名义 5 轮的嵌套验收
         * 实际能跑十几分钟 → 被外层硬超时从中间掐断 → 判 UNVERIFIED(用户实测:验收必失败)。
         */
        softDeadlineMs: Long = 0L,
        /** 全部迭代(含上述重试/续写/空轮)的活动轮上限,防止重试把时间吃光。0 = 不启用。 */
        maxActivityRounds: Int = 0,
        /** 到达软截止/活动上限时注入的催结论指令;null 表示只禁工具、不额外注入。 */
        deadlineInstruction: String? = null,
        /**
         * 由调用方指定的文件改动收集器(并行子任务专用)。非空时本循环**直接写这个列表**,
         * 并且**不再去抢** [activeFileChangeRecorder] 那个模块级单槽。
         *
         * 为什么需要它:过去每个子执行者进来就 `activeFileChangeRecorder = 自己的列表`,
         * 4 个并发子循环互相覆盖 —— 只有最后抢到槽的那个能收到记录,另外 3 个的改动全部丢失,
         * 而且四个线程同时往同一个 ArrayList 写本身就是并发修改(丢条目/结构损坏)。
         * 共用父层那一个列表 + 写入侧加锁(见 [addChangeSafe]),两个问题一起消掉。
         */
        fileChangeSink: MutableList<FileChange>? = null,
        round: suspend (nativeCalls: MutableList<AINativeToolCall>, prefix: String, toolChoice: String?) -> AIRoundResult
    ): AILoopOutcome {
        // 长任务哨兵：正常收尾（含出错退出）会清掉标记；只有进程被系统强行掐断
        // 才会把标记留下，下次启动时据此如实告知用户「上次任务被中断了」。
        com.aurora.chat.AITaskGuard.markRunning(ctx, "AI 工具任务")
        // 【执行日志归属】只有**最外层**任务负责开/关追加式日志。
        // 判据用 enableTaskCompletion：验收者/规划者/并行子执行者这些嵌套子循环一律传 false。
        // 如果嵌套循环也去 bind，就会把父任务正在写的那份日志顶掉 → 父任务的记录从此写进新文件，
        // 而旧文件没有 end 会被误判成"崩溃残留"（正常的任务也被当成中断）。
        // 反过来，子循环里的工具调用/文件改动**照样会写进父任务那份日志**（日志是进程级单槽），
        // 这正是我们要的：并行派发期间的调用同样可追溯。
        val journalOwner = enableTaskCompletion
        if (journalOwner) {
            AIJournal.bind(
                ctx = ctx, userId = userId,
                convKey = AiConversationSession.storageKey(),
                msgId = aiStreamMsgId.value,
                desc = "AI 工具任务"
            )
        }
        // 文件改动收集器:本会话所有文件类工具的成功改动都会追加到此,供「查看所有改动」展示。
        // 声明在 try 之前,finally 里才能还原;用 save/restore 保护嵌套 agent 循环(验收/规划子循环)不互相串扰。
        val fileChanges = fileChangeSink ?: mutableListOf()
        /** true = 本循环自己拥有这个收集器(需要在 finally 里还原模块级单槽);
         *  false = 复用父层的列表(并行子任务),谁都不许动模块级单槽 */
        val ownsRecorder = fileChangeSink == null
        val prevRecorder = activeFileChangeRecorder
        if (ownsRecorder) activeFileChangeRecorder = fileChanges
        // 任务开始前对沙盒拍全量快照:结束时比对,兜底捕获工具记录之外的一切改动
        // (shell_exec/execute_python 等脚本写文件不经过文件工具,只有快照 diff 能抓到)
        val sandboxBefore = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { snapshotSandboxFiles(ctx, userId) }
        try {
            var accumulated = ""
            var reasoningAccum = ""
            var toolRounds = 0
            // 多 AI 强制验收跟踪:是否调用过 agent_review / 动过哪些工作区类工具 / 是否已补验过一次
            // 补验指令只在【多 AI 模式】生效:普通模式下动了工作区不强制验收(用户没开多 AI,
            // 就不该有「验收 AI 接手」这种事);嵌套循环(验收者/规划者/子执行者)传 false 同样关闭。
            val enforceReviewEffective = enforceReview && execActive(ctx, userId, EXEC_MULTI_AGENT)
            var reviewCalled = false
            var reviewNudged = false
            // agent_review 已被调用次数(含被拒):配合 MAX_AGENT_REVIEW_CALLS 做编排层硬上限
            var reviewCallCount = 0
            val mutatedWorkspace = mutableSetOf<String>()
            // 工具轮数上限由执行模式决定(纯编排层,任何 API/模型都生效);嵌套循环可覆写为更小值
            val maxToolRounds = maxRoundsOverride ?: maxToolRoundsFor(ctx, userId)
            var lastUsage = 0L
            var lastThinking = 0L
            var lastTotal = 0L
            // 多轮累积用量明细:每轮相加(供计费与用量界面取最终值);contextLength 取最后一轮
            var lastUsageDetail: AiUsageDetail? = null
            // 本次会话实际处理过的工具调用次数(含失败/被拒):底部信息行「调用 N 个工具」数据源
            var toolCallCount = 0
            // 每次工具调用明细(工具名/成败/发起时刻毫秒):「工具调用」全屏清单数据源
            val toolCalls = mutableListOf<ChatMsg.ToolStageRecord>()
            // 主循环连续失败硬上限：防止 AI 在同一个坑里死磕（比如同一个工具连调 7 次都是失败）。
            // 规则：本轮所有工具调用全部失败才算一次连续失败；本轮有至少一个成功就清零。
            // 连续 3 轮全失败 → 强制 break，不再给 AI 新轮次去重试。
            var consecutiveAllFailRounds = 0
            // 长任务中断优化状态(P2):
            var lastBatchMarker = 0        // 上次批次衔接注入时的工具数(防重复注入)
            var readOnlyStreak = 0         // 连续只读探索轮数(达到上限判定「过度探索」→ 降级输出)
            var roundReadOnly = false      // 本轮是否只调了只读工具(轮末结算用)
            var roundWrote = false         // 本轮是否有写/产出工具成功(轮末结算用)
            var sameToolFailStreak = 0     // 同类工具连续失败次数(提前换策略,不等整轮全败)
            var lastFailTool = ""          // 最近连续失败的同类工具名
            var contextRetries = 0         // 上下文溢出「裁剪后重试」次数(上限 MAX_CONTEXT_RETRIES)
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
                val rel = resolveAIPath(ctx, userId, agentRoot(ctx), pathRaw)
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
            // play_video 交付:读结果里的 play_url 回调 UI,由 UI 直接弹出全屏播放器。
            // 本地文件(工作区)传绝对路径,UI 侧直接读本地起播,不走网络。
            suspend fun emitOpenVideo(toolName: String, result: String, approved: Boolean) {
                if (toolName != TOOL_PLAY_VIDEO || !approved) return
                if (onOpenVideo == null) return
                try {
                    val o = JSONObject(result)
                    if (o.optBoolean("ok")) {
                        val url = o.optString("play_url", "")
                        if (url.isNotBlank()) onOpenVideo.invoke(url)
                    }
                } catch (_: Exception) {}
            }
            // update_plan_step 回写:把步骤序号与状态回调 UI,由 UI 更新方案消息 JSON 并持久化(逐条勾选/隐藏)
            suspend fun emitPlanStepUpdate(toolName: String, result: String, approved: Boolean) {
                if (!approved || toolName != "update_plan_step") return
                if (onPlanStepUpdate == null) return
                try {
                    val o = JSONObject(result)
                    if (o.optBoolean("ok")) {
                        val idx = o.optInt("step_index", -1)
                        val st = o.optString("status", "")
                        if (idx >= 0 && st.isNotBlank()) onPlanStepUpdate.invoke(idx, st)
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
            var phase = AITaskCompletion.AITaskPhase.NORMAL
            var wrapUpRounds = 0
            var nudgeCount = 0
            // 「请示式收尾」被驳回次数（见循环内 AITaskCompletion.asksForPermission 处的说明）。
            // 独立预算：与「防提前收工」成因不同（不交付 vs 把决定权推回用户），共享额度会让多步任务互相饿死。
            var permissionNudgeCount = 0
            var continueCount = 0
            var retryCount = 0
            var emptyRounds = 0   // 静默空轮计数器:上游空返回/抖动时做有界重试,耗尽则明确告知而非静默结束
            var currentToolChoice: String? = null
            var failStrategyRounds = 0   // P1:连续全失败后「换策略」补救机会计数(失败≠立即收尾)
            var wrapUpToolBudget = 0     // P1:收尾阶段剩余工具补救额度(收尾仍可少量补救,防死循环)
            var contractNeeded = enableTaskCompletion && AITaskCompletion.needsContract(ctx, userId, toolCallCount)
            // 交付「格式规范」是否已注入（见循环内首次契约触发处的说明）
            var contractSpecInjected = false
            // 嵌套子循环的软截止是否已触发(只触发一次):触发后置 tool_choice=none 并催结论,
            // 循环随后靠自身的"无工具调用 + 有正文"出口自然收束,产出的是完整结论而非半截文本。
            var softDeadlineApplied = false
            /** 软截止已在第几轮触发:给触发后留若干轮"补结论"的余量,到点才真正收束,避免一催就断 */
            var deadlineRounds = 0
            /** 循环迭代总数(含重试/续写/空轮):嵌套子循环的软截止按它计数,不看 toolRounds */
            var loopIterations = 0

            while (true) {
                loopIterations++
                // === 嵌套子循环的双重软截止(活动轮上限 / 墙钟):见 softDeadlineMs 参数注释 ===
                // 两者都不掐协程、不丢已产出内容,只是把「继续调工具」这条路封住,逼出结论。
                if (!softDeadlineApplied) {
                    val elapsedWall = android.os.SystemClock.elapsedRealtime() - loopStartElapsed
                    val hitActivity = maxActivityRounds > 0 && loopIterations >= maxActivityRounds
                    val hitWall = softDeadlineMs > 0L && elapsedWall >= softDeadlineMs
                    if (hitActivity || hitWall) {
                        softDeadlineApplied = true
                        currentToolChoice = "none"
                        Log.i(
                            TAG,
                            "runAIChatLoop 软截止触发(${if (hitWall) "墙钟 ${elapsedWall / 1000}s" else "活动轮 $loopIterations"}),禁工具催结论"
                        )
                    }
                } else {
                    // 触发后再跑 AGENT_SUBLOOP_DEADLINE_GRACE 轮就必须收束:给模型补结论的机会,同时杜绝"催了还拖"。
                    deadlineRounds++
                    if (deadlineRounds >= AGENT_SUBLOOP_DEADLINE_GRACE) {
                        Log.i(TAG, "runAIChatLoop 软截止后已达补结论上限($deadlineRounds 轮),强制收束")
                        break
                    }
                }
                // 长任务分批执行(P2):每完成一批工具调用,注入批次衔接指令,自动续接下一批(不等待用户)
                if (contractNeeded && phase == AITaskCompletion.AITaskPhase.NORMAL && toolCallCount > 0 &&
                    toolCallCount / AITaskCompletion.BATCH_TOOL_CALLS > lastBatchMarker / AITaskCompletion.BATCH_TOOL_CALLS
                ) {
                    val marker = (toolCallCount / AITaskCompletion.BATCH_TOOL_CALLS) * AITaskCompletion.BATCH_TOOL_CALLS
                    lastBatchMarker = marker
                    history.add("user" to AITaskCompletion.batchContinuationInstruction(marker))
                    Log.i(TAG, "runAIChatLoop 注入批次衔接(已完成 $marker 次工具调用)")
                }
                // 防死循环/过度探索(P2):连续多轮只读探索无实质产出 → 停止探索,基于已有信息降级输出
                if (enableTaskCompletion && contractNeeded && phase == AITaskCompletion.AITaskPhase.NORMAL &&
                    readOnlyStreak >= AITaskCompletion.MAX_OVER_EXPLORE_ROUNDS
                ) {
                    phase = AITaskCompletion.AITaskPhase.WRAP_UP
                    wrapUpRounds++
                    currentToolChoice = null
                    wrapUpToolBudget = 0 // 禁工具:防止继续只读探索
                    history.add("user" to AITaskCompletion.overExploreInstruction())
                    Log.i(TAG, "runAIChatLoop 连续 $readOnlyStreak 轮只读探索无产出,触发降级输出")
                }
                // 循环内 token 预算管理(P2):发送前裁剪 history,防多轮工具记录把上下文撑爆
                trimLoopHistory(history, ctx = ctx, userId = userId)
                val prefix = accumulated
                val nativeCalls = mutableListOf<AINativeToolCall>()
                // 软上限收尾阶段:禁用工具,强制纯文本交付
                // 实时进度提示:仅任务型会话显示「任务进行中」,普通对话用中性/空提示,避免打扰
                // 【不再暴露轮次】「第 N 轮」是编排层的内部计量，对用户没有任何可执行信息，
                // 只会让界面看起来像在报进度条数字。统一说"任务进行中"即可。
                onProgress?.invoke(
                    if (phase == AITaskCompletion.AITaskPhase.WRAP_UP) "正在收尾交付"
                    else if (contractNeeded) "任务进行中"
                    else ""
                )
                val res = round(nativeCalls, prefix, currentToolChoice)
                // 用量跨轮累加:必须放在下面所有 continue 分支(截断续写/上下文裁剪/可重试错误)之前,
                // 保证那几轮上游已经真实产生的消耗同样计入,不留下漏计口子(覆盖式计费会系统性少扣)。
                lastUsage += res.usage
                lastUsageDetail = accumulateUsage(lastUsageDetail, res.usageDetail)
                // 输出被上游 max_tokens 截断(finish_reason=length):自动从断点续写,最多 MAX_CONTINUE_ROUNDS 次
                if (res.finishReason == "length" && continueCount < AITaskCompletion.MAX_CONTINUE_ROUNDS) {
                    continueCount++
                    val tail = stripToolBlocks(res.fullText).takeLast(200)
                    history.add("user" to AITaskCompletion.continueInstruction(tail))
                    Log.i(TAG, "runAIChatLoop 输出被截断,自动续写(第 $continueCount 次)")
                    continue
                }
                if (!res.done) {
                    // 上下文溢出类错误(P2):不可原地重试(历史已超预算,原地重试必再失败)——
                    // 先强裁剪 history 再重试(最多 MAX_CONTEXT_RETRIES 次);裁剪后仍失败则按致命处理,不再原地重试。
                    if (AITaskCompletion.isContextOverflowError(res.errorText) && contextRetries < AITaskCompletion.MAX_CONTEXT_RETRIES) {
                        contextRetries++
                        trimLoopHistory(history, aggressive = true, ctx = ctx, userId = userId)
                        history.add("user" to AITaskCompletion.contextOverflowRetryInstruction())
                        Log.i(TAG, "runAIChatLoop 上下文溢出,裁剪历史后重试(第 $contextRetries 次)")
                        continue
                    }
                    // 可重试错误(网络/5xx/断流):原地重试,保留已完成进度,不终结任务
                    if (res.retriable && retryCount < AITaskCompletion.MAX_ROUND_RETRIES) {
                        retryCount++
                        Log.i(TAG, "runAIChatLoop 单轮可重试错误,第 $retryCount 次重试")
                        continue
                    }
                    // 重试耗尽或致命错误:只要本轮曾用工具且无可读总结,补兜底交付,避免「任务消失」
                    // (放宽为基于 hasReadableSummary,不再要求契约模式:错误退出同样保证用户拿到可读总结)
                    if (toolCallCount > 0 && !AITaskCompletion.hasReadableSummary(accumulated)) {
                        accumulated += "\n" + AITaskCompletion.fallbackDeliveryBlock(toolCalls, fileChanges)
                    }
                    // 任务因「真实错误」意外中断（非用户手动停止）：把后台保活小窗切成故障态
                    // （红底「AI 出现故障，点击回前台」，30 秒后自动关窗），避免任务已死却在桌面上留一个「AI 运行中」的僵尸小窗。
                    // 手动停止时 res.errorText 为空，不触发这里（那是用户主动行为，不是故障）。
                    if (res.errorText.isNotBlank()) {
                        try { com.aurora.chat.AiKeepAliveService.notifyFault(ctx) } catch (_: Exception) {}
                    }
                    // 手动停止 / 出错:保留已流出部分,不再执行工具、也不收尾(由调用方决定展示)。
                    // 即使中途停止,总时长也如实记到当前时刻(连续计时)。
                    // 中途停止前已发生的真实文件改动同样做快照比对,改动记录不丢
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { mergeSandboxDiff(ctx, userId, sandboxBefore, fileChanges) }
                    // 嵌套 agent 循环(验收/规划/派发):本层收尾时把改动合并回父层、计数/明细暂存
                    absorbNestedLoopIntoParent(prevRecorder, fileChanges, toolCallCount, toolCalls)
                    return AILoopOutcome(
                        accumulated, toolRounds, clean = false, lastUsage, lastThinking,
                        (android.os.SystemClock.elapsedRealtime() - loopStartElapsed) / 1000L,
                        lastUsageDetail,
                        fileChanges, toolCallCount, toolCalls
                    )
                }
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
                val producedTool = nativeCalls.isNotEmpty() || extractFirstToolCall(res.fullText) != null || extractXmlStyleToolCall(res.fullText) != null
                // 任务型会话(开了任务模式或已用过工具)需要交付契约:动态更新判断依据
                if (enableTaskCompletion) contractNeeded = AITaskCompletion.needsContract(ctx, userId, toolCallCount)
                // 【交付格式规范·首次注入】needsContract() 在系统提示构建时是用 usedTools=0 判定的 ——
                // 纯 Craft（未开多 AI）那一步恒为 false，于是「交付契约」这一段从来没进过上下文：
                // 模型被 nudge 逼着"输出交付块"，却从不知道哨兵长什么样，只能写一段散文总结，
                // 交付卡片于是永远渲染不出来（用户反馈：craft 模式下交付总结那个块出不来、AI 好像也不知道）。
                // 这里在"本次会话真的开始用工具了"的这一刻补注入一次（一次任务只注一次，纯闲聊零开销）。
                if (contractNeeded && !contractSpecInjected) {
                    contractSpecInjected = true
                    // 系统提示里本就带契约（多 AI 任务型）时不重复注入，只补"系统提示没这段"的纯 Craft 场景。
                    if (!AITaskCompletion.needsContract(ctx, userId)) {
                        history.add("user" to AITaskCompletion.buildContractPrompt(ctx, userId))
                        Log.i(TAG, "runAIChatLoop 首次注入交付格式规范(已用工具 $toolCallCount 次)")
                    }
                }
                // 模型本轮既没调用工具、也没输出正文(只有思考或上游空返回):
                // 可能是"还没想完",也可能是"上游抖动/空响应"。用独立计数器做【有界】重试,
                // 既避免"想了半天却什么都没说"就结束,也杜绝空轮无限循环刷 API(原逻辑轮数到上限会静默 break、未到上限会死循环)。
                if (!producedTool && cleaned.isBlank()) {
                    emptyRounds++
                    if (emptyRounds <= AITaskCompletion.MAX_EMPTY_ROUNDS && toolRounds < maxToolRounds) {
                        history.add("user" to "")
                        Log.w(TAG, "runAIChatLoop 静默空轮,第 $emptyRounds 次补催")
                        continue
                    }
                    // 空轮重试耗尽:绝不静默结束。
                    // ⚠️ 旧写法只在 accumulated **为空** 时才告知 —— 可只要模型开头说过一句"我先看看工作区",
                    // accumulated 就非空,于是既不补交付也不报错地静默停住(用户看到的就是"任务没做完就断了")。
                    // 现在:只要没有可读交付证据,就无条件留下交代(有工具产出则补系统托管交付块)。
                    if (accumulated.isBlank()) {
                        accumulated += "\n〔系统提示〕本次请求 AI 未返回任何内容(疑似上游空响应、网络抖动或模型异常),任务未能完成。请重试,或检查 API Key / 网络 / 模型名是否正确。"
                    } else if (!AITaskCompletion.hasReadableSummary(accumulated)) {
                        if (toolCallCount > 0) {
                            accumulated += "\n" + AITaskCompletion.fallbackDeliveryBlock(toolCalls, fileChanges)
                        }
                        accumulated += "\n〔系统提示〕模型连续 ${emptyRounds} 轮没有输出任何正文或工具调用(疑似上游空响应 / 网络抖动 / 模型异常),任务在此中断,尚未完成。可以让我重试或换个说法。"
                    }
                    break
                }
                if (!producedTool) {
                    // 多 AI 硬约束(编排层兜底):动了工作区却从未调用 agent_review → 注入补验指令,强制再跑一轮。
                    // 提示词层的「必须验收」被模型无视时的确定性补救;只补验一次,防死循环。
                    if (enforceReviewEffective && !reviewCalled && !reviewNudged && mutatedWorkspace.isNotEmpty() && toolRounds < maxToolRounds) {
                        reviewNudged = true
                        history.add("user" to "")
                        continue
                    }
                    // 【禁止请示·确定性封堵】模型以「要不要我继续 / 需要我现在开始吗 / 是否继续处理」这类请示句
                    // 收尾（且没有交付）→ 判为**无效收尾**：系统替用户回答「继续」并强制再跑一轮。
                    // 用户实测的恶性循环：任务刚开头就问要不要下一轮再改 → 用户说继续 → 只做一步又问 → 无限套娃。
                    // 必须放在「防提前收工」之前：后者要求"补齐交付总结"，对着"才做了一半"的回合
                    // 反而会诱导模型写一张半截交付卡就此合法收工。
                    // 只封**纯文本**请示；破坏性操作确认走 ask_user 工具（工具调用不走这条分支）。
                    if (phase != AITaskCompletion.AITaskPhase.WRAP_UP && contractNeeded &&
                        permissionNudgeCount < AITaskCompletion.MAX_PERMISSION_NUDGES &&
                        AITaskCompletion.asksForPermission(accumulated)
                    ) {
                        permissionNudgeCount++
                        history.add("user" to AITaskCompletion.permissionDeniedInstruction())
                        Log.i(TAG, "runAIChatLoop 请示式收尾被驳回,强制继续(第 $permissionNudgeCount 次)")
                        continue
                    }
                    // 防提前收工(通用客观判定):模型想结束但既无客观产出证据(写/产出类工具成功记录)、
                    // 也未给出可核对的交付总结 → 按实际进度分两种处理:
                    //  · 工作未完成 → 注入「继续执行」指令(保留工具,禁止现在交付),预算 MAX_CONTINUE_WORK_NUDGES;
                    //  · 工作已完成但缺交付总结 → 注入「补交付」指令(禁工具,直接输出交付块),预算 MAX_DELIVER_NUDGES。
                    // 旧写法只有一条指令且固定写「不要再调用任何工具,直接输出交付块」——工作还没做就催着写总结,
                    // 正是「两三次工具调用就出交付卡、任务没做完就断」的直接原因(用户实测)。
                    // 交付判定回归客观证据:DELIVER 块不再强制,工具覆盖 + 产物存在 + 可读总结即有效交付。
                    val deliveryVerified = AITaskCompletion.verifyDelivery(accumulated, agentRoot(ctx)) { p -> deliveredFileFor(ctx, userId, agentRoot(ctx), p) }
                    val objectiveDone = hasObjectiveCompletion(toolCalls)
                    val nudgeBudget = if (objectiveDone) AITaskCompletion.MAX_DELIVER_NUDGES else AITaskCompletion.MAX_CONTINUE_WORK_NUDGES
                    if (phase != AITaskCompletion.AITaskPhase.WRAP_UP && contractNeeded && !objectiveDone && !deliveryVerified && nudgeCount < nudgeBudget) {
                        nudgeCount++
                        val missing = AITaskCompletion.missingDeliveryPaths(accumulated, agentRoot(ctx)) { p -> deliveredFileFor(ctx, userId, agentRoot(ctx), p) }
                        history.add("user" to AITaskCompletion.nudgeInstruction(objectiveDone) +
                            (if (missing.isNotEmpty()) AITaskCompletion.deliveryVerifyInstruction(missing) else ""))
                        Log.i(TAG, "runAIChatLoop 模型想结束但无成功产出证据且交付未通过,强制继续执行(第 $nudgeCount 次)")
                        continue
                    }
                    // P1-3 软预算防断档:已给出可读总结(不必强制 DELIVER 哨兵),但 NORMAL 阶段轮次已逼近预算上限(>= max-1) → 不直接 break,
                    // 先注入一次「补结构化交付总结」并转入收尾,保证预算边缘任务也有完整交付收尾。
                    if (phase == AITaskCompletion.AITaskPhase.NORMAL && contractNeeded && AITaskCompletion.hasReadableSummary(accumulated) &&
                        maxToolRounds >= 2 && toolRounds >= maxToolRounds - 1 && wrapUpRounds < AITaskCompletion.MAX_WRAP_UP_ROUNDS) {
                        phase = AITaskCompletion.AITaskPhase.WRAP_UP
                        wrapUpRounds++
                        currentToolChoice = null
                        wrapUpToolBudget = AITaskCompletion.MAX_WRAP_UP_TOOL_ROUNDS
                        history.add("user" to AITaskCompletion.wrapUpInstruction(toolRounds, ""))
                        Log.i(TAG, "runAIChatLoop 软预算:交付通过但轮次逼近上限($toolRounds/$maxToolRounds),补收尾总结")
                        continue
                    }
                    // 收尾阶段(WRAP_UP):交付即停,否则续一轮;两轮仍无 DELIVER 块 → 编排层兜底合成后再 break,绝不裸断
                    if (phase == AITaskCompletion.AITaskPhase.WRAP_UP) {
                        if (deliveryVerified || wrapUpRounds >= AITaskCompletion.MAX_WRAP_UP_ROUNDS) {
                            if (!deliveryVerified) {
                                accumulated += "\n" + AITaskCompletion.fallbackDeliveryBlock(toolCalls, fileChanges)
                            }
                            break
                        }
                        wrapUpRounds++
                        history.add("user" to "")
                        continue
                    }
                    // 兜底(通用):只要本轮用过工具且没有可读总结(无 DELIVER 块或交付信号不足 2 类),
                    // 编排层合成交付块,确保任务完成后用户一定拿到可读总结;不再要求契约模式。
                    if (toolCallCount > 0 && !AITaskCompletion.hasReadableSummary(accumulated)) {
                        accumulated += "\n" + AITaskCompletion.fallbackDeliveryBlock(toolCalls, fileChanges)
                    }
                    break
                }
                if (toolRounds >= maxToolRounds) {
                    // 软上限:轮次预算耗尽不再硬断,而是转入收尾阶段(禁工具强制纯文本交付),保证用户拿到交付
                    if (contractNeeded && phase == AITaskCompletion.AITaskPhase.NORMAL && wrapUpRounds < AITaskCompletion.MAX_WRAP_UP_ROUNDS) {
                        phase = AITaskCompletion.AITaskPhase.WRAP_UP
                        wrapUpRounds++
                        currentToolChoice = null // P1:收尾保留少量工具补救额度(由 wrapUpToolBudget 限额)
                        wrapUpToolBudget = AITaskCompletion.MAX_WRAP_UP_TOOL_ROUNDS
                        history.add("user" to AITaskCompletion.wrapUpInstruction(toolRounds, "轮次预算已用尽"))
                        Log.i(TAG, "runAIChatLoop 轮次预算耗尽,转入收尾阶段")
                        continue
                    }
                    // ⚠️ 这个 break 以前是**裸断**:用户只看到中途的正文,任务就这么没了、也没有任何交代。
                    // 现在同样留下可见收束:有工具产出就补系统托管交付块,再补一句明确说明。
                    if (!AITaskCompletion.hasReadableSummary(accumulated)) {
                        if (toolCallCount > 0) {
                            accumulated += "\n" + AITaskCompletion.fallbackDeliveryBlock(toolCalls, fileChanges)
                        }
                        accumulated += "\n〔系统提示〕已达工具轮次预算上限($maxToolRounds 轮),任务在此收束。如仍有未完成的部分,请让我继续。"
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
                        val call = AIToolCall(
                            tool = nc.name,
                            path = args.optString("path", ""),
                            content = args.optString("content", ""),
                            argsJson = nc.argumentsJson
                        )
                        // 多 AI 强制验收跟踪:是否验过收 / 动过工作区 / 复验计数
                        if (nc.name == TOOL_AGENT_REVIEW) { reviewCalled = true; reviewCallCount++ }
                        if (nc.name in REVIEW_REQUIRED_TOOLS) mutatedWorkspace.add(nc.name)
                        // 调用开始:把「正在调用」状态行内联进正文流当前位置(调用真正发生的位置)
                        // 每次调用只解析一次 argsJson，同时拿到状态行目标与展开用参数摘要（绝不读 content 全文）
                        val stageInfo = toolCallInfo(nc.name, nc.argumentsJson)
                        accumulated = appendToolInline(
                            accumulated, nc.name, 0, stageInfo.target, stageInfo.argsSummary
                        )
                        onToolStage?.invoke(nc.name, 0, nc.argumentsJson, toolCallCount, AiChatManager.activeFileChangeRecorder?.size ?: 0, toolCalls.toList())
                        val callTs = System.currentTimeMillis()
                        val approved = onNeedApproval?.invoke(nc.name, nc.argumentsJson) ?: true
                        val result = if (approved) {
                            if (nc.name == TOOL_AGENT_REVIEW && reviewCallCount > MAX_AGENT_REVIEW_CALLS) reviewCapExceededResult()
                            else executeAITool(ctx, userId, call, appActionHandler)
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
                        emitOpenVideo(nc.name, result, approved)
                        emitCaptureDelivery(nc.name, result, approved)
                        emitPlanStepUpdate(nc.name, result, approved)
                        // 完成态:结果 ok 显示绿色「已调用」,否则红色「调用失败」(完成为彩色状态行,不再写纯文本)
                        val sOk = try { JSONObject(result).optBoolean("ok") } catch (_: Exception) { false }
                        // reason=AI 调用原因(由工具名+参数生成用途短句),供「工具调用」清单展示在调用时间与工具名之间
                        val stageRec = ChatMsg.ToolStageRecord(
                            tool = nc.name,
                            state = if (sOk) 1 else 2,
                            ts = callTs,
                            reason = describeToolCall(nc.name, nc.argumentsJson, snippetMax = 300),
                            target = stageInfo.fullTarget,
                            argsSummary = stageInfo.argsDetail,
                            code = stageInfo.code,
                            // 执行结果 / 变更预览：内联卡片直接显示"跑出了什么 / 改了哪几行"
                            output = toolOutputOf(nc.name, result),
                            diff = toolDiffOf(nc.name, nc.argumentsJson)
                        )
                        toolCalls.add(stageRec)
                        // 【崩溃可追溯】这条调用立即写进追加式执行日志：进程随后被 SIGKILL 也留得住。
                        try { AIJournal.tool(stageRec) } catch (_: Throwable) {}
                        // 长任务中断优化(P2):只读/产出跟踪 + 同类工具连续失败检测
                        if (nc.name in LOOP_READ_TOOLS) roundReadOnly = true
                        if (sOk) {
                            if (nc.name in LOOP_WRITE_TOOLS) roundWrote = true
                            sameToolFailStreak = 0
                            lastFailTool = ""
                        } else if (nc.name == lastFailTool) {
                            sameToolFailStreak++
                            if (sameToolFailStreak >= AITaskCompletion.MAX_SAME_TOOL_FAIL_STREAK) {
                                history.add("user" to "")
                                sameToolFailStreak = 0
                            }
                        } else {
                            sameToolFailStreak = 1
                            lastFailTool = nc.name
                        }
                        // 多 AI 元工具:把验收/规划/并行报告块写进消息流(可见的接手记录,随消息持久化)
                        // 报告块内包含子 AI 内部的工具轨迹（哪个文件/哪条命令），保证「一个不漏」
                        if (nc.name == TOOL_AGENT_REVIEW || nc.name == TOOL_AGENT_FORK || nc.name == TOOL_AGENT_PLAN)
                            (when (nc.name) {
                                TOOL_AGENT_REVIEW -> reviewReportBlock(result)
                                TOOL_AGENT_FORK -> forkReportBlock(result)
                                else -> planReportBlock(result)
                            })?.let { blk ->
                            accumulated = accumulated + "\n" + blk
                        }
                        // ask_user 完成:「问/答」记录直接嵌进状态块(点状态行展开),不再单独写「回答内容」块
                        // 调用完成:把同名状态行原地替换为最终态(1 已调用 / 2 失败)
                        accumulated = replaceToolInline(
                            accumulated, nc.name, if (sOk) 1 else 2,
                            if (nc.name == TOOL_ASK_USER) askUserRecordText(result) ?: "" else "",
                            stageInfo.target, stageInfo.argsSummary
                        )
                        onToolStage?.invoke(nc.name, if (sOk) 1 else 2, nc.argumentsJson, toolCallCount, AiChatManager.activeFileChangeRecorder?.size ?: 0, toolCalls.toList())
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
                                if (failStrategyRounds < AITaskCompletion.MAX_FAIL_STRATEGY_ROUNDS) {
                                    failStrategyRounds++
                                    history.add("user" to AITaskCompletion.failStrategyInstruction())
                                    Log.i(TAG, "runAIChatLoop 连续 $consecutiveAllFailRounds 轮工具失败,注入换策略指令(第 $failStrategyRounds 次)")
                                } else {
                                    phase = AITaskCompletion.AITaskPhase.WRAP_UP
                                    wrapUpRounds++
                                    currentToolChoice = null // P1:收尾保留工具(由 wrapUpToolBudget 限额,防死循环)
                                    wrapUpToolBudget = AITaskCompletion.MAX_WRAP_UP_TOOL_ROUNDS
                                    history.add("user" to AITaskCompletion.wrapUpInstruction(toolRounds, ""))
                                    // ⚠️ 必须写清楚是「工具连失败」而不是「轮次预算」：不写清楚的话，
                                    // 用户只看思考链里那句「已达工具轮次预算上限」就会误以为额度设小了
                                    // （用户实测：上限设 300，AI 却说"已达上限 4 轮"，根因就是这句有歧义的文案）。
                                    com.aurora.chat.ModelLogger.warn(ctx, "STOP", "连续 $consecutiveAllFailRounds 轮工具全部失败 → 收尾 | 轮次=$toolRounds 真实上限=$maxToolRounds")
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
                    if (phase == AITaskCompletion.AITaskPhase.WRAP_UP && nativeCalls.isNotEmpty()) {
                        wrapUpToolBudget--
                        if (wrapUpToolBudget <= 0) {
                            currentToolChoice = "none"
                            Log.i(TAG, "runAIChatLoop 收尾阶段工具补救额度耗尽,后续禁工具")
                        }
                    }
                    // 轮末结算(P2):连续只读探索轮数(本轮只读且无产出才累加,有产出即清零)
                    if (roundReadOnly && !roundWrote) readOnlyStreak++ else readOnlyStreak = 0
                    roundReadOnly = false
                    roundWrote = false
                    toolRounds++
                } else {
                    // 文本协议兜底(原生不可用时):@tool:start 块 → 裸 JSON 代码块 → Qwen XML 文本协议
                    var toolCall = extractFirstToolCall(res.fullText)
                    if (toolCall == null) toolCall = extractToolCallFromCodeBlock(res.fullText)
                    if (toolCall == null) toolCall = extractXmlStyleToolCall(res.fullText)
                    if (toolCall != null) {
                        // 多 AI 强制验收跟踪:是否验过收 / 动过工作区 / 复验计数
                        if (toolCall.tool == TOOL_AGENT_REVIEW) { reviewCalled = true; reviewCallCount++ }
                        if (toolCall.tool in REVIEW_REQUIRED_TOOLS) mutatedWorkspace.add(toolCall.tool)
                        // 调用开始:把「正在调用」状态行内联进正文流当前位置(调用真正发生的位置)
                        val stageInfo = toolCallInfo(toolCall.tool, toolCall.argsJson)
                        accumulated = appendToolInline(
                            accumulated, toolCall.tool, 0, stageInfo.target, stageInfo.argsSummary
                        )
                        onToolStage?.invoke(toolCall.tool, 0, toolCall.argsJson, toolCallCount, AiChatManager.activeFileChangeRecorder?.size ?: 0, toolCalls.toList())
                        val callTs = System.currentTimeMillis()
                        val approved = onNeedApproval?.invoke(toolCall.tool, toolCall.argsJson) ?: true
                        val result = if (approved) {
                            if (toolCall.tool == TOOL_AGENT_REVIEW && reviewCallCount > MAX_AGENT_REVIEW_CALLS) reviewCapExceededResult()
                            else executeAITool(ctx, userId, toolCall, appActionHandler)
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
                        emitOpenVideo(toolCall.tool, result, approved)
                        emitCaptureDelivery(toolCall.tool, result, approved)
                        // 文本协议分支此前漏了这一句(只有原生分支接了) → 走文本协议时方案卡片永不勾选
                        emitPlanStepUpdate(toolCall.tool, result, approved)
                        val sOk = try { JSONObject(result).optBoolean("ok") } catch (_: Exception) { false }
                        val stageRec = ChatMsg.ToolStageRecord(
                            tool = toolCall.tool,
                            state = if (sOk) 1 else 2,
                            ts = callTs,
                            reason = describeToolCall(toolCall.tool, toolCall.argsJson, snippetMax = 300),
                            target = stageInfo.fullTarget,
                            argsSummary = stageInfo.argsDetail,
                            code = stageInfo.code,
                            output = toolOutputOf(toolCall.tool, result),
                            diff = toolDiffOf(toolCall.tool, toolCall.argsJson)
                        )
                        toolCalls.add(stageRec)
                        try { AIJournal.tool(stageRec) } catch (_: Throwable) {}
                        // 长任务中断优化(P2):只读/产出跟踪 + 同类工具连续失败检测(文本协议兜底)
                        if (toolCall.tool in LOOP_READ_TOOLS) roundReadOnly = true
                        if (sOk) {
                            if (toolCall.tool in LOOP_WRITE_TOOLS) roundWrote = true
                            sameToolFailStreak = 0
                            lastFailTool = ""
                        } else if (toolCall.tool == lastFailTool) {
                            sameToolFailStreak++
                            if (sameToolFailStreak >= AITaskCompletion.MAX_SAME_TOOL_FAIL_STREAK) {
                                history.add("user" to "")
                                sameToolFailStreak = 0
                            }
                        } else {
                            sameToolFailStreak = 1
                            lastFailTool = toolCall.tool
                        }
                        // 多 AI 元工具:把验收/规划/并行报告块写进消息流(可见的接手记录,随消息持久化)
                        if (toolCall.tool == TOOL_AGENT_REVIEW || toolCall.tool == TOOL_AGENT_FORK || toolCall.tool == TOOL_AGENT_PLAN)
                            (when (toolCall.tool) {
                                TOOL_AGENT_REVIEW -> reviewReportBlock(result)
                                TOOL_AGENT_FORK -> forkReportBlock(result)
                                else -> planReportBlock(result)
                            })?.let { blk ->
                            accumulated = accumulated + "\n" + blk
                        }
                        // ask_user 完成:「问/答」记录直接嵌进状态块(点状态行展开),不再单独写「回答内容」块
                        // 调用完成:把同名状态行原地替换为最终态(1 已调用 / 2 失败)
                        accumulated = replaceToolInline(
                            accumulated, toolCall.tool, if (sOk) 1 else 2,
                            if (toolCall.tool == TOOL_ASK_USER) askUserRecordText(result) ?: "" else "",
                            stageInfo.target, stageInfo.argsSummary
                        )
                        onToolStage?.invoke(toolCall.tool, if (sOk) 1 else 2, toolCall.argsJson, toolCallCount, AiChatManager.activeFileChangeRecorder?.size ?: 0, toolCalls.toList())
                        // === 文本协议兜底：连续失败硬上限（同上,P1 先换策略补救,机会用尽才收尾）===
                        if (!sOk) {
                            consecutiveAllFailRounds++
                            if (consecutiveAllFailRounds >= 3) {
                                if (contractNeeded) {
                                    // 同上方原生分支:不往 accumulated 拼系统提示,只喂 history
                                    if (failStrategyRounds < AITaskCompletion.MAX_FAIL_STRATEGY_ROUNDS) {
                                        failStrategyRounds++
                                        history.add("user" to AITaskCompletion.failStrategyInstruction())
                                        Log.i(TAG, "runAIChatLoop 文本协议连续 $consecutiveAllFailRounds 轮失败,注入换策略指令(第 $failStrategyRounds 次)")
                                    } else {
                                        phase = AITaskCompletion.AITaskPhase.WRAP_UP
                                        wrapUpRounds++
                                        currentToolChoice = null // P1:收尾保留工具(由 wrapUpToolBudget 限额)
                                        wrapUpToolBudget = AITaskCompletion.MAX_WRAP_UP_TOOL_ROUNDS
                                        history.add("user" to AITaskCompletion.wrapUpInstruction(toolRounds, ""))
                                        com.aurora.chat.ModelLogger.warn(ctx, "STOP", "(文本协议)连续 $consecutiveAllFailRounds 轮工具全部失败 → 收尾 | 轮次=$toolRounds 真实上限=$maxToolRounds")
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
                        if (phase == AITaskCompletion.AITaskPhase.WRAP_UP) {
                            wrapUpToolBudget--
                            if (wrapUpToolBudget <= 0) {
                                currentToolChoice = "none"
                                Log.i(TAG, "runAIChatLoop 文本协议收尾阶段工具补救额度耗尽,后续禁工具")
                            }
                        }
                        // 轮末结算(P2):连续只读探索轮数(文本协议分支)
                        if (roundReadOnly && !roundWrote) readOnlyStreak++ else readOnlyStreak = 0
                        roundReadOnly = false
                        roundWrote = false
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
            // 最终兜底:正常收尾时若累加内容仍为空(理论上已被上方各出口堵住,但防万一),
            // 至少给出明确系统提示,杜绝"任务结束却啥也没输出、也没报错"的黑洞。
            if (accumulated.isBlank()) {
                accumulated += if (toolCallCount > 0)
                    "\n" + AITaskCompletion.fallbackDeliveryBlock(toolCalls, fileChanges)
                else
                    "\n〔系统提示〕本次任务 AI 未产生任何可见输出。若你发起了具体任务却看到此提示,请重试或检查 API / 网络。"
            }
            return AILoopOutcome(accumulated, toolRounds, clean = true, lastUsage, lastThinking, finalTotalSeconds, lastUsageDetail, fileChanges, toolCallCount, toolCalls)
        } finally {
            if (ownsRecorder) activeFileChangeRecorder = prevRecorder
            // 收尾（正常完成 / 出错 / 手动停止）→ 日志使命结束，删掉；
            // 只有进程被 SIGKILL 才会走到不这里，文件留下供下次启动恢复。
            if (journalOwner) {
                try { AIJournal.finish(ctx) } catch (_: Throwable) {}
            }
            com.aurora.chat.AITaskGuard.markFinished(ctx)
        }
    }

    // ===== 多 AI 模式:独立验收 AI =====

    /**
     * 验收 AI 的只读工具白名单(硬编码,写操作类一律拒绝——验收者不能篡改现场)。
     *
     * 直接引用 [AIRoles.SUB_AGENT_READ_TOOLS]:过去这份名单与 schema 侧割裂,
     * 形成"模型看得见、调用必被拒"的负向循环。现在执行层准入、工具清单裁剪、以及给模型的提示词
     * 全部由同一份名单推导,不会再度漂移。
     */
    private val REVIEWER_READ_TOOLS = AIRoles.SUB_AGENT_READ_TOOLS

    /**
     * 验收结论的判定词表(**单一事实源**,只服务于 [judgeReviewToken])。
     *
     * 为什么要单独一表:过去判定词内联在 parseReviewVerdict 里,和ِين「行筛选」逻辑缠成一团,
     * 于是"放宽题型"和"收紧范围"两件事互相打架——改一个就误伤另一个。现在词表只管词义、筛选只管语法。
     */
    /** 自带否定的通过词(字面上含「通过/合格/达标」,必须优先于 PASS 表判) */
    private val REVIEW_FAIL_NEGATED_RES = listOf(
        Regex("不通过"), Regex("未通过"), Regex("不合格"), Regex("不达标"), Regex("未达标"),
        Regex("不合规"), Regex("不正确"), Regex("不满足"), Regex("不合格项")
    )
    /** 明确的不通过信号 */
    private val REVIEW_FAIL_RES = listOf(
        Regex("失敗|失败"), Regex("验收失败"), Regex("核验失败"), Regex("打回"), Regex("整改"),
        Regex("存在问题"), Regex("有缺陷"), Regex("有错误"), Regex("不完整"), Regex("缺失"),
        Regex("缺漏"), Regex("未完成"), Regex("不合要求"), Regex("与要求不符"),
        Regex("\\bfail(ed|ure)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\breject(ed)?\\b", RegexOption.IGNORE_CASE)
    )
    /**
     * 明确的通过信号。
     * 刻意不收「无问题 / 没问题 / 正常」这类弱信号:它们是铺垫语气("没问题的话我就交付了"),
     * 收进来会把没下结论的话误读成 PASS。英文用 \b 词界:\bok\b 而非裸 "ok",
     * 否则 "broken"/"looks" 这类词会被撞成通过。
     */
    private val REVIEW_PASS_RES = listOf(
        Regex("通过"), Regex("合格"), Regex("达标"), Regex("符合要求"), Regex("符合"),
        Regex("\\bpass(ed)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bsuccess(ful)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bok\\b", RegexOption.IGNORE_CASE),
        Regex("✅"), Regex("✔"), Regex("✓")
    )

    /** 多 AI 模式下触发强制验收的「动工作区」类工具:执行者用了这些工具就必须走 agent_review */
    val REVIEW_REQUIRED_TOOLS = setOf(
        "write_file", "edit_file", "delete_file", "restore_file", "delete_folder", "rename_file", "copy_file", "append_file",
        TOOL_GIT, TOOL_DOWNLOAD_FILE, TOOL_EXECUTE_PYTHON, TOOL_SHELL_EXEC, TOOL_INSTALL_APK,
        TOOL_AGENT_FORK,   // 并行派发的子任务动了工作区,整体成果同样必须过验收
        TOOL_BATCH_LOOP    // 批量循环内部会执行写入类工具,同样视为动过工作区
    )

    /** 验收 AI 的独立系统提示词(全新上下文,不与执行者共享对话,避免立场污染) */
    // 工具调用系统提示已清理：保留常量名以维持 AI 骨架，内容已清空。
    private const val REVIEWER_SYSTEM_PROMPT = ""
    /**
     * 验收 AI 实时状态总线:doAIReview 各阶段更新(接手 → 每轮核验 → 正在核验的具体动作),
     * UI 端渲染 agent_review「调用中」状态行时收集显示,让用户看见验收 AI 的接手过程。
     * 进程内同一时刻只有一个验收在跑,全局单槽安全。
     */
    /**
     * 验收 / 规划 AI 的实时状态总线(UI 在 agent_review 或 agent_plan「调用中」状态行下方显示)。
     *
     * 【为什么改成多槽】(这里是过去最容易被误读的一处):
     *  - 单槽时代,并行发起的多个验收会互相 **overwrite**,界面只剩最后那一个的文案;
     *  - 更糟的是**先结束的那个**在自己的 finally 里 update(null) 时,会把还在跑的验收状态行直接抹掉
     *    —— 用户看到「验收中」凭空消失,以为验收挂了,其实它还在跑;
     *  - 规划者和验收者共用同一根槽,一个「查询改造方案」的任务里两者穿插出现时文案互相打架。
     *  现在每次调用独占一个 key,对外暴露的文案是**所有存活者**的合并;谁结束谁自己退租,互不影响。
     */
    object AIReviewBus {
        private val slots = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val seq = java.util.concurrent.atomic.AtomicInteger(0)
        private val _live = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val live: kotlinx.coroutines.flow.StateFlow<String?> get() = _live

        /** 租一个本次调用专用的槽;结束时必须 [release],否则状态行永远不清(不会泄漏给下次任务,但界面会残留在横幅里) */
        fun acquire(prefix: String): String = prefix + "#" + seq.incrementAndGet()

        fun update(key: String, s: String?) {
            if (s == null) slots.remove(key) else slots[key] = s
            // 排序后合并:ConcurrentHashMap 的迭代顺序不稳定,不排序会让界面文案在两帧之间乱跳
            val all = slots.toMap().values.toList().sorted()
            _live.value = if (all.isEmpty()) null else all.joinToString(" ｜ ")
        }

        fun release(key: String) = update(key, null)

        /** 兜底清空(理论上用不到:每个槽都有 owner 负责 release) */
        fun clearAll() {
            slots.clear()
            _live.value = null
        }
    }

    /** 用户点「跳过审查」后置位;doAIReview 在轮次边界与流式中途感知并中止验收(SKIPPED 出口) */
    private val reviewSkipFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    /** UI 调用:请求跳过当前正在进行的独立验收(进程内同一时刻只有一个验收,全局单槽安全) */
    fun requestReviewSkip() { reviewSkipFlag.set(true) }

    /** 新一轮 AI 任务开始时清零「跳过审查」请求(上一轮的跳过不残留到下一轮) */
    fun resetReviewSkip() { reviewSkipFlag.set(false) }

    /** 跳过验收的内部控制流异常:仅用于从验收循环里跳出,不外泄 */
    private class ReviewSkippedByUserException : Exception()

    /**
     * 并行派发实时状态总线:doAIFork 派发/子任务完成时更新,
     * UI 端在 agent_fork「调用中」状态行下方收集显示(已完成 n/总数)。
     */
    object AIForkBus {
        private val _live = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val live: kotlinx.coroutines.flow.StateFlow<String?> get() = _live
        private val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile private var total = 0
        fun start(n: Int) {
            total = n
            doneCount.set(0)
            _live.value = "已并行派发 $n 个子执行 AI,同时开工…"
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
     * 多槽队列:并行 AI 同时提问时全部入队,界面一次展示一个(队首),答完自动轮到下一个,
     * 谁都不会被丢弃(旧版单槽会让第二个提问者直接收到失败结果)。
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

        /** 等待中的提问队列(多槽):并行 AI 同时提问时按到达顺序依次呈现 */
        private val _queue = kotlinx.coroutines.flow.MutableStateFlow<List<Request>>(emptyList())
        private val _pending = kotlinx.coroutines.flow.MutableStateFlow<Request?>(null)
        val pending: kotlinx.coroutines.flow.StateFlow<Request?> get() = _pending

        /** 面板收起标记:true=用户主动收起(未提交,提问仍在等待),点消息流里的「正在向用户提问」状态行可重新展开 */
        private val _hidden = kotlinx.coroutines.flow.MutableStateFlow(false)
        val hidden: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _hidden

        /** 收起面板(不提交、不清请求;AI 继续等待作答) */
        fun hide() { _hidden.value = true }

        /** 重新展开面板(带着上次的作答草稿继续) */
        fun reopen() { _hidden.value = false }

        /** 队首同步:界面一次只展示一个提问,队首变化时自动展开(不继承上一个的收起状态) */
        private fun refreshHead() {
            val head = _queue.value.firstOrNull()
            if (_pending.value !== head) {
                _pending.value = head
                _hidden.value = false
            }
        }

        /** 发布提问:入队即可(不再因"已有面板在等待"而失败),界面按到达顺序依次弹出 */
        fun post(r: Request): Boolean {
            if (_queue.value.none { it === r }) _queue.value = _queue.value + r
            refreshHead()
            return true
        }

        /** 结算/撤下某个提问:出队,并自动轮到下一个等待者继续问 */
        fun clear(r: Request) {
            _queue.value = _queue.value.filterNot { it === r }
            refreshHead()
        }
    }

    /**
     * 备份恢复审批总线:restore_backup 工具在任意访问模式下都只"发起恢复请求"并挂起,
     * 聊天界面收集 pending 弹出【红字警告】确认框,用户手动批准后才真正写回工作区;拒绝/关闭则不改任何东西。
     * 单槽:同一时刻只允许一个待批准恢复(并行 AI 场景下第二个会直接失败)。
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
    private suspend fun doPluginList(ctx: Context, userId: Long, call: AIToolCall): String {
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
                .put("message", "当前没有可用插件" + (if (keyword.isNotEmpty()) "(关键词:$keyword)" else "") + "。可提示用户在插件市场安装或让 AI 自建。"))
        }
        return toolOk(JSONObject().put("plugins", arr).put("count", arr.length()))
    }

    /** 调用一个声明式 http_tool 插件:替换 URL 参数 → 发请求 → 按 responsePath 取值返回 */
    private suspend fun doPluginCall(ctx: Context, userId: Long, call: AIToolCall): String {
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

    /** 提交声明式 HTTP 工具插件到插件市场;复用客户端已有的 pluginSubmit 接口 */
    private suspend fun doPluginSubmit(ctx: Context, call: AIToolCall): String {
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

    private suspend fun doAskUser(ctx: Context, userId: Long, call: AIToolCall): String {
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
        // 入队等待:并行场景下排在前面提问者的后面,轮到本提问时面板自动弹出(不再直接失败)
        AskUserBus.post(request)
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
        // 系统提示已清理：不再注入解释指令，直接把中断原文交给模型。
        val history = mutableListOf("user" to notice)
        var result = ""
        var err: String? = null
        streamChat(
            ctx = ctx, userId = userId, history = history,
            onDelta = {},
            onDone = { _, full, _, _ -> result = full },
            onError = { err = it },
            treatEmptyAsError = false,
            stopCheck = { false },
            enableAITools = false
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
            // 系统提示已清理：标题生成器不再注入指令文本。
            val history = mutableListOf("user" to firstMessage.take(500))
            var title = ""
            kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                streamChat(
                    ctx = ctx, userId = userId, history = history,
                    stopCheck = { false },
                    onDelta = {},
                    onDone = { _, full, _, _ -> title = full },
                    onError = {},
                    treatEmptyAsError = false,
                    enableAITools = false
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

        /**
         * 一个正在等待重试的 429 请求。
         *
         * 【为什么从单槽改成多槽】并行子 AI 一起跑时,多个请求会**同时**撞上 429。
         * 旧写法只留一个 resume 句柄 + 一个倒计时 Job:后到的 notify 直接覆盖前一个的句柄,
         * 被覆盖的那个 CompletableDeferred 从此没人负责 complete,只能靠 streamChat 侧
         * `withTimeoutOrNull(wait * 1000)` 傻等到自然超时才重试(整整白等一轮),
         * UI 的倒计时文案也会在两个 AI 之间来回跳、最后可能被别人 reset 掉。
         * 现在每个等待者自带到点唤醒,谁也不会被别人顶掉。
         */
        private class Waiter(
            val deferred: kotlinx.coroutines.CompletableDeferred<Unit>,
            val count: Int,
            val deadlineMs: Long
        )
        private val waiters: MutableList<Waiter> = java.util.Collections.synchronizedList(mutableListOf())
        private val countdownLock = Any()
        private var countdownJob: kotlinx.coroutines.Job? = null
        private val countdownScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
        )

        /** streamChat 429 时调用:count=累计第几次限流(无上限),waitSeconds=本次等待秒数(≤30) */
        fun notify(count: Int, waitSeconds: Long, r: kotlinx.coroutines.CompletableDeferred<Unit>) {
            if (r.isCompleted) return
            val w = Waiter(r, count, System.currentTimeMillis() + waitSeconds * 1000L)
            waiters.add(w)
            // 自带到点唤醒:即使 UI 侧被打断、即使别的等待者插队,这个请求也会准时重试
            countdownScope.launch {
                kotlinx.coroutines.delay(waitSeconds * 1000L)
                release(w)
            }
            ensureCountdown()
        }

        /** 用户点「再次尝试」:取消倒计时,立刻唤醒**所有**等待中的请求重试 */
        fun retryNow() {
            val pending = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
            // 只 complete、不 cancel:defer.cancel() 会让 await() 抛 CancellationException,
            // 结果不是"立刻重试"而是"把这次请求直接掐了",正好与用户点「再试一次」的意图相反。
            pending.forEach { runCatching { it.deferred.complete(Unit) } }
            synchronized(countdownLock) { countdownJob?.cancel(); countdownJob = null }
            _text.value = null
        }

        /**
         * 清理横幅(这条请求已不再等等)。**不主动 complete 别人的等待句柄**:
         * 每个等待者都自带到点唤醒的协程,提前放行会让还没到退避时间的请求又撞一次 429。
         */
        fun clear() {
            synchronized(waiters) { waiters.clear() }
            synchronized(countdownLock) { countdownJob?.cancel(); countdownJob = null }
            _text.value = null
        }

        /** 单个等待者到点(或被清)后出队并刷新文案 */
        private fun release(w: Waiter) {
            runCatching { w.deferred.complete(Unit) }
            waiters.remove(w)
            refreshTextOrStop()
        }

        /** 启动统一的文案 ticker(只有一个,避免多个 job 互相打架,这也是单槽时代的老毛病) */
        private fun ensureCountdown() {
            synchronized(countdownLock) {
                if (countdownJob?.isActive == true) return
                countdownJob = countdownScope.launch {
                    while (true) {
                        val snapshot = synchronized(waiters) { waiters.toList() }
                        if (snapshot.isEmpty()) break
                        val now = System.currentTimeMillis()
                        val leftSec = ((snapshot.minOf { it.deadlineMs } - now) / 1000L).coerceAtLeast(1L)
                        // 并发时不下 N 条横幅:只展示"几个请求在等 + 最近的还剩几秒"
                        _text.value = if (snapshot.size > 1) {
                            "API 被限流(${snapshot.size} 个并行请求在等),$leftSec 秒后自动重试"
                        } else {
                            "API 被限流(第 ${snapshot[0].count} 次),$leftSec 秒后自动重试"
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                    _text.value = null
                }
            }
        }

        private fun refreshTextOrStop() {
            if (waiters.isEmpty()) {
                synchronized(countdownLock) { countdownJob?.cancel(); countdownJob = null }
                _text.value = null
            } else {
                ensureCountdown()
            }
        }
    }

    /** 子 AI 工具轨迹（带具体文件/命令/URL）缩进追加到报告块；空数组时不输出任何内容 */
    private fun appendToolTraceLines(sb: StringBuilder, arr: org.json.JSONArray?, prefix: String = "│  ") {
        if (arr == null || arr.length() == 0) return
        for (i in 0 until arr.length()) {
            val line = arr.optString(i, "").trim()
            if (line.isNotBlank()) sb.append(prefix).append("· ").append(line).append('\n')
        }
    }

    /** 解析 agent_review 的返回结果,生成随消息持久化的「验收 AI 报告」文本块;非验收结果返回 null */
    fun reviewReportBlock(resultJson: String): String? {
        return try {
            val o = JSONObject(resultJson)
            val verdict = o.optString("verdict", "").trim()
            if (verdict.isBlank()) null
            else buildString {
                append("┌ 验收 AI 报告 · 独立核验\n")
                if (verdict == "SKIPPED") {
                    append("├ 结论:用户跳过验收\n")
                    append("├ 核验要点:(未执行任何核验,成果未经独立确认)\n")
                } else {
                    append("├ 结论:$verdict\n")
                    val detail = o.optString("detail", "").replace(Regex("\\s+"), " ").trim()
                    append("├ 核验要点:")
                    append(if (detail.isBlank()) "(验收 AI 未产出细节)" else detail.take(300))
                    append('\n')
                }
                // 验收 AI 内部的每次工具调用（哪个文件 / 哪条命令），一个不漏地随报告持久化
                val trace = o.optJSONArray("tool_trace")
                if (trace != null && trace.length() > 0) {
                    append("├ 工具轨迹(${trace.length()} 条)\n")
                    appendToolTraceLines(this, trace)
                }
                append("└ 独立核验由独立上下文的验收 AI 完成")
            }
        } catch (_: Exception) { null }
    }

    /** 解析 agent_plan 的返回结果,生成「规划 AI 报告」文本块（含其内部工具轨迹）;非规划结果返回 null */
    fun planReportBlock(resultJson: String): String? {
        return try {
            val o = JSONObject(resultJson)
            val plan = o.optString("plan", "").trim()
            val err = o.optString("error", "").trim()
            if (plan.isBlank() && err.isBlank()) null
            else buildString {
                append("┌ 规划 AI 报告 · 独立拆解\n")
                if (plan.isNotBlank()) {
                    append("├ 计划:").append(plan.replace(Regex("\\s+"), " ").take(300)).append('\n')
                } else {
                    append("├ 结论:未产出计划\n")
                }
                val trace = o.optJSONArray("tool_trace")
                if (trace != null && trace.length() > 0) {
                    append("├ 工具轨迹(${trace.length()} 条)\n")
                    appendToolTraceLines(this, trace)
                }
                append("└ 规划仅供参考，实际执行以执行 AI 为准")
            }
        } catch (_: Exception) { null }
    }

    /** 解析 agent_fork 的返回结果,生成随消息持久化的「并行派发报告」文本块;非派发结果返回 null */
    fun forkReportBlock(resultJson: String): String? {
        return try {
            val o = JSONObject(resultJson)
            val arr = o.optJSONArray("results") ?: return null
            buildString {
                append("┌ 并行派发报告 · ${arr.length()} 个子执行 AI\n")
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val task = r.optString("task", "").replace(Regex("\\s+"), " ").trim()
                    val ok = r.optBoolean("ok")
                    append("├ [${i + 1}] ${if (ok) "完成" else "失败"} · ${task.take(80)}\n")
                    if (!ok) {
                        append("│    ${r.optString("summary", "").replace(Regex("\\s+"), " ").trim().take(150)}\n")
                    }
                    // 每个子执行 AI 内部的工具调用（带具体目标）
                    appendToolTraceLines(this, r.optJSONArray("tool_trace"), "│    ")
                }
                append("└ 各子任务详情见执行者的汇总答复")
            }
        } catch (_: Exception) { null }
    }

    /**
     * agent_review 工具实现:启动一个完全独立上下文的验收 AI 对执行者成果做核验。
     * - 验收者权限:只读白名单内全自动(不打扰用户审批),白名单外一律拒绝 → 机制上禁止验收者改现场;
     * - 验收过程对用户不可见(无流式输出),只在执行者的消息流里体现为一个工具调用状态行;
     * - AITaskGuard 为引用计数设计,嵌套调用安全。
     * 返回给执行者的 JSON:ok / verdict(PASS|FAIL|UNVERIFIED) / detail(验收员完整报告)
     */
    private suspend fun doAIReview(ctx: Context, userId: Long, call: AIToolCall): String {
        // 兜底:验收是多 AI 模式专属。非多 AI 模式下(如模型用文本协议硬塞 JSON 调用)直接拒绝。
        if (!execActive(ctx, userId, EXEC_MULTI_AGENT)) {
            return JSONObject().put("ok", false)
                .put("error", "agent_review 仅在多 AI 模式下可用;当前未开启多 AI 模式,无需验收,直接向用户交付结果即可。").toString()
        }
        val args = argsOf(call)
        val task = args.optString("task", "").trim()
        val summary = args.optString("summary", "").trim()
        if (task.isBlank() && summary.isBlank()) {
            return JSONObject().put("ok", false)
                .put("error", "task 与 summary 不能同时为空").toString()
        }
        // 验收者的独立历史:全新上下文。注意:这里【不再】重置跳过标志——用户一旦点「跳过审查」,
        // 本轮任务内所有后续 agent_review 调用都立即被跳过(防模型自作主张再次发起验收,
        // 让用户以为跳过无效、任务一直卡着);标志由新一轮任务开始时(会话页发送路径)统一清零。
        var skippedByUser = false
        val reviewSlot = AIReviewBus.acquire("review")
        AIReviewBus.update(reviewSlot, "验收 AI 已接手 · 建立独立上下文")
        val reviewerHistory = mutableListOf(
            "system" to REVIEWER_SYSTEM_PROMPT,
            "user" to buildString {
                append("原始任务:\n").append(task.ifBlank { "(详见下方工作汇报)" }).append("\n\n")
                append("执行 AI 的工作汇报:\n").append(summary.ifBlank { "(无)" }).append("\n\n")
            }
        )
        var reviewerErr: String? = null
        var reviewRound = 0
        // 验收总时长硬预算:验收 AI 卡流/反复慢轮时,任务绝不被验收环节无限拖死。
        // 注意:真正的兜底已经前置到子循环的**软截止**(见下方 softDeadlineMs)——
        // 软截止会先禁工具催结论,让验收争取在这道硬超时之前自然产出结论;
        // 这道硬超时只在软截止也没救回来时才生效,届时如实报「超时」,绝不谎称「格式不对」。
        var reviewTimedOut = false
        val reviewStartedAt = android.os.SystemClock.elapsedRealtime()
        com.aurora.chat.ModelLogger.info(ctx, "MULTI", "agent_review 开始 | 任务=${task.take(60)}")
        val outcome = try {
            val loopOutcome = kotlinx.coroutines.withTimeoutOrNull(AITaskCompletion.REVIEW_TIMEOUT_MS) {
                // 挂上 REVIEWER 身份:工具清单据此被裁成只读集(模型看不见写工具就不会去试),
                // 万一它用文本协议硬塞工具名,executeAITool 的准入矩阵也会再次拦一道。
                kotlinx.coroutines.withContext(
                    AIPrincipal("reviewer", "验收 AI", AIRoles.AIRole.REVIEWER, 1)
                ) {
                runAIChatLoop(
                ctx = ctx, userId = userId, history = reviewerHistory,
                // 验收者工具门控:只读白名单全自动放行(验收不该骚扰用户),白名单外一律拒绝
                onNeedApproval = { tool, _ -> tool in REVIEWER_READ_TOOLS },
                // 验收者自身不再触发强制验收(否则会要求验收者验收自己)
                enforceReview = false,
                // 验收者轮数硬顶:验收是核验不是重做,5 轮足够;防止模型反复调只读工具无限跑轮
                maxRoundsOverride = 5,
                // 验收是嵌套子循环,不应触发用户级任务守护(交付契约/收尾)
                enableTaskCompletion = false,
                // 墙钟软截止 + 活动轮上限:到点禁工具并催它立刻出结论。
                // 这行是「验收必失败」的核心修复之一——以前只有 300s 硬超时,超时把流式请求从中间掐断,
                // 产出的是半截文本 → 判 UNVERIFIED;现在先留 2.5 分钟让它自然收束出完整结论。
                softDeadlineMs = AITaskCompletion.REVIEW_SOFT_DEADLINE_MS,
                maxActivityRounds = AITaskCompletion.REVIEW_MAX_ACTIVITY_ROUNDS,
                deadlineInstruction = AITaskCompletion.REVIEW_DEADLINE_INSTRUCTION,
                round = { nativeCalls, _, toolChoice ->
                    // 用户点了「跳过审查」:本轮不再发起请求,直接中止整个验收循环
                    if (reviewSkipFlag.get()) throw ReviewSkippedByUserException()
                    reviewRound++
                    AIReviewBus.update(reviewSlot, "第 $reviewRound 轮独立核验中…")
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
                        enableAITools = true,
                        toolChoice = toolChoice,
                        onFinish = { r -> finishReason = r }
                    )
                    if (nativeCalls.isNotEmpty()) {
                        val acts = nativeCalls.joinToString("、") { it.name }
                        AIReviewBus.update(reviewSlot, "正在核验:${acts.take(46)}")
                    }
                    // 本轮结束后再次感知跳过(流式已被掐断时 done=false,同样走跳过出口)
                    if (reviewSkipFlag.get()) throw ReviewSkippedByUserException()
                    AIRoundResult(done = done && !err, fullText = ft, finishReason = finishReason, retriable = if (err) AITaskCompletion.isRetriable(lastErrMsg) else false, errorText = if (err) lastErrMsg else "")
                }
                )
                }
                // ← 旧写法在这里多补了一个 `?: run { reviewTimedOut = true; null }`:
                //   runAIChatLoop 返回的是**非空** AILoopOutcome,Elvis 右支永不执行,reviewTimedOut 恒为 false。
                //   后果:真超时时 outcome==null 却被引向「验收 AI 未按规定格式输出结论」,排查方向彻底跑偏。
                //   现在直接由 withTimeoutOrNull 的返回值判定。
            }
            if (loopOutcome == null) reviewTimedOut = true
            loopOutcome
        } catch (e: ReviewSkippedByUserException) {
            skippedByUser = true
            null
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            android.util.Log.e("AiChatManager", "agent_review 验收循环异常", e)
            com.aurora.chat.ModelLogger.error(ctx, "MULTI", "agent_review 验收循环异常: ${e.message}", e)
            reviewerErr = e.message ?: e.javaClass.simpleName
            null
        } finally {
            AIReviewBus.release(reviewSlot)
        }
        val reviewerText = outcome?.accumulated?.trim().orEmpty()
        val reviewElapsed = (android.os.SystemClock.elapsedRealtime() - reviewStartedAt) / 1000L
        val verdict = when {
            skippedByUser -> "SKIPPED"
            outcome == null || reviewerText.isBlank() -> "UNVERIFIED"
            // 宽容解析:不再只认死格式「验收结论:PASS/FAIL」,见 parseReviewVerdict 注释
            else -> parseReviewVerdict(reviewerText)
        }
        com.aurora.chat.ModelLogger.info(
            ctx, "MULTI",
            "agent_review 结束 | 结论=$verdict 耗时=${reviewElapsed}s 超时=$reviewTimedOut 轮数=$reviewRound 输出长度=${reviewerText.length}"
        )
        return JSONObject().apply {
            put("ok", verdict != "UNVERIFIED")
            put("verdict", verdict)
            when (verdict) {
                "PASS" -> put("hint", "PASS")
                "FAIL" -> put("hint", "FAIL")
                "SKIPPED" -> put("hint", "SKIPPED")
                else -> put("hint", "UNVERIFIED")
            }
            put("detail", reviewerText.take(6000))
            // 验收 AI 内部的工具调用轨迹（每个文件/命令都带具体目标），父层报告块据此展示
            put("tool_trace", org.json.JSONArray().apply {
                agentToolTraceLines(outcome?.toolCalls ?: emptyList()).forEach { put(it) }
            })
        }.toString()
    }

    /**
     * 从验收 AI 输出文本中宽容解析结论:PASS / FAIL / UNVERIFIED。
     *
     * ## 为什么要重写(这是"验收必失败"的另一半根因)
     * 旧实现对**所有**含「结论 / verdict / result / 验收」的行做倒序扫描,只要其中**任意一行**
     * 命中一个否定词就整体判 FAIL。而模型在【核验过程】里写「第 3 项不满足要求」这类**客观描述**
     * 同样会命中,于是明明整体通过也被打成 FAIL —— FAIL 率被抬到近乎 100%,用户看到的就是验收必失败。
     *
     * 现在改成**结构性判定优先,措辞仅作兜底**:
     *  ⓪ 机器标记 `[[VERDICT:PASS|FAIL]]` —— 确定性通道,提示词已强制要求,命中即采信;
     *  ① 结构性结论句 —— 只认「标签 + 冒号 + 极短判定词」这种**专门用来宣布结论的行**,
     *     并且跳过带引号的行(那是模型在复述格式模板,不是在下结论);
     *  ② 短句兜底 —— 只扫含结论题材词且整行 ≤80 字的短句;
     *  ③ 再不中就判 UNVERIFIED —— **宁可如实说"不可判定",也不凭一句措辞判 FAIL**。
     *     执行者收到 UNVERIFIED 走如实告知 + 自行判断,不会陷入"修→再验→再 FAIL"的消耗战。
     */
    private fun parseReviewVerdict(text: String): String {
        // ⓪ 确定性通道:取**最后一次**出现,避免正文里引用格式说明时被误命中。
        val marker = Regex("""\[\[\s*VERDICT\s*[:：]\s*(PASS|FAIL)\s*\]\]""", RegexOption.IGNORE_CASE)
        marker.findAll(text).lastOrNull()?.let { return it.groupValues[1].uppercase() }

        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        // 结论题材词:只有提到它,这一行才可能是在下结论(把路线级别的叙事排除在外)。
        val topicRe = Regex("(验收|核验|审查|检查|验证|判定|结论|verdict|result)", RegexOption.IGNORE_CASE)
        // 「标签 + 冒号」标签位,用于①识别结构性结论句。
        val labelRe = Regex(
            "(验收结论|核验结论|验收结果|核验结果|检查结果|判定结果|审查结论|结论|结果|verdict|result)\\s*(?:是)?\\s*[:：]",
            RegexOption.IGNORE_CASE
        )

        // ① 结构性结论句:标签 + 冒号 + 极短判定词。
        //    必须是**极短**的尾巴:`PASS`、`不通过`、`通过 ✅` 这种;
        //    「核验结果:xxx.md 存在占位符,需修改」这种长长的逐项描述不算 —— 那是过程不是结论。
        var sawStructuralPass = false
        for (line in lines) {
            // 跳过带引号的行:模型在复述「第一行写 验收结论:PASS 或 验收结论:FAIL」这类格式说明,
            // 这种行同时含 PASS 与 FAIL 判据,不跳过就会被稳稳读成 FAIL(修复前的真实误命中)。
            if (line.contains("「") || line.contains("」") || line.contains("\"")) continue
            val m = labelRe.find(line) ?: continue
            val tail = line.substring(m.range.last + 1).trim()
            if (tail.length > 16) continue
            when (judgeReviewToken(tail)) {
                "FAIL" -> return "FAIL"
                "PASS" -> sawStructuralPass = true
            }
        }
        if (sawStructuralPass) return "PASS"

        // ② 短句兜底:结论题材词 + 整行 ≤80 字。FAIL 优先(只要有明确的不通过声明就认),
        //    范围比旧实现窄得多(旧实现扫描任意长度的所有含关键词行,FAIL 偏置就出在那)。
        var sawPass = false
        for (line in lines.asReversed()) {
            if (line.length > 80) continue
            if (!topicRe.containsMatchIn(line)) continue
            when (judgeReviewToken(line)) {
                "FAIL" -> return "FAIL"
                "PASS" -> sawPass = true
                else -> {}
            }
        }
        if (sawPass) return "PASS"

        // ③ 末段关键句:只取末尾 600 字符里、含结论题材词的短句(≤80 字),从后往前找第一个能判定的。
        val tailLines = text.takeLast(600).lines().map { it.trim() }.filter { it.isNotBlank() }
        for (line in tailLines.asReversed()) {
            if (line.length > 80) continue
            if (!topicRe.containsMatchIn(line)) continue
            judgeReviewToken(line)?.let { return it }
        }
        return "UNVERIFIED"
    }

    /** 判定一小段话是在说「通过」还是「不通过」;两者都读不出时返回 null(不猜)。 */
    private fun judgeReviewToken(seg: String): String? {
        // 否定清洗走**单一事实源**(与交付状态推导 deriveDeliverableStatus 同一套),
        // 保证「未发现不合格项」「无任何问题」「均正常」不会被当成 FAIL 信号。
        val cleaned = AITaskCompletion.stripFailureNegations(seg.lowercase())
        // 先查"自带否定的通过词":不通过 / 未通过 / 不合格 / 未达标 …
        // 这些词字面上包含「通过」「合格」,必须先判,否则会被下面的 PASS 分支抢走。
        if (REVIEW_FAIL_NEGATED_RES.any { it.containsMatchIn(cleaned) }) return "FAIL"
        if (REVIEW_FAIL_RES.any { it.containsMatchIn(cleaned) }) return "FAIL"
        if (REVIEW_PASS_RES.any { it.containsMatchIn(cleaned) }) return "PASS"
        return null
    }

    /** 规划者系统提示词:只读权限,只出计划不动手;格式固定,便于执行者逐条执行 */
    // 工具调用系统提示已清理：保留常量名以维持 AI 骨架，内容已清空。
    private const val PLANNER_SYSTEM_PROMPT = ""

    /**
     * agent_plan 工具实现:把任务交给一个完全独立上下文的规划 AI 拆解成执行计划。
     * 规划者只有只读白名单权限(与验收者同一套门控),产出纯文本计划返回给执行者;
     * 规划失败/信息不足时如实返回,不编造计划。
     */
    private suspend fun doAIPlan(ctx: Context, userId: Long, call: AIToolCall): String {
        // 兜底:规划是多 AI 模式专属,非多 AI 模式直接拒绝(防文本协议绕过)。
        if (!execActive(ctx, userId, EXEC_MULTI_AGENT)) {
            return JSONObject().put("ok", false)
                .put("error", "agent_plan 仅在多 AI 模式下可用").toString()
        }
        val args = argsOf(call)
        val task = args.optString("task", "").trim()
        if (task.isBlank()) {
            return JSONObject().put("ok", false).put("error", "task 不能为空:请传入用户的原始任务描述").toString()
        }
        val planSlot = AIReviewBus.acquire("plan")
        AIReviewBus.update(planSlot, "规划 AI 已接手 · 建立独立上下文")
        val plannerHistory = mutableListOf(
            "system" to PLANNER_SYSTEM_PROMPT,
            "user" to task
        )
        var plannerErr: String? = null
        var planRound = 0
        var planTimedOut = false
        val planStartedAt = android.os.SystemClock.elapsedRealtime()
        com.aurora.chat.ModelLogger.info(ctx, "MULTI", "agent_plan 开始 | 任务=${task.take(60)}")
        val outcome = try {
            // 规划子循环此前**既无轮数上限、也无墙钟超时**,而多 AI 模式下 maxToolRoundsFor 是 Int.MAX_VALUE
            // —— 规划者只要不肯收手,整通会话就吊在"规划中"。这里与验收/派发同构地补上三层预算。
            val loopOutcome = kotlinx.coroutines.withTimeoutOrNull(AITaskCompletion.PLAN_TIMEOUT_MS) {
                // 挂上 PLANNER 身份:工具清单据此裁成只读集,模型看不见写工具就不会去试。
                kotlinx.coroutines.withContext(
                    AIPrincipal("planner", "规划 AI", AIRoles.AIRole.PLANNER, 1)
                ) {
            runAIChatLoop(
                ctx = ctx, userId = userId, history = plannerHistory,
                // 规划者与验收者同一套只读白名单门控;自身不触发强制验收/规划
                onNeedApproval = { tool, _ -> tool in REVIEWER_READ_TOOLS },
                enforceReview = false,
                // 规划是嵌套子循环,不应触发用户级任务守护(交付契约/收尾)
                enableTaskCompletion = false,
                // 规划只做勘察:8 轮足够;不设的话这里是 Int.MAX_VALUE。
                maxRoundsOverride = 8,
                softDeadlineMs = AITaskCompletion.PLAN_SOFT_DEADLINE_MS,
                maxActivityRounds = AITaskCompletion.PLAN_MAX_ACTIVITY_ROUNDS,
                deadlineInstruction = AITaskCompletion.PLAN_DEADLINE_INSTRUCTION,
                round = { nativeCalls, _, toolChoice ->
                    planRound++
                    AIReviewBus.update(planSlot, "第 $planRound 轮规划中…")
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
                        enableAITools = true,
                        toolChoice = toolChoice,
                        onFinish = { r -> finishReason = r }
                    )
                    if (nativeCalls.isNotEmpty()) {
                        val acts = nativeCalls.joinToString("、") { it.name }
                        AIReviewBus.update(planSlot, "正在勘察:${acts.take(46)}")
                    }
                    AIRoundResult(done = done && !err, fullText = ft, finishReason = finishReason, retriable = if (err) AITaskCompletion.isRetriable(lastErrMsg) else false, errorText = if (err) lastErrMsg else "")
                }
            )
                }
            }
            if (loopOutcome == null) {
                planTimedOut = true
                com.aurora.chat.ModelLogger.warn(
                    ctx, "MULTI",
                    "agent_plan 超时(${AITaskCompletion.PLAN_TIMEOUT_MS / 1000}s)未完成"
                )
            }
            loopOutcome
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            android.util.Log.e("AiChatManager", "agent_plan 规划循环异常", e)
            com.aurora.chat.ModelLogger.error(ctx, "MULTI", "agent_plan 规划循环异常: ${e.message}", e)
            plannerErr = e.message ?: e.javaClass.simpleName
            null
        } finally {
            AIReviewBus.release(planSlot)
        }
        val planElapsed = (android.os.SystemClock.elapsedRealtime() - planStartedAt) / 1000L
        com.aurora.chat.ModelLogger.info(ctx, "MULTI", "agent_plan 结束 | 耗时=${planElapsed}s 轮数=$planRound 有效=${outcome != null}")
        val planText = outcome?.accumulated?.trim().orEmpty()
        val insufficient = planText.replace(" ", "").startsWith("【执行计划】信息不足")
        return JSONObject().apply {
            put("ok", outcome != null && planText.isNotBlank() && !insufficient)
            when {
                plannerErr != null -> {
                    put("error", "规划 AI 运行失败($plannerErr)")
                }
                insufficient -> {
                    put("error", "规划 AI 判定信息不足")
                    put("detail", planText.take(6000))
                }
                planTimedOut -> {
                    put("error", "规划 AI 超时")
                }
                planText.isBlank() -> {
                    put("error", "规划 AI 未产出计划")
                }
                else -> {
                    put("plan", planText.take(6000))
                    put("hint", "")
                }
            }
            // 规划 AI 内部的勘察轨迹（读了哪些文件、搜了什么），供父层报告块展示
            put("tool_trace", org.json.JSONArray().apply {
                agentToolTraceLines(outcome?.toolCalls ?: emptyList()).forEach { put(it) }
            })
        }.toString()
    }

    /** 并行子执行者的系统提示词:专注单个子任务,不碰元工具,只在允许的路径范围内动手 */
    // 工具调用系统提示已清理：保留常量名以维持 AI 骨架，内容已清空。
    private const val FORK_WORKER_PROMPT = ""
    /**
     * agent_fork 工具实现:把多个互不依赖的子任务并行派发给独立上下文的执行 AI(多协程并发)。
     * - 每个子执行者:全新上下文 + 全套工具(写权限),仅受 FORK_WORKER_PROMPT 约束;
     *   审批在派发动作本身(agent_fork 调用)发生,子任务内部免逐个审批,否则单槽审批桥会互相覆盖。
     * - 子执行者不做验收(enforceReview=false),调度者拿到汇总后统一走 agent_review。
     * - 上限 4 个并发,防止请求风暴触发限流。
     */
    private suspend fun doAIFork(ctx: Context, userId: Long, call: AIToolCall): String {
        // 兜底:并行派发是多 AI 模式专属,非多 AI 模式直接拒绝(防文本协议绕过)。
        if (!execActive(ctx, userId, EXEC_MULTI_AGENT)) {
            return JSONObject().put("ok", false)
                .put("error", "agent_fork 仅在多 AI 模式下可用").toString()
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
        AIForkBus.start(n)
        com.aurora.chat.ModelLogger.info(ctx, "MULTI", "agent_fork 派发 $n 个子任务")
        // 并行写入感知/冲突拦截:先把全部子 AI 登记进共享状态表(≥2 个时才启用机制),
        // 每个子执行者再挂上协程级身份,工具层据此判定"谁在写哪个路径"与"这个身份能用哪些工具"。
        (0 until n).forEach { i -> AISharedWorkspace.register("fork-$i", "子任务${i + 1}") }
        // 全部子任务共用一个改动收集器(父层那一个),谁都不许再去抢模块级单槽 ——
        // 否则四个并发子循环各自 assign,只有最后抢到的那个能收到记录。
        val forkRecorder = activeFileChangeRecorder
        val results = try {
            // 【为什么必须是 supervisorScope】
            // 旧写法用 coroutineScope:任一子协程失败 → 整个作用域取消,
            // **其余已经干完活的子任务结果被一并丢弃**,而且第二次 `.map { await() }` 连回退的机会都没有
            // —— 一个子任务出意外,等于这一次派发整体白干。supervisorScope 让每个子任务各担其责,
            // 单个失败降级为"这条子任务失败",其余照常汇总;这才是并行派发该有的语义。
            kotlinx.coroutines.supervisorScope {
                (0 until n).map { i ->
                    async(AIPrincipal("fork-$i", "子任务${i + 1}", AIRoles.AIRole.WORKER, 1)) {
                        try {
                            val startedAt = android.os.SystemClock.elapsedRealtime()
                            val worker = runForkWorker(ctx, userId, tasks.optString(i).trim(), forkRecorder)
                            val elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
                            val timedOut = worker == null
                            if (worker == null) {
                                com.aurora.chat.ModelLogger.warn(
                                    ctx, "MULTI",
                                    "agent_fork 子任务 ${i + 1} 超时/失败 | 耗时=${elapsedMs / 1000}s"
                                )
                            } else {
                                com.aurora.chat.ModelLogger.info(
                                    ctx, "MULTI",
                                    "agent_fork 子任务 ${i + 1} 完成 | 耗时=${elapsedMs / 1000}s 工具=${worker.second.size} 输出=${worker.first.length}字"
                                )
                            }
                            AIForkBus.workerDone()
                            JSONObject()
                                .put("index", i)
                                .put("task", tasks.optString(i).trim())
                                .put("ok", worker != null && worker.first.isNotBlank())
                                .put(
                                    "summary",
                                    when {
                                        timedOut -> "(子执行 AI 超过 ${AITaskCompletion.FORK_WORKER_TIMEOUT_MS / 1000} 秒未完成,已按失败处理;已与其兄弟子任务的成果一并汇总)"
                                        worker == null || worker.first.isBlank() -> "(子执行 AI 无输出,视为失败)"
                                        else -> worker.first.take(1500)
                                    }
                                )
                                .put("timed_out", timedOut)
                                .put("tool_trace", JSONArray().apply {
                                    agentToolTraceLines(worker?.second ?: emptyList()).forEach { put(it) }
                                })
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            // 用户停止 / 父任务取消:必须原样抛出,否则并行子任务会变成"停不掉的野协程"。
                            // supervisorScope 只隔离子协程失败向上传播,不阻断父作业的取消向下传播,所以这里抛是对的。
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("AiChatManager", "agent_fork 子任务 ${i + 1} 执行异常", e)
                            com.aurora.chat.ModelLogger.error(ctx, "MULTI", "agent_fork 子任务 ${i + 1} 执行异常: ${e.message}", e)
                            AIForkBus.workerDone()
                            JSONObject()
                                .put("index", i)
                                .put("task", tasks.optString(i).trim())
                                .put("ok", false)
                                .put("summary", "(子执行 AI 异常中断:${e.message ?: e.javaClass.simpleName})")
                                .put("tool_trace", JSONArray())
                        }
                    }
                }.awaitAll()
            }
        } finally {
            AIForkBus.clear()
            // 注销全部子 AI:最后一个退出时自动清空共享写入状态,不跨任务残留
            (0 until n).forEach { i -> AISharedWorkspace.unregister("fork-$i") }
        }
        val failed = results.filter { !it.optBoolean("ok") }.map { it.optInt("index") + 1 }
        val timedOut = results.filter { it.optBoolean("timed_out") }.map { it.optInt("index") + 1 }
        com.aurora.chat.ModelLogger.info(
            ctx, "MULTI",
            "agent_fork 汇总 | 总数=$n 失败=${failed.joinToString("、").ifBlank { "无" }} 超时=${timedOut.joinToString("、").ifBlank { "无" }}"
        )
        return JSONObject().apply {
            put("ok", failed.isEmpty())
            put("results", JSONArray().apply { results.forEach { put(it) } })
            put("hint", if (failed.isEmpty()) "全部完成($n)" else "失败:${failed.joinToString("、")} 超时:${timedOut.joinToString("、")}")
        }.toString()
    }

    /**
     * 跑一个并行子执行者。**墙钟硬超时 + 轮数上限 + 活动上限三层预算**都在里面。
     *
     * 为什么必须补这三样:子执行者是嵌套子循环,而多 AI 模式下 maxToolRoundsFor 返回 Int.MAX_VALUE、
     * enableTaskCompletion=false(没有软预算收尾)、stopCheck 恒 false(中途停不了)——
     * 合起来就是一个子任务失控能把整通会话吊死,这才是"多 AI 几乎走不通"最直接的一条腿。
     *
     * @return 子任务产出(正文 + 内部工具轨迹);超时或失败返回 null(由调用方降级为"这一条失败")。
     */
    private suspend fun runForkWorker(
        ctx: Context, userId: Long, taskDesc: String, fileChangeSink: MutableList<FileChange>?
    ): Pair<String, List<ChatMsg.ToolStageRecord>>? {
        val workerHistory = mutableListOf(
            "system" to FORK_WORKER_PROMPT,
            "user" to taskDesc
        )
        return kotlinx.coroutines.withTimeoutOrNull(AITaskCompletion.FORK_WORKER_TIMEOUT_MS) {
            try {
                runAIChatLoop(
                    ctx = ctx, userId = userId, history = workerHistory,
                    // 派发动作本身已经过审批(或非手动模式),子任务内部免逐个审批
                    onNeedApproval = { _, _ -> true },
                    // 子执行者不做验收兜底,调度者统一验收
                    enforceReview = false,
                    // 子执行者是嵌套子循环,不应触发用户级任务守护(交付契约/收尾)
                    enableTaskCompletion = false,
                    // 轮数上限:子任务应当是"一小份活",15 轮工具足够;不设的话多 AI 模式是 Int.MAX_VALUE。
                    maxRoundsOverride = AITaskCompletion.FORK_WORKER_MAX_ROUNDS,
                    // 墙钟软截止 + 活动轮上限:先禁工具催它写汇报,再让它自然收束。
                    // 硬超时在调用侧;软截止在这里,避免 hard kill 产出半截文本。
                    softDeadlineMs = AITaskCompletion.FORK_WORKER_SOFT_DEADLINE_MS,
                    maxActivityRounds = AITaskCompletion.FORK_WORKER_MAX_ACTIVITY_ROUNDS,
                    deadlineInstruction = AITaskCompletion.FORK_WORKER_DEADLINE_INSTRUCTION,
                    // 改动记录统一落到父层那一个列表里:并行子任务各自 assign 模块级单槽会互相丢记录
                    fileChangeSink = fileChangeSink,
                    round = { nativeCalls, _, toolChoice ->
                        var done = false
                        var ft = ""
                        var err = false
                        var finishReason = ""
                        var lastErrMsg = ""
                        streamChat(
                            ctx = ctx, userId = userId, history = workerHistory,
                            // 停止信号不在这里:用户停止会一路取消协程树,CancellationException 直接把子任务带走,
                            // 比轮询一个布尔量更及时、也更不容易留下"停不掉的野协程"。
                            stopCheck = { false },
                            onReasoningDelta = {},
                            onDelta = {},
                            onDone = { _, full, _, _ -> done = true; ft = full },
                            onError = { err = true; lastErrMsg = it },
                            nativeToolAccumulator = nativeCalls,
                            enableAITools = true,
                            toolChoice = toolChoice,
                            onFinish = { r -> finishReason = r }
                        )
                        AIRoundResult(done = done && !err, fullText = ft, finishReason = finishReason, retriable = if (err) AITaskCompletion.isRetriable(lastErrMsg) else false, errorText = if (err) lastErrMsg else "")
                    }
                ).let { Pair(it.accumulated.trim(), it.toolCalls) }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                android.util.Log.e("AiChatManager", "agent_fork 子执行循环异常", e)
                com.aurora.chat.ModelLogger.error(ctx, "MULTI", "agent_fork 子执行循环异常: ${e.message}", e)
                null
            }
        }
    }

    // ===== 生成模式:图片 / 视频生成(personal 直连用户自己的 key;third_party/paid_api/free_api 走后端 /api/ai/image|video,与对话模型解耦) =====
    private const val IMAGE_MODEL = "agnes-image-2.5-flash"
    private const val VIDEO_MODEL = "agnes-video-v2.0"
    /** agnes-image-2.5-flash 官方宽高比白名单（与 size 档位配合，默认 1:1）。 */
    private val IMAGE_RATIOS = listOf("1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9")
    /** 图生图/多图合成参考图上限（官方价格表第 4 张起才计费，UI 侧收敛到 4 张内）。 */
    private const val MAX_REFERENCE_IMAGES = 4

    /**
     * 本地直连生图：图片生成在**客户端**完成，不经过平台后端。
     *
     * 背景：generate_media 是本地执行的工具，但对话模型（DeepSeek 等）本身没有生图端点。
     * 旧实现把这类情况甩给后端 /api/ai/image，导致「服务端配没配密钥」直接决定本地工具能不能用
     * （曾出现后端环境变量丢失后生图全量 503、而客户端毫无线索）。图片生成与对话模型解耦后，
     * 客户端用内置的 Agnes 生图密钥直连上游，工具在本地闭环，与后端状态无关。
     *
     * 仅用于「对话上游不支持生图」的场景；paid/free 中转与 Agnes 直连仍走各自既有路径。
     */
    private const val LOCAL_IMAGE_BASE = "https://api.example.com/v1"
    // 本地生图密钥不再内置：反编译 APK 即可提取，等于公开送密钥。
    // 如需启用本地直连生图，请改为从安全来源（如后端下发）在运行时注入。
    private val LOCAL_IMAGE_KEYS: List<String> = emptyList()
    /** 本地生图密钥轮询下标：多 key 分摊限流，单个失效时靠重试切下一个。 */
    private val localImageKeyIdx = java.util.concurrent.atomic.AtomicInteger(0)

    /** 取一个本地生图密钥（轮询起点），返回从该位置开始的完整轮换序列。 */
    private fun localImageKeyOrder(): List<String> {
        if (LOCAL_IMAGE_KEYS.isEmpty()) return emptyList()
        val start = (localImageKeyIdx.getAndIncrement() % LOCAL_IMAGE_KEYS.size + LOCAL_IMAGE_KEYS.size) % LOCAL_IMAGE_KEYS.size
        return LOCAL_IMAGE_KEYS.drop(start) + LOCAL_IMAGE_KEYS.take(start)
    }

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
        ctx: Context, userId: Long, prompt: String, kind: String, size: String,
        ratio: String = "", images: List<String> = emptyList()
    ): GenResult {
        val mode = getMode(ctx, userId)
        // 生图三路径,按「上游是否原生支持生图」判定,不按对话模式硬编码:
        //  - 直连(AI 自己来):personal 模式上游是支持 images/generations 的生图平台
        //    (目前仅 Agnes 系列域名),用用户自己的 key 调上游,资源/成本归 key 方;
        //  - 中转(密钥不下发):paid_api/free_api 模式上游是 Agnes 时,走后端中转端点
        //    /api/paid-api/image 或 /api/free-api/image——上传者的 Key 只在服务端持有,
        //    由后端直连其上游。既不能客户端直连(那要求 available 明文下发 key),
        //    也不能落到平台的 Agnes 生图密钥上(那笔成本会凭空由平台承担);
        //  - 代理(工具兜底):其余上游(如 DeepSeek 等对话平台)没有生图端点,直连只会收到上游的
        //    "不支持/401 token 不可用"类错误,统一走平台后端 /api/ai/image(Agnes 生图模型),与对话模型解耦。
        // 说明:视频生成仅第三方模式可用(generateVideo 入口已限制),paid/free 不涉及视频中转。
        val paidFreeUpstream: String = if (mode == "paid_api" || mode == "free_api") {
            val (_, u, _) = if (mode == "paid_api") getPaidApiConfig(ctx, userId) else getFreeApiConfig(ctx, userId)
            u
        } else ""
        val relayImage = kind == "image" && (mode == "paid_api" || mode == "free_api") &&
            (paidFreeUpstream.contains("agnes-ai.com") || paidFreeUpstream.contains("agnes-ai.cn"))
        val (key, baseUrl, _) = resolveConfig(ctx, userId)
        val model = if (kind == "video") VIDEO_MODEL else IMAGE_MODEL
        val body = org.json.JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            if (kind == "image") {
                put("size", size)
                // agnes-image-2.5-flash 官方参数规范（文档：agnes-ai.cn/zh-Hans/docs/agnes-image-25-flash）：
                // ① response_format 禁止放请求体顶层，必须放 extra_body 内（顶层放置是文档点名的错误写法）；
                // ② 文生图 Base64 输出用 return_base64:true；图生图 Base64 输出用 extra_body.response_format:"b64_json"；
                // ③ 图生图/多图合成的参考图放 extra_body.image 数组（公共 URL 或 Data URI Base64）。
                // （2.1 时代"return_base64 会报错"的经验已作废：2.5 文档明确推荐文生图使用它。）
                val extra = org.json.JSONObject()
                if (images.isNotEmpty()) {
                    val arr = org.json.JSONArray()
                    images.forEach { arr.put(it) }
                    extra.put("image", arr)
                    extra.put("response_format", "b64_json")
                } else {
                    put("return_base64", true)
                }
                if (ratio.isNotBlank()) put("ratio", ratio)
                if (extra.length() > 0) put("extra_body", extra)
            }
            // 中转端点需要平台侧标识(后端据此查表定位条目);base_url/model_name 仅作
            // 客户端本地 id 失效时后端兜底匹配,不携带任何上游密钥。
            if (relayImage) {
                put("api_id", if (mode == "paid_api") getPaidApiId(ctx, userId) else getFreeApiId(ctx, userId))
                if (paidFreeUpstream.isNotBlank()) put("base_url", paidFreeUpstream)
                val (_, _, m) = if (mode == "paid_api") getPaidApiConfig(ctx, userId) else getFreeApiConfig(ctx, userId)
                if (m.isNotBlank()) put("model_name", m)
            }
        }
        val endpoint: String
        val proxyGenerated: Boolean
        // 本地直连判定：对话上游（DeepSeek 等）没有生图端点时，图片在客户端直连 Agnes，
        // 不再甩给后端 —— 工具是本地执行的，不该由服务端配置决定它能不能用。
        val localImage = kind == "image" && !relayImage &&
            !(baseUrl.contains("agnes-ai.com") || baseUrl.contains("agnes-ai.cn"))
        val authCandidates: List<String>
        when {
            relayImage -> {
                endpoint = AuroraApi.serverUrl.trimEnd('/') +
                    (if (mode == "paid_api") "/api/paid-api/image" else "/api/free-api/image")
                authCandidates = listOf(AuroraApi.authToken.orEmpty())
                proxyGenerated = false
            }
            baseUrl.contains("agnes-ai.com") || baseUrl.contains("agnes-ai.cn") -> {
                endpoint = baseUrl.trimEnd('/') + (if (kind == "image") "/images/generations" else "/video/generations")
                authCandidates = listOf(key)
                proxyGenerated = false
            }
            localImage -> {
                endpoint = LOCAL_IMAGE_BASE.trimEnd('/') + "/images/generations"
                authCandidates = localImageKeyOrder()
                proxyGenerated = false
            }
            else -> {
                endpoint = AuroraApi.serverUrl.trimEnd('/') + (if (kind == "image") "/api/ai/image" else "/api/ai/video")
                authCandidates = listOf(AuroraApi.authToken.orEmpty())
                proxyGenerated = true
            }
        }
        var code = 0
        var respText = ""
        var lastFailure: RuntimeException? = null
        // 多密钥轮换：本地生图有 3 个内置 key，任一失效/限流自动切下一个；单 key 路径即一次尝试。
        for ((attemptIdx, attemptKey) in authCandidates.withIndex()) {
            val req = okhttp3.Request.Builder().url(endpoint)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer $attemptKey")
                .build()
            // 生成是慢接口(可达 60s+),用独立长读超时客户端,避免共享客户端 15s 读超时
            val resp = try {
                withContext(Dispatchers.IO) { genClient.newCall(req).execute() }
            } catch (e: Exception) {
                lastFailure = RuntimeException(
                    "生成${if (kind == "image") "图片" else "视频"}请求失败: ${e.message}", e
                )
                com.aurora.chat.ErrorReporter.warn(
                    "AiGen", "请求异常(${attemptIdx + 1}/${authCandidates.size}): ${e.message}"
                )
                continue
            }
            code = resp.code
            respText = try { resp.body?.string() ?: "" } catch (_: Exception) { "" }
            resp.close()
            if (code in 200..299) {
                lastFailure = null
                break
            }
            lastFailure = RuntimeException("生成${if (kind == "image") "图片" else "视频"}失败($code): ${respText.take(200)}")
            com.aurora.chat.ErrorReporter.warn(
                "AiGen", "HTTP $code(${attemptIdx + 1}/${authCandidates.size}) ${respText.take(160).replace("\n", " ")}"
            )
            // 只有鉴权失效/限流/5xx 才值得换下一个 key；其它 4xx 重试无意义
            if (code != 401 && code != 403 && code != 429 && code < 500) break
        }
        if (code !in 200..299) {
            throw lastFailure ?: RuntimeException("生成${if (kind == "image") "图片" else "视频"}失败: 无可用密钥")
        }
        var arr: org.json.JSONArray? = null
        try {
            val root = org.json.JSONObject(respText)
            var holder = root
            if (proxyGenerated && !root.isNull("data") && root.opt("data") is org.json.JSONObject) {
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
                if (proxyGenerated && !root.isNull("data") && root.opt("data") is org.json.JSONObject) {
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

    suspend fun generateImage(
        ctx: Context, userId: Long, prompt: String, size: String = "1024x1024",
        ratio: String = "", images: List<String> = emptyList()
    ): GenResult =
        requestGeneration(ctx, userId, prompt, "image", size, ratio, images)

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
