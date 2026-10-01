package com.aurora.chat.ui.chat

import android.content.Context
import androidx.core.content.ContextCompat
import com.aurora.chat.R
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.MessageInfo
import com.aurora.chat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 本地特殊对话（个人对话 / DeepSeek / 通知中心）数据变化信号。
 * 会话列表按时间排序的 remember 块以它为 key，本地新增消息时自增，触发重排上浮。
 */
object LocalChatRefreshTick {
    val flow = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun bump() { flow.value++ }
}

internal data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

// ==================== 本地对话（自己 / AI）公共逻辑 ====================
// 自己对话 friendId=0；AI 对话为固定内置的本地对话（friendId=AI_CHAT_ID），不做任何服务端交互
const val AI_CHAT_ID = -2L
// 通知系统：固定内置对话（friendId=NOTIFICATION_CHAT_ID），既非个人对话也非群聊，用户不可发消息
const val NOTIFICATION_CHAT_ID = -3L

/**
 * 从消息 JSON 解析 AI 工具调用记录列表。
 * 优先读新字段 ai_tool_stages（每次调用追加留存的完整历史）；
 * 旧数据无该字段时回退到单值字段 ai_tool_stage / ai_tool_stage_state。
 */
fun parseToolStages(obj: org.json.JSONObject): List<com.aurora.chat.ui.chat.ChatMsg.ToolStageRecord> {
    val arr = obj.optJSONArray("ai_tool_stages")
    if (arr != null) {
        val out = mutableListOf<com.aurora.chat.ui.chat.ChatMsg.ToolStageRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tool = o.optString("tool", "")
            if (tool.isEmpty()) continue
            out.add(com.aurora.chat.ui.chat.ChatMsg.ToolStageRecord(tool, o.optInt("state", 2), o.optLong("ts", 0)))
        }
        if (out.isNotEmpty()) return out
    }
    val legacy = obj.optString("ai_tool_stage", "")
    return if (legacy.isNotEmpty()) {
        listOf(com.aurora.chat.ui.chat.ChatMsg.ToolStageRecord(legacy, obj.optInt("ai_tool_stage_state", -1)))
    } else {
        emptyList()
    }
}

/**
 * 把服务器返回的媒体路径拼成客户端可达的完整 URL。
 * 后端现在返回相对路径（/chat-media/xxx），必须用客户端已验证可达的 serverUrl 拼接；
 * 否则若后端在反向代理后、直接用 r.Host 拼出的 http://localhost:8080/... 手机端无法访问，
 * 表现为图片一直加载中转圈 / 空气泡，且对方设备永不可见。
 */
fun resolveMediaUrl(raw: String): String {
    if (raw.isEmpty()) return raw
    val base = com.aurora.chat.data.api.AuroraApi.serverUrl.trimEnd('/')
    if (raw.startsWith("http")) {
        // 后端在反向代理后可能返回内网 host（如 http://localhost:8080/chat-media/...），
        // 手机端无法访问。只要路径含 /chat-media/，就用客户端已知可达的 serverUrl 重写 host。
        val marker = "/chat-media/"
        val idx = raw.indexOf(marker)
        if (idx >= 0) return "$base${raw.substring(idx)}"
        return raw
    }
    // 本地文件 / content 协议原样返回：AI 对话、个人对话本地上传的图片（file://）以及
    // content:// 原图预览等，不能拼上服务器 host，否则会被拼成非法的 http://.../file:///... 导致加载失败。
    if (raw.startsWith("file://") || raw.startsWith("content://")) return raw
    return "$base/${raw.removePrefix("/")}"
}

/**
 * 「另存为」的底层 IO：把源文件（本地 file:// 直接读磁盘，远程 http/https 直接拉取）数据写入目标 Uri。
 * 返回 null 表示成功；非 null 为失败原因文案。需在 IO 线程调用。
 */
fun performSaveAsIo(ctx: android.content.Context, uri: android.net.Uri, url: String): String? {
    return try {
        val out = ctx.contentResolver.openOutputStream(uri, "w") ?: return "无法打开目标位置"
        try {
            if (url.startsWith("file://")) {
                val src = java.io.File(url.removePrefix("file://"))
                if (!src.exists()) return "源文件不存在"
                src.inputStream().use { inp -> inp.copyTo(out) }
            } else {
                val req = okhttp3.Request.Builder().url(url).build()
                com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return "下载失败"
                    val body = resp.body?.bytes()
                    if (body == null) return "下载内容为空"
                    out.write(body)
                }
            }
        } finally {
            try { out.close() } catch (_: Exception) {}
        }
        null
    } catch (e: Exception) {
        (e.message ?: "保存失败")
    }
}
// AI 回复占位（流式返回前显示），不计入发给模型的上下文
const val AI_THINKING = "思考中…"

/**
 * 「另存为」的 UI 层：在 scope 协程内把源文件写入用户选择的 Uri，成功后标记该源地址为「已保存」。
 * 放在顶层以避免被可组合函数内部的后置声明的局部函数前向引用问题。
 */
fun performSaveAs(
    scope: kotlinx.coroutines.CoroutineScope,
    ctx: android.content.Context,
    uri: android.net.Uri,
    url: String,
    fileName: String
) {
    scope.launch {
        var ok = false
        var failMsg = ""
        try {
            val internalErr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                performSaveAsIo(ctx, uri, url)
            }
            if (internalErr == null) {
                SavedFiles.mark(ctx, url)
                ok = true
            } else {
                failMsg = internalErr
            }
        } catch (e: Exception) {
            failMsg = e.message ?: "保存失败"
        }
        if (ok) {
            android.widget.Toast.makeText(ctx, "已保存：$fileName", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(ctx, "保存失败：${failMsg.ifEmpty { "未知错误" }}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

fun isLocalChat(friendId: Long): Boolean = friendId == 0L || friendId == AI_CHAT_ID
fun isGroupChat(friendId: Long): Boolean = friendId < 0 && friendId != AI_CHAT_ID && friendId != NOTIFICATION_CHAT_ID
fun isNotificationChat(friendId: Long): Boolean = friendId == NOTIFICATION_CHAT_ID
fun localChatKey(friendId: Long): String = if (friendId == AI_CHAT_ID) AiConversationSession.storageKey() else "self"

// ==================== AI 多对话（会话）管理 ====================
// 主聊天列表仍只有一个固定的 AI 入口（AI_CHAT_ID），其内部的对话(coversation)可多个：
// 每个对话拥有独立的 message 存储文件与标题，通过右上角「对话列表」抽屉切换/管理。
/** 默认（首个）AI 对话的固定会话 ID：其旧数据存于 ai_chat_{userId}.json（兼容历史数据） */
const val DEFAULT_AI_CONV_ID = 1L

/** AI 多对话元数据（仅内存/偏好存储，不落消息文件） */
data class AiConvMeta(
    val id: Long,
    var title: String,
    val createdAt: Long
)

/**
 * 当前 AI 会话的运行时状态。
 * activeConvId 由进入 AI 对话 / 切换会话 / 新建会话时写入，作为：
 *   ① 消息存储动态 key 的来源（storageKey()）；
 *   ② 后台回复隔离的会话 ID（后台回复携带当时的 activeConvId，仅当仍为当前会话才渲染）。
 * 默认指向默认会话（key="ai"），保证未初始化时也能安全读写历史数据。
 */
object AiConversationSession {
    @Volatile var activeConvId: Long = DEFAULT_AI_CONV_ID

    /** 当前会话的消息存储 key：默认会话沿用 "ai" 兼容历史文件，其余会话为 "ai_{id}" */
    fun storageKey(): String = if (activeConvId == DEFAULT_AI_CONV_ID) "ai" else "ai_${activeConvId}"
}

private fun aiConvsPrefs(ctx: android.content.Context): android.content.SharedPreferences =
    ctx.getSharedPreferences("ai_convs", android.content.Context.MODE_PRIVATE)

/** 读取全部 AI 会话元数据（按创建时间降序,最新在前,与列表「新建置顶」语义一致）。 */
fun loadAiConvs(ctx: android.content.Context): MutableList<AiConvMeta> {
    val raw = aiConvsPrefs(ctx).getString("list", "") ?: ""
    return try {
        val out = mutableListOf<AiConvMeta>()
        val arr = org.json.JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(AiConvMeta(
                id = o.optLong("id", o.optLong("i", 0L)),
                title = o.optString("title", "新对话"),
                createdAt = o.optLong("created_at", o.optLong("t", 0L))
            ))
        }
        if (out.isEmpty()) mutableListOf(AiConvMeta(DEFAULT_AI_CONV_ID, "新对话", System.currentTimeMillis()))
        else out.apply { sortByDescending { it.createdAt } } // 降序:最新在前
    } catch (_: Exception) {
        mutableListOf(AiConvMeta(DEFAULT_AI_CONV_ID, "新对话", System.currentTimeMillis()))
    }
}

/**
 * 读取某个 AI 会话预览（最后一条消息文本）。
 * 复用在 saveLocalChatMessages 中已落盘的「每条会话最后一条消息」缓存：
 * SharedPreferences 文件名为 "${key}_chat_${userId}"，键 last_msg（已加密），配合 last_time。
 * 默认会话 key="ai"，其余会话 key="ai_{id}"。无内容时返回空串。
 */
fun getAiConvPreview(ctx: android.content.Context, currentUserId: Long, convId: Long): String {
    val key = if (convId == DEFAULT_AI_CONV_ID) "ai" else "ai_$convId"
    val prefs = ctx.getSharedPreferences("${key}_chat_${currentUserId}", android.content.Context.MODE_PRIVATE)
    val enc = prefs.getString("last_msg", "") ?: ""
    if (enc.isBlank()) return previewFromMessageFile(ctx, currentUserId, key)
    val raw = try { com.aurora.chat.CryptoUtil.decryptLocal(enc) } catch (_: Exception) { "" }
    // 清洗思考/工具哨兵：哨兵形如 \u0001T\u0001...\u0001/T\u0001(\u0001 不可见)，未清洗时预览会以
    // 「T」(思考)或「W」(工具) 开头。这里只保留正文；旧版本落盘的脏数据也能立即恢复正常。
    val prose = AiChatManager.splitInlineBlocksV2(raw)
        .filterIsInstance<AiChatManager.InlineBlock.Text>()
        .joinToString(" ") { it.content }
        .trim()
    // prefs 预览缺失/解密失败时兜底:直接读会话消息文件取最后一条正文,自愈而非显示「暂无消息」
    if (prose.isBlank()) return previewFromMessageFile(ctx, currentUserId, key)
    return prose
}

/** 预览兜底:从会话消息文件(从新到旧)找最后一条有正文的非工具消息;媒体消息显示占位文案 */
private fun previewFromMessageFile(ctx: android.content.Context, currentUserId: Long, key: String): String {
    return try {
        val arr = loadLocalChatMessages(ctx, currentUserId, key) ?: return ""
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("is_tool_status", false)) continue
            val media = o.optString("media_type", "")
            if (media.isNotEmpty()) {
                return when (media) { "image" -> "[图片]"; "video" -> "[视频]"; else -> "[文件]" }
            }
            val prose = AiChatManager.splitInlineBlocksV2(o.optString("text", ""))
                .filterIsInstance<AiChatManager.InlineBlock.Text>()
                .joinToString(" ") { it.content }
                .trim()
            if (prose.isNotEmpty()) return prose
        }
        ""
    } catch (_: Exception) { "" }
}

private fun saveAiConvs(ctx: android.content.Context, list: List<AiConvMeta>) {
    try {
        val arr = org.json.JSONArray()
        for (m in list) {
            arr.put(org.json.JSONObject().apply {
                put("id", m.id); put("title", m.title); put("created_at", m.createdAt)
            })
        }
        aiConvsPrefs(ctx).edit().putString("list", arr.toString()).apply()
    } catch (_: Exception) {}
}

/** 当前激活的会话 ID（无记录/越界时回退默认会话并修复）。 */
fun getActiveAiConvId(ctx: android.content.Context): Long {
    val id = aiConvsPrefs(ctx).getLong("active", -1L)
    val list = loadAiConvs(ctx)
    if (list.any { it.id == id }) return id
    // loadAiConvs 现为降序(最新在前):回退到最早创建的会话(列表末尾)保持原语义
    val fallback = list.lastOrNull()?.id ?: DEFAULT_AI_CONV_ID
    aiConvsPrefs(ctx).edit().putLong("active", fallback).apply()
    return fallback
}

private fun setActiveAiConvId(ctx: android.content.Context, id: Long) {
    aiConvsPrefs(ctx).edit().putLong("active", id).apply()
    AiConversationSession.activeConvId = id
}

/** 新建一个 AI 会话（标题先用「新对话」，发首条消息后自动替换为首条用户消息）并置为当前。 */
fun createAiConversation(ctx: android.content.Context): AiConvMeta {
    val list = loadAiConvs(ctx)
    var newId = System.currentTimeMillis()
    while (list.any { it.id == newId }) newId++
    val meta = AiConvMeta(newId, "新对话", newId)
    list.add(0, meta) // 新会话插到列表头部:列表为「最新在前」序,新建后即时置顶
    saveAiConvs(ctx, list)
    setActiveAiConvId(ctx, newId)
    return meta
}

/** 重命名某个会话。 */
fun renameAiConversation(ctx: android.content.Context, id: Long, title: String) {
    val list = loadAiConvs(ctx)
    val t = title.trim().ifEmpty { "新对话" }
    list.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { list[it] = list[it].copy(title = t) }
    saveAiConvs(ctx, list)
}

/** 将某个会话标记为当前并返回其消息存储 key（供加载用）。 */
fun switchAiConversation(ctx: android.content.Context, id: Long): String {
    setActiveAiConvId(ctx, id)
    return AiConversationSession.storageKey()
}

/** 删除一个会话（连同其消息文件）。删除后自动切换到剩余会话；全部删空则重建默认会话。 */
fun deleteAiConversation(ctx: android.content.Context, id: Long) {
    val list = loadAiConvs(ctx)
    val removed = list.indexOfFirst { it.id == id }
    if (removed >= 0) list.removeAt(removed)
    if (list.isEmpty()) {
        list.add(0, AiConvMeta(DEFAULT_AI_CONV_ID, "新对话", System.currentTimeMillis()))
    }
    saveAiConvs(ctx, list)
    // 删除该会话的消息文件（新位置 filesDir + 旧位置 cacheDir 一并清理）
    try {
        val prefixPrefix = if (id == DEFAULT_AI_CONV_ID) "ai_chat_" else "ai_${id}_chat_"
        for (dir in listOf(ctx.filesDir, ctx.cacheDir)) {
            dir.listFiles()?.filter { it.name.startsWith(prefixPrefix) }?.forEach { try { it.delete() } catch (_: Exception) {} }
        }
    } catch (_: Exception) {}
    // 若删的是当前会话，切到第一个剩余会话
    if (AiConversationSession.activeConvId == id) {
        // 列表为降序(最新在前),最后一个即最早创建的会话;切换回最早会话保持原语义
        switchAiConversation(ctx, list.last().id)
    }
}
/**
 * 把工具名+参数 JSON 翻译成一句简短的中文操作描述，供「权限审批」弹窗展示。
 * 例如 delete_file(path="a.txt") → "将删除文件：a.txt"。
 * 对未知工具回退到该工具的内置说明（AI 视角的用途解释），并限制总字数不超过 80。
 */
fun describeToolCall(tool: String, argsJson: String): String {
    val args = try { if (argsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(argsJson) } catch (_: Exception) { org.json.JSONObject() }
    fun p(key: String): String = args.optString(key, "").trim()
    fun prev(s: String, max: Int = 30): String =
        s.replace(Regex("\\s+"), " ").let { if (it.length > max) it.take(max) + "…" else it }
    val desc = when (tool) {
        "agent_review" -> {
            val task = p("task")
            "提交工作成果给独立验收 Agent 核验" + (if (task.isBlank()) "" else "：${prev(task, 40)}")
        }
        "agent_fork" -> {
            val tasks = try { args.optJSONArray("tasks") } catch (_: Exception) { null }
            val n = tasks?.length() ?: 0
            "并行派发 $n 个子执行 Agent 同时干活" + if (n > 0) "：${prev(tasks?.optString(0) ?: "", 40)} 等" else ""
        }
        "agent_plan" -> {
            val task = p("task")
            "把任务交给独立规划 Agent 拆解执行计划" + (if (task.isBlank()) "" else "：${prev(task, 40)}")
        }
        "ask_user" -> {
            val qs = try { args.optJSONArray("questions") } catch (_: Exception) { null }
            val n = qs?.length() ?: 0
            "向你提出 $n 个问题,等待你在面板中作答" +
                (if (n > 0) "：${prev(qs?.optJSONObject(0)?.optString("question") ?: "", 30)}" else "")
        }
        "list_files" -> {
            val path = p("path").ifEmpty { "工作区根目录" }
            "列出目录「$path」下的文件"
        }
        "read_file" -> {
            val path = p("path").ifEmpty { "（未指定路径）" }
            "读取文件：$path"
        }
        "write_file" -> {
            val path = p("path").ifEmpty { "（未指定路径）" }
            val content = p("content")
            if (content.isBlank()) "创建/覆盖写入文件：$path"
            else "写入文件：$path（${prev(content)}）"
        }
        "append_file" -> {
            val path = p("path").ifEmpty { "（未指定路径）" }
            val content = p("content")
            if (content.isBlank()) "向文件 $path 追加内容"
            else "向文件 $path 追加：${prev(content)}"
        }
        "delete_file" -> {
            val path = p("path").ifEmpty { "（未指定路径）" }
            "将删除文件：$path"
        }
        "create_folder" -> {
            val path = p("path").ifEmpty { "（未指定路径）" }
            "将创建目录：$path"
        }
        "delete_folder" -> {
            val path = p("path").ifEmpty { "（未指定路径）" }
            "将删除目录：$path"
        }
        "rename_file" -> {
            val path = p("path").ifEmpty { "（未指定）" }
            val newName = p("new_name").ifEmpty { "（未指定）" }
            "重命名 $path → $newName"
        }
        "copy_file" -> {
            val src = p("source").ifEmpty { "（未指定）" }
            val dst = p("target").ifEmpty { "（未指定）" }
            "复制 $src → $dst"
        }
        "stat_file" -> {
            val path = p("path").ifEmpty { "（未指定路径）" }
            "查看 $path 的详细信息"
        }
        "find_files" -> {
            val kw = p("keyword").ifEmpty { "（未指定关键字）" }
            val path = p("path").ifEmpty { "工作区根目录" }
            "在 $path 中搜索含「$kw」的文件"
        }
        "query_environment" -> "查询设备运行环境信息（型号、系统版本、root 状态等）"
        "host_status" -> "查看虚拟主机连接状态（是否已登录、权限等级、是否在主机视图）"
        "host_list" -> { val p = p("path").ifEmpty { "站点根目录" }; "列出虚拟主机目录「$p」下的文件" }
        "host_read" -> "读取虚拟主机文件：" + p("path").ifEmpty { "（未指定路径）" }
        "host_write" -> "在虚拟主机写入文件：" + p("path").ifEmpty { "（未指定路径）" }
        "host_append" -> "向虚拟主机文件追加内容：" + p("path").ifEmpty { "（未指定路径）" }
        "host_delete_file" -> "将删除虚拟主机文件：" + p("path").ifEmpty { "（未指定）" }
        "host_create_folder" -> "在虚拟主机创建目录：" + p("path").ifEmpty { "（未指定）" }
        "host_delete_folder" -> "将删除虚拟主机目录：" + p("path").ifEmpty { "（未指定）" }
        "host_rename" -> "重命名虚拟主机文件 ${p("path").ifEmpty { "（未指定）" }} → ${p("new_name").ifEmpty { "（未指定）" }}"
        "host_copy" -> "复制虚拟主机文件 ${p("source").ifEmpty { "（未指定）" }} → ${p("target").ifEmpty { "（未指定）" }}"
        "host_stat" -> "查看虚拟主机文件信息：" + p("path").ifEmpty { "（未指定）" }
        "host_find" -> "在虚拟主机搜索「" + p("keyword").ifEmpty { "（未指定）" } + "」"
        "host_backup_list" -> "列出虚拟主机站点备份"
        "host_backup_create" -> "为虚拟主机创建站点备份快照"
        "host_backup_restore" -> "将恢复虚拟主机备份：" + p("name").ifEmpty { "（未指定）" }
        AiChatManager.TOOL_GET_FILE -> {
            val path = p("path").ifEmpty { "（未指定路径）" }
            "取出文件 $path 并发送给你"
        }
        AiChatManager.TOOL_EXECUTE_PYTHON -> {
            val code = p("code").ifEmpty { p("content") }
            if (code.isBlank()) "在设备上执行一段 Python 代码"
            else "执行 Python 代码：${prev(code, 50)}"
        }
        AiChatManager.TOOL_SWITCH_TAB -> {
            "切换主界面到「${p("tab").ifEmpty { "（未指定）" }}」页"
        }
        AiChatManager.TOOL_SEND_MESSAGE -> {
            val targetType = p("target_type").ifEmpty { "?" }
            val content = p("content")
            "以你的身份给${if (targetType == "group") "群" else "好友"}(ID=${p("target_id").ifEmpty { "?" }})发送消息：${prev(content, 40)}"
        }
        AiChatManager.TOOL_SEND_FRIEND_REQUEST -> {
            "向用户(ID=${p("user_id").ifEmpty { "?" }})发送好友申请"
        }
        AiChatManager.TOOL_CREATE_POST -> {
            "发布社区帖子《${prev(p("title").ifEmpty { "（未指定标题）" }, 30)}》"
        }
        AiChatManager.TOOL_LIST_CONTACTS -> "列出你的全部好友"
        AiChatManager.TOOL_LIST_GROUPS -> "列出你加入的全部群聊"
        AiChatManager.TOOL_JSON_PROCESS -> {
            val action = p("action").ifEmpty { "beautify" }
            "本地处理 JSON(动作=$action)"
        }
        AiChatManager.TOOL_REGEX_TEST -> "本地测试正则表达式:${prev(p("pattern").ifEmpty { "（未填）" }, 20)}"
        AiChatManager.TOOL_ENCODE_CONVERT -> {
            "本地编码转换:${p("action").ifEmpty { "?" }}"
        }
        AiChatManager.TOOL_HASH_DIGEST -> "本地计算${p("algorithm").ifEmpty { "?" }}摘要"
        AiChatManager.TOOL_TIMESTAMP_CONVERT -> "本地时间戳转换(${p("direction").ifEmpty { "?" }})"
        AiChatManager.TOOL_TEXT_STATS -> "本地文本统计分析"
        AiChatManager.TOOL_SIZE_CONVERT -> "本地大小单位换算(${p("direction").ifEmpty { "?" }})"
        AiChatManager.TOOL_TAKE_SCREENSHOT -> {
            val n = p("name")
            "对当前屏幕截图" + if (n.isNotEmpty()) "(文件名 $n)" else ""
        }
        AiChatManager.TOOL_OPEN_APP -> {
            "打开应用「${p("target").ifEmpty { "（未指定）" }}」"
        }
        AiChatManager.TOOL_EXIT_APP -> {
            if (p("mode") in listOf("kill", "exit", "close", "force", "彻底", "关闭", "结束", "杀掉"))
                "彻底退出 Aurora Chat（结束进程）"
            else "退出 Aurora Chat（退到后台，进程保留）"
        }
        AiChatManager.TOOL_PHONE_ACCESS -> "检查/申请无障碍权限（操控手机）"
        AiChatManager.TOOL_PHONE_SCREEN -> "读取当前手机屏幕内容"
        AiChatManager.TOOL_PHONE_TAP -> {
            val t = p("text")
            if (t.isNotEmpty()) (if (p("long") == "true") "长按屏幕上的「$t」" else "点击屏幕上的「$t」")
            else (if (p("long") == "true") "长按屏幕坐标(${p("x")}, ${p("y")})" else "点击屏幕坐标(${p("x")}, ${p("y")})")
        }
        AiChatManager.TOOL_PHONE_SWIPE -> "在屏幕上滑动（${p("from_x")},${p("from_y")} → ${p("to_x")},${p("to_y")}）"
        AiChatManager.TOOL_PHONE_TYPE -> "往输入框写入文本（${p("text").take(20)}）"
        AiChatManager.TOOL_PHONE_KEY -> "发送系统按键：${p("key")}"
        AiChatManager.TOOL_WAIT -> {
            val s = p("seconds")
            val m = p("minutes")
            "延迟等待 " + when {
                s.isNotEmpty() -> "$s 秒"
                m.isNotEmpty() -> "$m 分钟"
                else -> "(未指定时长)"
            }
        }
        AiChatManager.TOOL_KEEP_ALIVE -> {
            if (p("on") == "false") "关闭后台保活"
            else "开启后台保活" +
                    (if (p("mode") == "invisible") "(隐形窗)" else "(可见小窗" +
                            (if (p("text").isNotEmpty()) "「${p("text")}」" else "") + ")") +
                    when {
                        p("x").isNotEmpty() || p("y").isNotEmpty() ->
                            "(位置 ${p("x").ifEmpty { "默认" }}dp, ${p("y").ifEmpty { "默认" }}dp)"
                        p("position").isNotEmpty() ->
                            "(" + com.aurora.chat.AiKeepAliveService.anchorLabel(
                                com.aurora.chat.AiKeepAliveService.normalizeAnchor(p("position"))
                                    ?: com.aurora.chat.AiKeepAliveService.ANCHOR_TOP_RIGHT
                            ) + ")"
                        else -> ""
                    } +
                    if (p("background") == "true") "并把应用退到后台" else ""
        }
        else -> toolDescription(tool)
    }
    return if (desc.length > 80) desc.take(80) + "…" else desc
}

/** 未知工具时回退：返回工具的内置用途说明（AI 视角下该工具是干什么的），做简短化处理 */
private fun toolDescription(tool: String): String {
    return when (tool) {
        else -> "执行工具操作：$tool"
    }
}

/**
 * 用本机记录的"真实媒体类型"覆盖后端返回的误标类型（后端会把所有文件统一标成 image/jpg）。
 * 仅对本机已发送且记录过类型的消息生效；未记录时原样返回。
 */
fun ChatMsg.withKnownMediaType(): ChatMsg {
    val known = com.aurora.chat.data.local.LocalMediaTypeStore.get(id)
    return if (known != null && known.isNotEmpty() && known != mediaType) copy(mediaType = known) else this
}

/**
 * 依据文件名扩展名推断媒体类型（比 contentResolver.getType 可靠，避免 APK/TXT 等被误判成 image）。
 * 只把【确定】的图片/视频扩展名归为 image/video，其余一律归为普通文件。
 */
fun guessMediaMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp", "3gpp" -> "video/3gpp"
        "mpg", "mpeg" -> "video/mpeg"
        else -> "application/octet-stream"
    }
}

/**
 * 本地会话消息 JSON 的存储文件。
 * 【重要】必须放 filesDir 而非 cacheDir——cacheDir 会被系统在存储紧张时清理、也会被清理软件
 * 一键清空,聊天记录跟着全没(历史上真实发生过)。发现旧 cacheDir 文件还在时自动迁移过来。
 */
fun localChatMessagesFile(ctx: android.content.Context, currentUserId: Long, key: String): java.io.File {
    val f = java.io.File(ctx.filesDir, "${key}_chat_${currentUserId}.json")
    if (f.exists()) return f
    // 旧版本把会话 JSON 放 cacheDir:存在则一次性迁移到 filesDir,历史记录得以保留
    val legacy = java.io.File(ctx.cacheDir, "${key}_chat_${currentUserId}.json")
    if (legacy.exists()) {
        try { legacy.copyTo(f, overwrite = true) } catch (_: Exception) { return legacy }
    }
    return f
}

fun loadLocalChatMessages(ctx: android.content.Context, currentUserId: Long, key: String): org.json.JSONArray? {
    val f = localChatMessagesFile(ctx, currentUserId, key)
    if (!f.exists()) return null
    return try {
        val arr = org.json.JSONArray(f.readText())
        // 统一在读取入口解密 text（兼容旧明文与 loc:v1: 密文）
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val raw = o.optString("text", "")
            o.put("text", com.aurora.chat.CryptoUtil.decryptLocal(raw))
        }
        arr
    } catch (_: Exception) { null }
}

// 保存本地对话消息：写 JSON 文件（撤回/读取权威存储）+ 同步最后一条消息到 SharedPreferences（会话列表预览）
fun saveLocalChatMessages(ctx: android.content.Context, currentUserId: Long, key: String, msgs: List<IMessageRef>) {
    try {
        val arr = org.json.JSONArray()
        var lastMsg = ""
        var lastTime = 0L
        for (m in msgs) {
            // 工具状态行不再落盘:任务收尾时会动画收回,持久层统一排除——
            // 中途停止/崩溃等异常路径遗留的状态行也不会在重进会话后出现(信息已汇聚到底部按钮)
            if ((m as? com.aurora.chat.ui.chat.ChatMsg)?.isToolStatus == true) continue
            // 预览只取「正文」：排除工具状态行(其 text 是工具名,如 create_folder)与思考块,
            // 否则会话列表预览会显示成 create_folder 这类工具名而不是 AI 说的话。
            if ((m as? com.aurora.chat.ui.chat.ChatMsg)?.isToolStatus != true) {
                val plain = AiChatManager.splitInlineBlocksV2(m.text)
                    .filterIsInstance<AiChatManager.InlineBlock.Text>()
                    .joinToString(" ") { it.content }
                    .trim()
                if (plain.isNotEmpty()) { lastMsg = plain; lastTime = m.createdAt }
            }
            if (m.mediaType.isNotEmpty()) {
                lastMsg = when (m.mediaType) {
                    "image" -> "[图片]"; "video" -> "[视频]"; else -> "[文件]"
                }
                lastTime = m.createdAt
            }
            val obj = org.json.JSONObject()
            obj.put("id", m.id)
            obj.put("text", com.aurora.chat.CryptoUtil.encryptLocal(m.text))
            obj.put("is_mine", m.isMine)
            obj.put("from_user_id", m.fromUserId)
            obj.put("sender_name", m.senderName)
            obj.put("revoked", m.isRevoked)
            obj.put("created_at", m.createdAt)
            obj.put("media_type", m.mediaType)
            obj.put("media_url", m.mediaUrl)
            obj.put("reasoning_text", m.reasoningText)
            obj.put("thinking_seconds", m.thinkingSeconds)
            obj.put("total_seconds", m.totalSeconds)
            obj.put("ai_tool_stage", (m as? com.aurora.chat.ui.chat.ChatMsg)?.aiToolStage ?: "")
            obj.put("ai_tool_stage_state", (m as? com.aurora.chat.ui.chat.ChatMsg)?.aiToolStageState ?: -1)
            obj.put("is_tool_status", (m as? com.aurora.chat.ui.chat.ChatMsg)?.isToolStatus ?: false)
            // 工具调用完整记录列表：每次调用追加留存(含发起时刻毫秒时间戳,供「工具调用」全屏清单展示)
            obj.put("ai_tool_stages", org.json.JSONArray().apply {
                (m as? com.aurora.chat.ui.chat.ChatMsg)?.aiToolStages?.forEach { rec ->
                    put(org.json.JSONObject().put("tool", rec.tool).put("state", rec.state).put("ts", rec.ts))
                }
            })
            // AI 文件改动记录（「查看所有改动」全屏界面数据源，JSON 字符串，空表示无）
            obj.put("ai_file_changes", (m as? com.aurora.chat.ui.chat.ChatMsg)?.aiFileChanges ?: "")
            // AI 工具调用次数（底部信息行「调用 N 个工具」）
            obj.put("ai_tool_call_count", (m as? com.aurora.chat.ui.chat.ChatMsg)?.aiToolCallCount ?: 0)
            arr.put(obj)
        }
        localChatMessagesFile(ctx, currentUserId, key).writeText(arr.toString())
        val prefs = ctx.getSharedPreferences("${key}_chat_${currentUserId}", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("last_msg", com.aurora.chat.CryptoUtil.encryptLocal(lastMsg))
            .putLong("last_time", lastTime).apply()
        // 同步进程内缓存，让会话列表的 last_time 读取保持 O(1)，不再全量解析 JSON
        localLastTimeCache["$currentUserId:$key"] = lastTime
        LocalChatRefreshTick.bump()
    } catch (_: Exception) {}
}

/**
 * 撤回媒体消息时清理物理文件与 Coil 缓存（best-effort，不抛异常）。
 * - file:// 本地文件：直接删除
 * - 远程 URL：按实际请求使用的缩略 URL 与原 URL 清理 Coil 磁盘/内存缓存
 */
fun deleteMediaFile(ctx: android.content.Context, rawMediaUrl: String) {
    if (rawMediaUrl.isEmpty()) return
    val resolved = resolveMediaUrl(rawMediaUrl)
    if (resolved.startsWith("file://")) {
        try { java.io.File(resolved.removePrefix("file://")).delete() } catch (_: Exception) {}
    }
    try {
        val loader = coil.Coil.imageLoader(ctx)
        val thumb = com.aurora.chat.ui.chat.media.chatMediaThumbUrl(resolved)
        for (url in listOf(thumb, resolved)) {
            // 内存缓存：Key 以 URL 字符串为主键
            loader.memoryCache?.remove(coil.memory.MemoryCache.Key(key = url))
            // 磁盘缓存：remove(String) 为 ExperimentalCoilApi
            @OptIn(coil.annotation.ExperimentalCoilApi::class)
            loader.diskCache?.remove(url)
        }
    } catch (_: Exception) {}
}

fun loadLocalChatLastMsg(ctx: android.content.Context, currentUserId: Long, key: String): String {
    return try {
        val prefs = ctx.getSharedPreferences("${key}_chat_${currentUserId}", android.content.Context.MODE_PRIVATE)
        com.aurora.chat.CryptoUtil.decryptLocal(prefs.getString("last_msg", "") ?: "")
    } catch (_: Exception) { "" }
}

/** 读取本地对话最后一条消息的时间戳（用于特殊对话按时间参与会话排序），无消息返回 0 */
// 【性能】AI/自己对话的 JSON 文件可能巨大，全量解析发生在主线程 = 启动卡顿。
// 保存消息时已把 last_time 写入 SharedPreferences（O(1)），这里优先读它；旧数据无该字段时
// 才回退到全量解析（一次性，结果同时写回 prefs + 进程内缓存，后续读取 O(1)）。
private val localLastTimeCache = HashMap<String, Long>()

fun loadLocalChatLastTime(ctx: android.content.Context, currentUserId: Long, key: String): Long {
    val cacheKey = "$currentUserId:$key"
    localLastTimeCache[cacheKey]?.let { return it }
    return try {
        val prefs = ctx.getSharedPreferences("${key}_chat_${currentUserId}", android.content.Context.MODE_PRIVATE)
        val cached = prefs.getLong("last_time", Long.MIN_VALUE)
        if (cached != Long.MIN_VALUE) {
            localLastTimeCache[cacheKey] = cached
            return cached
        }
        // 旧数据没有 last_time：回退到旧逻辑（全量解析取最大 created_at），一次性，随后写入缓存
        val arr = loadLocalChatMessages(ctx, currentUserId, key) ?: return 0
        var t = 0L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val c = o.optLong("created_at", 0)
            if (c > t) t = c
        }
        prefs.edit().putLong("last_time", t).apply()
        localLastTimeCache[cacheKey] = t
        t
    } catch (_: Exception) { 0 }
}

/** 通知中心的最后消息时间戳：优先读 prefs（O(1)），旧数据回退到解析整个通知文件 */
fun loadNotificationLastTime(ctx: android.content.Context, currentUserId: Long): Long {
    return try {
        val prefs = ctx.getSharedPreferences("notification_chat_$currentUserId", android.content.Context.MODE_PRIVATE)
        val cached = prefs.getLong("last_time", Long.MIN_VALUE)
        if (cached != Long.MIN_VALUE) return cached
        loadNotificationMessages(ctx, currentUserId).lastOrNull()?.createdAt ?: 0L
    } catch (_: Exception) { 0 }
}

// ==================== 系统通知对话（本地存储，不落服务端） ====================
// 与 self/ai 本地对话共用 saveLocalChatMessages 的区别：系统通知消息本身就是
// isSystemNotice，必须保留（saveLocalChatMessages 会过滤掉 isSystemNotice），故单独实现。

private fun notificationFile(ctx: android.content.Context, currentUserId: Long): java.io.File =
    java.io.File(ctx.cacheDir, "notification_chat_${currentUserId}.json")

fun loadNotificationMessages(ctx: android.content.Context, currentUserId: Long): List<ChatMsg> {
    val f = notificationFile(ctx, currentUserId)
    if (!f.exists()) return emptyList()
    return try {
        val arr = org.json.JSONArray(f.readText())
        val list = mutableListOf<ChatMsg>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("revoked", false)) continue
            list.add(ChatMsg.create(
                serverId = obj.optLong("id", 0L),
                text = obj.optString("text", ""),
                isMine = obj.optBoolean("is_mine", false),
                isSystemNotice = obj.optBoolean("is_system_notice", true),
                createdAt = obj.optLong("created_at", 0),
                isNew = false
            ))
        }
        list
    } catch (_: Exception) { emptyList() }
}

/** 追加一条系统通知到本地存储，并更新会话列表预览与未读角标 */
fun appendNotificationMessage(ctx: android.content.Context, currentUserId: Long, msg: ChatMsg) {
    val list = loadNotificationMessages(ctx, currentUserId).toMutableList()
    list.add(msg)
    try {
        val arr = org.json.JSONArray()
        for (m in list) {
            arr.put(org.json.JSONObject().apply {
                put("id", m.id)
                put("text", m.text)
                put("is_mine", m.isMine)
                put("is_system_notice", m.isSystemNotice)
                put("created_at", m.createdAt)
                put("revoked", false)
            })
        }
        notificationFile(ctx, currentUserId).writeText(arr.toString())
        val prefs = ctx.getSharedPreferences("notification_chat_${currentUserId}", android.content.Context.MODE_PRIVATE)
        val preview = when {
            isOrderNotice(msg.text) -> (parseOrderNotice(msg.text)?.title ?: msg.text.take(40))
            parseTransferNotice(msg.text) != null -> (parseTransferNotice(msg.text) ?: msg.text.take(40))
            else -> msg.text.take(40)
        }
        prefs.edit().putString("last_msg", preview).putLong("last_time", msg.createdAt).apply()
        // 通知中心未读累计走统一的未读 store（与底部"聊天"红点、列表红点同源）
        ChatViewModel.addUnread(ctx, NOTIFICATION_CHAT_ID, 1)
        LocalChatRefreshTick.bump()
    } catch (_: Exception) {}
}

fun getNotificationUnread(ctx: android.content.Context, currentUserId: Long): Int {
    return ChatViewModel.getUnread(ctx, NOTIFICATION_CHAT_ID)
}

fun setNotificationUnread(ctx: android.content.Context, currentUserId: Long, n: Int) {
    // 通知中心未读统一走 ChatViewModel 未读 store（n 恒为 0：进通知中心即已读）
    ChatViewModel.clearUnread(ctx, NOTIFICATION_CHAT_ID)
}

/** 用给定列表覆盖写入系统通知本地存储（用于与服务端历史合并后回写，保证离线仍可见） */
fun saveNotificationMessages(ctx: android.content.Context, currentUserId: Long, list: List<ChatMsg>) {
    try {
        val arr = org.json.JSONArray()
        for (m in list) {
            arr.put(org.json.JSONObject().apply {
                put("id", m.id)
                put("text", m.text)
                put("is_mine", m.isMine)
                put("is_system_notice", m.isSystemNotice)
                put("created_at", m.createdAt)
                put("revoked", false)
            })
        }
        notificationFile(ctx, currentUserId).writeText(arr.toString())
        val prefs = ctx.getSharedPreferences("notification_chat_${currentUserId}", android.content.Context.MODE_PRIVATE)
        val last = list.lastOrNull()
        val preview = if (last != null) {
            when {
                isOrderNotice(last.text) -> (parseOrderNotice(last.text)?.title ?: last.text.take(40))
                parseTransferNotice(last.text) != null -> (parseTransferNotice(last.text) ?: last.text.take(40))
                else -> last.text.take(40)
            }
        } else ""
        prefs.edit().putString("last_msg", preview)
            .putLong("last_time", last?.createdAt ?: 0L).apply()
    } catch (_: Exception) {}
}

// ==================== 位置卡片 ====================
fun isLocationCardMessage(text: String): Boolean {
    return text.trimStart().startsWith("分享位置")
}

data class LocationCardData(
    val address: String,
    val username: String,
    val lat: Double,
    val lng: Double
)

fun parseLocationCard(text: String): LocationCardData? {
    if (!isLocationCardMessage(text)) return null
    try {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val addrIdx = lines.indexOfFirst { it == "━━━" }
        val endIdx = lines.indexOfLast { it == "━━━" }
        if (addrIdx < 0 || endIdx <= addrIdx || endIdx + 1 >= lines.size) return null
        val addrLine = lines.getOrNull(addrIdx + 1) ?: ""
        val address = if (addrLine.startsWith("我在：") && addrLine.length <= 5) {
            lines.getOrNull(addrIdx + 2) ?: addrLine
        } else {
            addrLine.removePrefix("我在：").trim()
        }
        val fromLine = lines.getOrNull(endIdx + 1) ?: ""
        val username = fromLine.removePrefix("来自").trim()
        val latLine = lines.lastOrNull { it.startsWith("location_lat=") }?.removePrefix("location_lat=")?.trim()?.toDoubleOrNull() ?: 0.0
        val lngLine = lines.lastOrNull { it.startsWith("location_lng=") }?.removePrefix("location_lng=")?.trim()?.toDoubleOrNull() ?: 0.0
        return LocationCardData(address, username, latLine, lngLine)
    } catch (_: Exception) { return null }
}

// 解析拍拍消息文本
internal fun parsePokeText(m: MessageInfo, currentUserId: Long, currentUserName: String): String {
    val pokerName = if (m.fromUserId == currentUserId) "你" else m.fromUserName
    val targetName = if (m.toUserId == currentUserId) "你" else {
        m.targetName.takeIf { it.isNotEmpty() } ?: "对方"
    }
    return if (m.fromUserId == m.toUserId) {
        "${pokerName}拍了拍自己"
    } else if (m.fromUserId == currentUserId) {
        "你拍了拍${targetName}"
    } else if (m.toUserId == currentUserId) {
        "${pokerName}拍了拍你"
    } else {
        "${pokerName}拍了拍${targetName}"
    }
}

// ==================== 转账 ====================
// 视角完全由结构化字段现算，不在服务端写死文案，群聊/私聊天然一致
const val TRANSFER_MSG_PREFIX = "系统转账数据"
const val TRANSFER_NOTICE_PREFIX = "系统转账通知"

data class TransferData(
    val fromUserId: Long,
    val fromName: String,
    val toUserId: Long,
    val toName: String,
    val amount: Long
)

fun isTransferMessage(text: String): Boolean = text.trimStart().startsWith(TRANSFER_MSG_PREFIX)

fun parseTransferMessage(text: String): TransferData? {
    return try {
        val cleaned = text.trim()
        if (!cleaned.startsWith(TRANSFER_MSG_PREFIX)) return null
        val lines = cleaned.split("\n").map { it.trim() }
        fun get(key: String): String {
            val l = lines.firstOrNull { it.startsWith("$key=") } ?: return ""
            return l.removePrefix("$key=").trim()
        }
        val fromUserId = get("from_user_id").toLongOrNull() ?: return null
        val toUserId = get("to_user_id").toLongOrNull() ?: return null
        val amount = get("amount").toLongOrNull() ?: return null
        TransferData(
            fromUserId = fromUserId,
            fromName = get("from_name").ifEmpty { "某人" },
            toUserId = toUserId,
            toName = get("to_name").ifEmpty { "某人" },
            amount = amount
        )
    } catch (_: Exception) { null }
}

/** 视角文案：由结构化字段现算，群聊/私聊统一正确 */
fun transferDisplayLabel(data: TransferData, currentUserId: Long): String {
    return when {
        currentUserId == data.fromUserId -> "你向${data.toName}转账 ${data.amount} token"
        currentUserId == data.toUserId -> "${data.fromName}向你转账 ${data.amount} token"
        else -> "${data.fromName}向${data.toName}转账 ${data.amount} token"
    }
}

/** 构造与后端一致的转账消息正文（用于本地乐观插入，随后会被服务端同步覆盖） */
fun buildTransferContent(fromUserId: Long, fromName: String, toUserId: Long, toName: String, amount: Long): String = buildString {
    appendLine(TRANSFER_MSG_PREFIX)
    appendLine("from_user_id=$fromUserId")
    appendLine("from_name=$fromName")
    appendLine("to_user_id=$toUserId")
    appendLine("to_name=$toName")
    appendLine("amount=$amount")
}

fun formatTransferNotice(desc: String): String = buildString {
    appendLine(TRANSFER_NOTICE_PREFIX)
    appendLine("desc=$desc")
}

fun parseTransferNotice(text: String): String? {
    return try {
        val cleaned = text.trim()
        if (!cleaned.startsWith(TRANSFER_NOTICE_PREFIX)) return null
        cleaned.split("\n").map { it.trim() }.firstOrNull { it.startsWith("desc=") }?.removePrefix("desc=")?.trim()
    } catch (_: Exception) { null }
}

// ==================== 红包 ====================
const val REDPACKET_MSG_PREFIX = "系统红包数据"

data class RedPacketData(
    val packetId: Long,
    val fromUserId: Long,
    val fromName: String,
    val total: Long,
    val count: Int,
    val greeting: String,
    val createdAt: Long,
    val expireAt: Long
)

/** 红包卡片实时状态（来自服务端 detail 接口），用于内联卡片显示进度/过期 */
data class RedPacketStatus(
    val packetId: Long,
    val claimedCount: Int,
    val totalCount: Int,
    val remaining: Long,
    val total: Long,
    val status: String,   // active / full / expired
    val myClaimed: Long
)

fun isRedPacketMessage(text: String): Boolean = text.trimStart().startsWith(REDPACKET_MSG_PREFIX)

fun parseRedPacketMessage(text: String): RedPacketData? {
    return try {
        val cleaned = text.trim()
        if (!cleaned.startsWith(REDPACKET_MSG_PREFIX)) return null
        val lines = cleaned.split("\n").map { it.trim() }
        fun get(key: String): String {
            val l = lines.firstOrNull { it.startsWith("$key=") } ?: return ""
            return l.removePrefix("$key=").trim()
        }
        fun getLong(key: String): Long = get(key).toLongOrNull() ?: 0L
        fun getInt(key: String): Int = get(key).toIntOrNull() ?: 0
        RedPacketData(
            packetId = getLong("packet_id"),
            fromUserId = getLong("from_user_id"),
            fromName = get("from_name"),
            total = getLong("total"),
            count = getInt("count"),
            greeting = get("greeting"),
            createdAt = getLong("created_at"),
            expireAt = getLong("expire_at")
        )
    } catch (_: Exception) { null }
}

/** 构造与后端一致的红包消息正文（用于本地乐观插入，随后会被服务端同步覆盖） */
fun buildRedPacketContent(
    packetId: Long, fromUserId: Long, fromName: String, total: Long, count: Int,
    greeting: String, createdAt: Long, expireAt: Long
): String = buildString {
    appendLine(REDPACKET_MSG_PREFIX)
    appendLine("packet_id=$packetId")
    appendLine("from_user_id=$fromUserId")
    appendLine("from_name=$fromName")
    appendLine("total=$total")
    appendLine("count=$count")
    appendLine("greeting=$greeting")
    appendLine("created_at=$createdAt")
    appendLine("expire_at=$expireAt")
}

/** 根据后端 detail 响应构建内联卡片状态 */
fun redPacketStatusFromJson(json: org.json.JSONObject): RedPacketStatus? {
    return try {
        val p = json.optJSONObject("packet") ?: return null
        RedPacketStatus(
            packetId = p.optLong("id"),
            claimedCount = p.optInt("claimed"),
            totalCount = p.optInt("count"),
            remaining = p.optLong("remaining"),
            total = p.optLong("total"),
            status = json.optString("status", "active"),
            myClaimed = json.optLong("my_claimed")
        )
    } catch (_: Exception) { null }
}


// 获取当前位置并发送位置卡片消息
internal suspend fun sendLocationMessage(
    ctx: android.content.Context,
    currentUserId: Long,
    friendId: Long,
    onMessagesUpdate: (List<IMessageRef>) -> Unit
) {
    try {
        val locationManager = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        // 将权限检查结果存入局部布尔量，并在每次 getLastKnownLocation 调用前用紧邻的 if 守卫，
        // 满足 Lint MissingPermission 检测（when 表达式无法被该检测器可靠识别）。同时包 SecurityException 兜底。
        val hasFine = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val location = try {
            var loc: android.location.Location? = null
            if (hasFine) loc = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            if (loc == null) {
                if (hasFine) {
                    loc = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                } else if (hasCoarse) {
                    loc = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                }
            }
            loc
        } catch (_: SecurityException) { null }
        if (location == null) {
            android.widget.Toast.makeText(ctx, "未授予位置权限或无法获取当前位置，请打开GPS", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val geocoder = android.location.Geocoder(ctx, java.util.Locale.getDefault())
        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
        val address = if (!addresses.isNullOrEmpty()) {
            addresses[0].getAddressLine(0) ?: "${location.latitude},${location.longitude}"
        } else {
            "${location.latitude},${location.longitude}"
        }
        val loginPrefs = ctx.getSharedPreferences("aurora_login", android.content.Context.MODE_PRIVATE)
        val username = loginPrefs.getString("username", "") ?: "我"
        val locationText = "分享位置\n━━━\n我在：${address}\n━━━\n来自 ${username}\nlocation_lat=${location.latitude}\nlocation_lng=${location.longitude}"
        val newMsg = ChatMsg.create(text = locationText, isMine = true, fromUserId = currentUserId, isNew = true)
        onMessagesUpdate(emptyList<ChatMsg>() + newMsg)
        if (isLocalChat(friendId)) {
            ChatViewModel.updateConversationPreview(friendId, locationText, System.currentTimeMillis() / 1000)
        } else {
            val sendResult = if (isGroupChat(friendId)) com.aurora.chat.data.repository.ChatRepository.sendGroupMessage(friendId, locationText)
            else com.aurora.chat.data.repository.ChatRepository.sendMessage(friendId, locationText)
            if (!sendResult.success) {
                com.aurora.chat.data.api.AuroraApi.showErrorToast(ctx, "位置发送失败")
            }
            // 用户主动发送位置卡片 → 读取并上报服务器位置
            try {
                com.aurora.chat.data.api.AuroraApi.uploadLocation(location.latitude, location.longitude, address)
                ctx.getSharedPreferences("aurora_location", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("uploaded_once", true).apply()
            } catch (_: Exception) { }
        }
    } catch (e: Exception) {
        com.aurora.chat.data.api.AuroraApi.showErrorToast(ctx, "获取位置失败: ${e.message}")
    }
}

// 发送相机拍摄的文件（直接上传，不经过文件选择中转）
internal suspend fun sendCameraFile(
    uri: android.net.Uri,
    ctx: android.content.Context,
    currentUserId: Long,
    friendId: Long,
    scope: CoroutineScope,
    messages: List<IMessageRef>,
    uploadProgressMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Float>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onMessagesUpdate: (List<IMessageRef>) -> Unit
) {
    try {
        val mimeType = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        val fileName = uri.lastPathSegment ?: "camera_capture.jpg"
        val isImage = mimeType.startsWith("image/")
        val isVideo = mimeType.startsWith("video/")

    val extraType = if (isImage) "image" else if (isVideo) "video" else "file"

    // 本地对话（自己 / AI）秒发：直接写原始文件 + 全量写 messages 到 JSON
    if (isLocalChat(friendId)) {
        val key = localChatKey(friendId)
        val localChatDir = java.io.File(ctx.filesDir, "${key}_chat")
        if (!localChatDir.exists()) localChatDir.mkdirs()
        val ext = when { isVideo -> "mp4"; isImage -> "jpg"; else -> "bin" }
        val localFile = java.io.File(localChatDir, "${System.currentTimeMillis()}.${ext}")
        ctx.contentResolver.openInputStream(uri)?.use { rawIn ->
            java.io.FileOutputStream(localFile).use { out -> rawIn.copyTo(out) }
        }
        val localMediaUrl = "file://${localFile.absolutePath}"
        val selfMsgId = System.currentTimeMillis() * 10000 + (0..9999).random()
        val now = System.currentTimeMillis() / 1000
        val updated = messages + ChatMsg.create(
            serverId = selfMsgId, text = "", isMine = true, fromUserId = currentUserId,
            isNew = true, mediaType = extraType, mediaUrl = localMediaUrl, createdAt = now
        )
        MediaDebug.log(ctx, "SEND_LOCAL", "mediaType=$extraType mediaUrl=$localMediaUrl")
        onMessagesUpdate(updated)
        saveLocalChatMessages(ctx, currentUserId, key, updated.filter { !it.isSystemNotice })
        ChatViewModel.updateConversationPreview(friendId, if (isVideo) "[视频]" else "[图片]", System.currentTimeMillis() / 1000)
        listState.animateScrollToItem(updated.size - 1)
        return
    }

    val placeholderId = System.currentTimeMillis() * 10000 + (0..9999).random()
    val placeholderMsg = ChatMsg.create(serverId = placeholderId, text = "", isMine = true, fromUserId = currentUserId, isNew = true, mediaType = extraType, mediaUrl = "", isUploading = true, flashDuration = 0)
        onMessagesUpdate(messages + placeholderMsg)
        uploadProgressMap[placeholderMsg.id] = 0f
        scope.launch {
            kotlinx.coroutines.delay(200)
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        val inputStream = ctx.contentResolver.openInputStream(uri)
        if (inputStream == null) {
            com.aurora.chat.data.api.AuroraApi.showErrorToast(ctx, "无法读取文件")
            uploadProgressMap.remove(placeholderMsg.id)
            onMessagesUpdate(messages.toMutableList().apply { removeAll { it.id == placeholderMsg.id } })
            return
        }
        val fileBytes = inputStream.readBytes()
        inputStream.close()

        val finalBytes = if (isImage) {
            // 先解码图片尺寸，按目标最大 1920px 计算采样率，避免全尺寸解码导致 OOM
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size, opts)
            val maxDimension = 1920
            val sampleSize = maxOf(
                (opts.outWidth + maxDimension - 1) / maxDimension,
                (opts.outHeight + maxDimension - 1) / maxDimension,
                1
            )
            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size, decodeOpts)
            if (bitmap != null && (fileBytes.size > 500 * 1024 || sampleSize > 1)) {
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            } else fileBytes
        } else fileBytes

        if (isLocalChat(friendId)) {
            val key = localChatKey(friendId)
            val localChatDir = java.io.File(ctx.filesDir, "${key}_chat")
            if (!localChatDir.exists()) localChatDir.mkdirs()
            val ext = when {
                isVideo -> "mp4"
                isImage -> "jpg"
                else -> "bin"
            }
            val localFile = java.io.File(localChatDir, "${System.currentTimeMillis()}.${ext}")
            java.io.FileOutputStream(localFile).use { out -> out.write(finalBytes) }
            val localMediaUrl = "file://${localFile.absolutePath}"

            val finalMsg = ChatMsg.create(
                text = "", isMine = true, fromUserId = currentUserId, isNew = true,
                mediaType = if (isVideo) "video" else "image", mediaUrl = localMediaUrl
            )
            MediaDebug.log(ctx, "SEND_LOCAL", "mediaType=${if (isVideo) "video" else "image"} mediaUrl=$localMediaUrl")
            val newMessages = messages.toMutableList().apply { removeAll { it.id == placeholderMsg.id } } + finalMsg
            onMessagesUpdate(newMessages)
            saveLocalChatMessages(ctx, currentUserId, key, newMessages.filter { !it.isSystemNotice })
            ChatViewModel.updateConversationPreview(friendId, if (isVideo) "[视频]" else "[图片]", System.currentTimeMillis() / 1000)
        } else {
            val uploadResult = com.aurora.chat.data.api.AuroraApi.uploadChatMedia(finalBytes, fileName, mimeType)
            if (!uploadResult.success || uploadResult.data == null) {
                com.aurora.chat.data.api.AuroraApi.showErrorToast(ctx, uploadResult.message)
                uploadProgressMap.remove(placeholderMsg.id)
                onMessagesUpdate(messages.toMutableList().apply { removeAll { it.id == placeholderMsg.id } })
                return
            }
            val rawMediaUrl = uploadResult.data!!.optString("url", "")
            val rawMediaType = uploadResult.data!!.optString("media_type", "image")
            if (rawMediaUrl.isEmpty()) {
                com.aurora.chat.data.api.AuroraApi.showErrorToast(ctx, "上传失败")
                uploadProgressMap.remove(placeholderMsg.id)
                onMessagesUpdate(messages.toMutableList().apply { removeAll { it.id == placeholderMsg.id } })
                return
            }

            val fullMediaUrl = resolveMediaUrl(rawMediaUrl)
            MediaDebug.log(ctx, "SEND_SERVER", "mediaType=${if (isVideo) rawMediaType else "image"} mediaUrl=$fullMediaUrl")


            val finalMsg = ChatMsg.create(
                serverId = 0L, text = "", isMine = true, fromUserId = currentUserId,
                isNew = false, mediaType = if (isVideo) rawMediaType else "image", mediaUrl = fullMediaUrl,
                createdAt = System.currentTimeMillis() / 1000
            )
            val baseList = messages.toMutableList().apply { removeAll { it.id == placeholderMsg.id } }
            uploadProgressMap.remove(placeholderMsg.id)
            onMessagesUpdate(baseList)
            ChatViewModel.sendMessage(
                currentUserId = currentUserId,
                friendId = friendId,
                text = "",
                replyTo = 0,
                mediaType = if (isVideo) rawMediaType else "image",
                mediaUrl = fullMediaUrl,
                flashDuration = 0,
                onSuccess = { msgId ->
                    onMessagesUpdate(baseList + finalMsg.copy(id = msgId))
                    // 落盘已验证可用的完整媒体 URL，防止重进对话后变成空气泡
                    scope.launch {
                        try {
                            val info = com.aurora.chat.data.api.MessageInfo(
                                id = msgId, fromUserId = currentUserId, toUserId = friendId,
                                content = "", createdAt = System.currentTimeMillis() / 1000,
                                mediaType = finalMsg.mediaType, mediaUrl = finalMsg.mediaUrl, isRevoked = 0, flashDuration = 0
                            )
                            com.aurora.chat.data.repository.LocalMessageStore.mergeAndSave(ctx, friendId, listOf(info))
                        } catch (_: Exception) { }
                    }
                },
                onError = { msg -> com.aurora.chat.data.api.AuroraApi.showErrorToast(ctx, "发送失败: $msg") },
                context = ctx
            )
            ChatViewModel.loadConversations(force = true)
        }

        scope.launch {
            kotlinx.coroutines.delay(200)
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
    } catch (e: Exception) {
        com.aurora.chat.data.api.AuroraApi.showErrorToast(ctx, "发送失败: ${e.message}")
    }
}

// 保存自己对话（friendId=0）的消息到本地（兼容旧调用，内部走通用本地对话存储）
internal fun saveSelfChatMessages(ctx: android.content.Context, currentUserId: Long, msgs: List<IMessageRef>) {
    saveLocalChatMessages(ctx, currentUserId, "self", msgs)
}

internal fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}

internal fun isImageFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
}
