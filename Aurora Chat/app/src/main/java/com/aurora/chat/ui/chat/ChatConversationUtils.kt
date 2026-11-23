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
            out.add(
                com.aurora.chat.ui.chat.ChatMsg.ToolStageRecord(
                    tool = tool,
                    state = o.optInt("state", 2),
                    ts = o.optLong("ts", 0),
                    reason = o.optString("reason", ""),
                    // 旧数据没有 target / args 两个键 → 取空串，明细页按「无记录」降级，绝不显示占位符
                    target = o.optString("target", ""),
                    argsSummary = o.optString("args", ""),
                    // 代码类工具的正文（旧数据无此键 → 空串，卡片退化为原来的「本次参数」形态）
                    code = o.optString("code", ""),
                    // 执行结果 / 变更预览（旧数据无此键 → 空串，卡片退回旧形态）
                    output = o.optString("output", ""),
                    diff = o.optString("diff", "")
                )
            )
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
    // 表情协议必须原样返回：sticker:// 不是 http/file/content，历史上掉进下面的兜底
    // 被拼成 "https://host/sticker://xxx"，URL 一坏表情就被兜底判成文件卡片。
    // indexOf 而非 startsWith：顺带把旧版本已污染的缓存 URL（host+sticker://）截回协议头。
    val si = raw.indexOf("sticker://")
    if (si >= 0) return raw.substring(si)
    // emoji 消息：media_url 直接存 emoji 字符本身，不是 http/file/content 协议，必须原样返回，
    // 否则会被下方兜底拼成 "https://host/😀"，mediaUrl 被污染后误判成文件卡片
    if (isEmojiMediaUrl(raw)) return raw
    // 历史污染矫正：emoji 字符曾被旧逻辑拼成 "https://host/😀"（无编码、直接拼），
    // 路径尾段若是纯 emoji 字符则截回字符本身（与 sticker 的 indexOf 截回同思路，防旧缓存残留）
    val slashIdx = raw.lastIndexOf('/')
    if (slashIdx >= 0) {
        val tail = raw.substring(slashIdx + 1)
        if (isEmojiMediaUrl(tail)) return tail
    }
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
 *
 * 远程下载走流式（边读边写，不整包进内存），并显式禁用 HTTP 缓存：
 * 此前 OkHttp 的 Cache 拦截器偶发返回空/不完整的缓存条目，导致好友下载得到 0B 文件却静默"成功"。
 */
fun performSaveAsIo(ctx: android.content.Context, uri: android.net.Uri, url: String): String? {
    return try {
        val out = ctx.contentResolver.openOutputStream(uri, "w") ?: return "无法打开目标位置"
        try {
            var bytesWritten = 0L
            if (url.startsWith("file://")) {
                val src = java.io.File(url.removePrefix("file://"))
                if (!src.exists()) return "源文件不存在"
                src.inputStream().use { inp -> bytesWritten = inp.copyTo(out) }
            } else if (url.startsWith("content://")) {
                // 本机 content:// 源（本地上传的文件、相册、文档提供者）同样不该走网络：
                // 交给 OkHttp 只会失败，必须用系统解析器直接读。
                val inp = ctx.contentResolver.openInputStream(android.net.Uri.parse(url))
                    ?: return "无法读取源文件"
                inp.use { i -> bytesWritten = i.copyTo(out) }
            } else {
                // 显式禁用 OkHttp 缓存 + 关闭连接，确保每次都是新鲜数据（缓存空条目曾导致 0B）
                val req = okhttp3.Request.Builder().url(url)
                    .header("Cache-Control", "no-cache, no-store")
                    .header("Connection", "close")
                    .build()
                com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return "下载失败 (HTTP ${resp.code})"
                    val body = resp.body ?: return "下载内容为空"
                    val expectedLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    body.byteStream().use { inp ->
                        bytesWritten = inp.copyTo(out)
                    }
                    // 写入验证：如果服务器声明了 Content-Length，比对实际写入字节数
                    if (expectedLen > 0 && bytesWritten != expectedLen) {
                        android.util.Log.e("performSaveAsIo",
                            "下载字节数不匹配: 期望=$expectedLen, 实际=$bytesWritten url=$url")
                        return "下载不完整（期望 ${expectedLen} 字节，实际 ${bytesWritten}）"
                    }
                }
            }
            // 最终兜底验证：什么都没写入 → 直接报错，不生成 0B 文件
            if (bytesWritten == 0L) {
                android.util.Log.e("performSaveAsIo", "下载得到 0 字节，拒绝写入 url=$url")
                return "下载内容为空（服务器返回了空文件）"
            }
            null
        } finally {
            try { out.close() } catch (_: Exception) {}
        }
    } catch (e: Exception) {
        android.util.Log.e("performSaveAsIo", "保存异常", e)
        (e.message ?: "保存失败")
    }
}
// AI 回复占位（流式返回前显示），不计入发给模型的上下文
const val AI_THINKING = "思考中…"

// ===================== 引用（quote）辅助 =====================

/**
 * 引用预览文案的**唯一事实来源**：输入框上方横幅与发送落库必须调同一个函数。
 *
 * 曾经的坑：横幅里自己写了一份 `when`（含撤回判断），发送路径又另写一份（漏了撤回），
 * 于是出现"横幅显示『对方撤回了一条消息』、发出去的引用却带着已撤回消息的原文"这种口径不一致。
 * 抽成公共函数后两边不可能再漂。
 */
fun quotePreviewText(ref: IMessageRef): String = when {
    ref.isRevoked == 1 -> if (ref.isMine) "我撤回了一条消息" else "对方撤回了一条消息"
    ref.mediaType == "voice" -> "[语音]"
    ref.mediaType == "sticker" -> "[表情]"
    ref.mediaType == "image" -> "[图片]"
    ref.mediaType == "video" || ref.mediaType == "mp4" -> "[视频]"
    ref.mediaType == "file" -> "[文件]"
    ref.mediaType == "transfer" -> "[转账]"
    ref.flashDuration > 0 -> "[闪照]"
    ref.mediaType.isNotEmpty() -> "[媒体]"
    ref.text.isNotBlank() -> ref.text.take(40) + if (ref.text.length > 40) "…" else ""
    ref.mediaUrl.isNotEmpty() -> "[媒体]"
    else -> "[空消息]"
}

/** 引用状态的持久化 key：与草稿同一个 prefs、按会话隔离（key = 草稿 key + "_quote"）。 */
fun quoteDraftKey(draftKey: String): String = "${draftKey}_quote"

/**
 * 把当前引用写入草稿 prefs；ref 为 null 时移除。
 *
 * 为什么需要：引用态原来是纯 `remember`——进程被回收 / Activity 重建后草稿文字回来了，
 * 引用横幅却消失，两者不同步。跟草稿放一起存，恢复时一起回来。
 */
fun saveQuoteDraft(ctx: android.content.Context, draftKey: String, ref: IMessageRef?) {
    val prefs = ctx.getSharedPreferences("aurora_drafts", android.content.Context.MODE_PRIVATE)
    val key = quoteDraftKey(draftKey)
    if (ref == null) {
        prefs.edit().remove(key).apply()
        return
    }
    // 只存渲染 + 发送真正用到的字段；恢复时合成一条轻量 ChatMsg 当引用源
    val json = org.json.JSONObject().apply {
        put("id", ref.id)
        put("text", ref.text)
        put("isMine", ref.isMine)
        put("senderName", ref.senderName)
        put("mediaType", ref.mediaType)
        put("mediaUrl", ref.mediaUrl)
        put("isRevoked", ref.isRevoked)
        put("flashDuration", ref.flashDuration)
    }
    prefs.edit().putString(key, json.toString()).apply()
}

/** 从草稿 prefs 读回引用；没有 / 解析失败 / id 非法时返回 null。 */
fun loadQuoteDraft(ctx: android.content.Context, draftKey: String): IMessageRef? {
    val prefs = ctx.getSharedPreferences("aurora_drafts", android.content.Context.MODE_PRIVATE)
    val raw = prefs.getString(quoteDraftKey(draftKey), null) ?: return null
    return runCatching {
        val o = org.json.JSONObject(raw)
        val id = o.optLong("id", 0L)
        if (id <= 0L) return null
        ChatMsg.create(
            serverId = id,
            text = o.optString("text", ""),
            isMine = o.optBoolean("isMine", false),
            fromUserId = 0,
            senderName = o.optString("senderName", ""),
            isRevoked = o.optInt("isRevoked", 0),
            mediaType = o.optString("mediaType", ""),
            mediaUrl = o.optString("mediaUrl", ""),
            flashDuration = o.optInt("flashDuration", 0)
        )
    }.getOrNull()
}

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
                // 主动发一次「下载完成」信号：SAF 保存不经过下载管理器，
                // 不通知的话文件卡片上的「已保存」标签不会刷新。
                runCatching { com.aurora.chat.ui.tools.DownloadManager.downloadChanged.value++ }
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

/**
 * 纯本地存储的内置对话：现在只剩 AI 多会话。
 * 本地「个人对话」(friendId=0) 已移除，改走服务端自聊会话（friendId = 自己 userId），
 * 与真实私聊共用发送/接收/撤回/媒体/搜索等全部链路。
 */
fun isLocalChat(friendId: Long): Boolean = friendId == AI_CHAT_ID
fun isGroupChat(friendId: Long): Boolean = friendId < 0 && friendId != AI_CHAT_ID && friendId != NOTIFICATION_CHAT_ID
/**
 * 需要「好友关系横幅 / 转账」的私聊：friendId > 0。
 * AI(-2)/通知(-3)/群聊(<0) 均不算。
 *
 * currentUserId 传入后，「对方就是自己」也不算：自聊会话虽然走服务端普通私聊链路，
 * 但自己和自己没有好友关系，加好友/删好友/转账必然失败，故不显示非好友横幅、不禁用付款。
 */
fun isPrivateChat(friendId: Long, currentUserId: Long = 0L): Boolean =
    friendId > 0L && (currentUserId <= 0L || friendId != currentUserId)
fun isNotificationChat(friendId: Long): Boolean = friendId == NOTIFICATION_CHAT_ID
/** 本地存储 key：仅 AI 多会话使用；本地个人对话已移除，自聊消息由服务端/本地消息库按 conversationId 存储。 */
fun localChatKey(friendId: Long): String = AiConversationSession.storageKey()

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
    // 交付总结(DELIVER 块)同步剥离:卡片在会话详情内渲染,预览不展示其哨兵/纯文本。
    val prose = prepareDeliverableDisplay(raw).second
        .let { AiChatManager.splitInlineBlocksV2(it) }
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
            val prose = prepareDeliverableDisplay(o.optString("text", "")).second
                .let { AiChatManager.splitInlineBlocksV2(it) }
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
/**
 * 工具文案的**唯一事实源**：
 *  · [toolStatusText]  = 状态行文案（进行中/成功/失败）
 *  · [toolFriendlyName] = 工具中文短名（清单页等展示用）
 *
 * 为什么要收口：这段文案原本**散在 4 个渲染点**各写一份 `"调用成功 $tool"`，
 * 改一处必然漏三处 —— 用户提过两次「说好改成『已读取文件』却一直没生效」，正是这个原因。
 * 现在所有渲染点都调这里，改一次就全变；未收录的工具一律**原样回退**，不会变空、不会报错。
 */
/**
 * 工具状态行文案（0=进行中 / 1=成功 / 2=失败）。
 *
 * 中文名一律走 [toolDisplayName]（TOOL_DISPLAY_NAMES 总表 + 中文兜底），
 * 不再回退成「调用成功 open_rear_camera」这种英文句式。
 *
 * [target] 传本次调用的**真实目标**（由 [toolActionTarget] 从工具参数提取）时，
 * 文案变成可追溯的「正在搜索文件：MainActivity.kt…／已搜索文件：MainActivity.kt」——
 * 用户要求：能知道它到底在动哪个/搜哪个的，就必须显示出来，而不是只给一句泛化的「正在调用」。
 */
internal fun toolStatusText(tool: String, state: Int, target: String = ""): String {
    val n = toolDisplayName(tool)
    val t = target.trim()
    val ellipsis = if (t.endsWith("…")) "" else "…"
    return when {
        state == 1 -> if (t.isEmpty()) "已$n" else "已$n：$t"
        state == 2 -> if (t.isEmpty()) "${n}失败" else "${n}失败：$t"
        else -> if (t.isEmpty()) "正在$n…" else "正在$n：$t$ellipsis"
    }
}

/** 工具中文短名（清单页展示用）。 */
internal fun toolFriendlyName(tool: String): String = toolDisplayName(tool)


/**
 * 把一条 AI 消息的**原文**清洗成"给用户看/编辑的正文"（复制、修改 共用）。
 *
 * 为什么必须清洗：消息原文里除了正文，还夹着全部**内部协议**——
 *   · 思考哨兵、工具状态内联哨兵（`\u0001W\u0001write_file\u00022\u0001/W\u0001`）
 *   · `@tool:start…@tool:end` 文本协议
 *   · 方案卡片整块（`[[PLAN_CARD]]{json}[[/PLAN_CARD]]`）
 *   · 交付总结块（`\u0001DELIVER\u0001…`）
 * 直接把原文丢进编辑框/剪贴板，用户就会看到"正文 + 一堆内部块"（用户反馈：*把两个生成的东西全塞进去*）。
 *
 * ⚠️ 顺序：先逐层剥协议块（此时尚可被各协议识别），最后才做纯文本美化（压空行/去孤立标点）。
 */
internal fun messageEditableBody(text: String): String {
    if (text.isEmpty()) return text
    var s = text
    s = AiChatManager.stripInlineReasoning(s)      // 思考哨兵
    s = AiChatManager.stripToolSentinels(s)        // 工具状态内联哨兵
    s = AiChatManager.stripToolBlocks(s)           // @tool:start…@tool:end 文本协议
    s = AITaskCompletion.stripDelivery(s)       // 交付总结块
    s = stripPlanSentinelFragments(s)              // 方案卡片整块 + 残缺碎片
    return tidyCardBodyText(s)
}

/**
 * 追加新一轮正文时**去掉与已有内容的重复**。
 *
 * 为什么需要：多轮工具循环里每轮文本都是直接追加（`accumulated + 本轮文本`），
 * 而模型在「断点续写 / 被催交付 / 复述进度」时经常把**前面写过的内容再写一遍** ——
 * 于是同一条消息里同一段话会出现两三遍（用户反馈：整场对话出现近十几次）。
 * 这里做纯字符串去重（零依赖、无 NLP）：
 *   ① 后缀-前缀重叠：本轮文本开头与已有文本结尾有较长重叠 → 只保留不重叠的部分；
 *   ② 长片段复述：本轮文本开头 ≥120 字**在原文本末尾附近**出现过 → 从该处之后保留；
 *   ③ 裁剪后剩余过短（<12 字）→ 视为纯重复，整段丢弃（避免留下"半句话"）。
 * 这是**保守**规则：命中不了就原样追加，绝不误删正常新内容。
 */
internal fun appendRoundTextDedup(prev: String, next: String): String {
    val n = next.trim()
    if (n.isEmpty()) return prev
    if (prev.isBlank()) return n
    // ① 最长后缀-前缀重叠（扫描上限 600 字，够覆盖"从上次断点重新开始写"的情况）
    val maxK = minOf(600, prev.length, n.length)
    var k = maxK
    while (k > 0 && !prev.endsWith(n.substring(0, k))) k--
    if (k > 0) {
        val rest = n.substring(k).trim()
        return if (rest.length < 12) prev else "$prev\n\n$rest"
    }
    // ② 长片段复述：只看原文本的**末尾 4000 字**，避免正文中间恰好出现相同短语就误裁剪
    val windowStart = (prev.length - 4000).coerceAtLeast(0)
    val window = prev.substring(windowStart)
    val probeLen = minOf(120, n.length)
    if (probeLen >= 120) {
        val at = window.lastIndexOf(n.substring(0, probeLen))
        if (at >= 0) {
            val rest = n.substring(probeLen).trim()
            return if (rest.length < 12) prev else "$prev\n\n$rest"
        }
    }
    return "$prev\n\n$n"
}

fun describeToolCall(tool: String, argsJson: String, snippetMax: Int = 30): String {
    val args = try { if (argsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(argsJson) } catch (_: Exception) { org.json.JSONObject() }
    fun p(key: String): String = args.optString(key, "").trim()
    // cap = maxOf(各处固定上限, snippetMax)：默认 30 与原行为完全一致（审批弹窗/通知需要短句）；
    // 「工具调用清单」记录时传大值，于是所有片段上限统一放宽 —— 展开后能看到原本被「…」省略掉的内容。
    fun prev(s: String, max: Int = 30): String {
        val cap = maxOf(max, snippetMax)
        return s.replace(Regex("\\s+"), " ").let { if (it.length > cap) it.take(cap) + "…" else it }
    }
    val desc = when (tool) {
        "agent_review" -> {
            val task = p("task")
            "提交工作成果给独立验收 AI 核验" + (if (task.isBlank()) "" else "：${prev(task, 40)}")
        }
        "agent_fork" -> {
            val tasks = try { args.optJSONArray("tasks") } catch (_: Exception) { null }
            val n = tasks?.length() ?: 0
            "并行派发 $n 个子执行 AI 同时干活" + if (n > 0) "：${prev(tasks?.optString(0) ?: "", 40)} 等" else ""
        }
        "agent_plan" -> {
            val task = p("task")
            "把任务交给独立规划 AI 拆解执行计划" + (if (task.isBlank()) "" else "：${prev(task, 40)}")
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
        "search_in_files" -> {
            val q = p("query").ifEmpty { "（未指定搜索内容）" }
            val path = p("path").ifEmpty { "工作区根目录" }
            val glob = p("glob")
            "在 $path 中搜索文件内容「" + prev(q, 40) + "」" + (if (glob.isBlank()) "" else "（仅 $glob）")
        }
        "query_environment" -> "查询设备运行环境信息（型号、系统版本、root 状态等）"
        AiChatManager.TOOL_DEEP_RESEARCH -> {
            val q = p("query").ifEmpty { "（未指定课题）" }
            "深度研究：$q（多角度检索 + 抓取多页正文）"
        }
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
        AiChatManager.TOOL_SEND_FILE -> {
            val targetType = p("target_type").ifEmpty { "?" }
            val path = p("path").ifEmpty { "（未指定文件）" }
            val caption = p("caption")
            "以你的身份把文件「${path}」发送给${if (targetType == "group") "群" else "好友"}(ID=${p("target_id").ifEmpty { "?" }})" +
                (if (caption.isBlank()) "" else "，附言：${prev(caption, 30)}")
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
    // 总长闸门：默认 80（审批弹窗/通知要短句）。工具调用清单记录时 snippetMax 已放大，
    // 这里必须同步放宽，否则上面各处 prev 放宽了、最后仍被这 80 字砍掉，展开全文也看不到省略的内容。
    val totalCap = maxOf(80, snippetMax)
    return if (desc.length > totalCap) desc.take(totalCap) + "…" else desc
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
        // SVG 矢量图：按图片发送（上传前会渲染成 PNG，服务端不接收可脚本渲染的 .svg）
        "svg", "svgz" -> "image/svg+xml"
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
            // 交付总结(DELIVER 块)一并剥离:卡片在会话详情内渲染,列表预览不重复展示其哨兵/纯文本。
            if ((m as? com.aurora.chat.ui.chat.ChatMsg)?.isToolStatus != true) {
                val plain = prepareDeliverableDisplay(m.text).second
                    .let { AiChatManager.splitInlineBlocksV2(it) }
                    .filterIsInstance<AiChatManager.InlineBlock.Text>()
                    .joinToString(" ") { it.content }
                    .trim()
                if (plain.isNotEmpty()) { lastMsg = plain; lastTime = m.createdAt }
            }
            if (m.mediaType.isNotEmpty()) {
                // 闪照优先：阅后即焚图片在会话列表显示 [闪照]
                lastMsg = if (m.flashDuration > 0) "[闪照]"
                else when (m.mediaType) {
                    "image" -> "[图片]"; "video" -> "[视频]"; "voice" -> "[语音]"; else -> "[文件]"
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
            obj.put("input_tokens", m.inputTokens)
            obj.put("output_tokens", m.outputTokens)
            obj.put("cache_hit_tokens", m.cacheHitTokens)
            obj.put("context_length", m.contextLength)
            obj.put("ai_cost_deducted", m.aiCostDeducted)
            obj.put("ai_mode", (m as? com.aurora.chat.ui.chat.ChatMsg)?.aiMode ?: "")
            // 中断记录的 token 数值是否来自服务端权威结算（false = 客户端估算）；旧数据无此键 → false
            obj.put("ai_usage_authoritative", (m as? com.aurora.chat.ui.chat.ChatMsg)?.aiUsageAuthoritative ?: false)
            obj.put("ai_tool_stage", (m as? com.aurora.chat.ui.chat.ChatMsg)?.aiToolStage ?: "")
            obj.put("ai_tool_stage_state", (m as? com.aurora.chat.ui.chat.ChatMsg)?.aiToolStageState ?: -1)
            obj.put("is_tool_status", (m as? com.aurora.chat.ui.chat.ChatMsg)?.isToolStatus ?: false)
            // 工具调用完整记录列表：每次调用追加留存(含发起时刻毫秒时间戳,供「工具调用」全屏清单展示)
            obj.put("ai_tool_stages", org.json.JSONArray().apply {
                (m as? com.aurora.chat.ui.chat.ChatMsg)?.aiToolStages?.forEach { rec ->
                    put(
                        org.json.JSONObject()
                            .put("tool", rec.tool)
                            .put("state", rec.state)
                            .put("ts", rec.ts)
                            .put("reason", rec.reason)
                            // 具体目标 + 关键参数摘要：「调用 N 个工具」明细页的对账依据
                            .put("target", rec.target)
                            .put("args", rec.argsSummary)
                            // 代码正文（仅 execute_python / serve_start / shell_exec 有值），供明细页代码胶囊复制
                            .put("code", rec.code)
                            // 执行结果与变更预览：内联卡片「输出 / 变更」块的数据源（已截断）
                            .put("output", rec.output)
                            .put("diff", rec.diff)
                    )
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

// ==================== 消息渲染分类（唯一真源） ====================

/**
 * 一条消息是否应渲染为「居中系统提示」（notice 样式）的唯一判定入口。
 *
 * 为什么必须收敛到一个函数：此前全项目有 5 处各自独立的判定
 * （全量同步 / 上滑分页 / 实时增量 / 增量合并 / 磁盘回读），每处条件都不完全相同，
 * 同一条消息走不同路径会得到不同结论；而结论又被压成一个布尔写进本地缓存，
 * 一旦为 true 就永久固化、无法自愈 —— 这就是「普通消息隔一段时间变成广播样式」的结构性成因。
 *
 * 硬性约束：只允许依赖服务端结构化字段，禁止用正文关键词推断类型。
 * 正文是用户数据，用户可以正常打出「撤回了一条消息」「拍了拍」这类词，
 * 用 contains 判定必然把普通聊天误标成系统广播。
 * 仅保留两个例外，它们都是服务端写死的协议正文，不是用户自由输入：
 *   - "POKE"：拍一拍固定正文（friend_handlers.go 保存时写死）
 *   - "群聊邀请结果" 前缀：结构化多行协议文本
 */
internal fun isSystemNoticeMessage(
    isRevoked: Boolean,
    msgType: String,
    broadcastTaskId: Long,
    flashDuration: Int,
    content: String
): Boolean {
    if (isRevoked) return true                   // 撤回
    if (msgType == "system") return true         // 服务端系统消息（隐私告警等）
    if (broadcastTaskId > 0L) return true        // 开发者广播
    if (flashDuration == -1) return true         // 拍一拍（服务端唯一写入点）
    val t = content.trim()
    if (t.equals("POKE", ignoreCase = true)) return true
    if (isGroupConsentNotice(t)) return true
    return false
}

/**
 * 发送失败的分类结果。
 *
 * @param text   本地广播文案（居中系统提示，像微信那样「对方已开启朋友验证」）。
 * @param terminal true=终态业务失败（拉黑 / 好友验证 / 账号异常 / 被移出群 / 被禁言 …），
 *                重试无意义，消息以红叹号气泡留存，并打印本地广播。
 *                false=瞬时失败（网络 / 超时 / 限流），气泡红叹号留存、后台自动重试，不打印广播。
 * @param logout  true=账号已废 / 登录失效，应直接踢下线，不留气泡也不打印广播。
 */
data class SendFailure(val text: String, val terminal: Boolean, val logout: Boolean)

/**
 * 把后端 send_message 返回的失败文案归类成「本地广播」文案。
 *
 * 只认后端写死的协议文案（关键词命中），不臆造。命中即视为「终态业务失败」：
 * 这类错误服务端已经明确拒收，客户端再怎么重试都发不出去，所以不再走自动重试，
 * 而是把消息以红叹号气泡留在本地，并在会话里打印一条本地广播（居中系统提示）留存。
 *
 * 未命中（网络/超时/5xx/限流等）返回 null：仍走瞬时失败路径（红叹号 + 后台自动重试）。
 */
internal fun classifySendFailure(message: String): SendFailure? {
    val m = message
    return when {
        // 账号已废 / 登录失效：直接踢下线（不打印广播、不留气泡）
        m.contains("您的账号异常") -> SendFailure("您的账号异常，操作已禁止", terminal = true, logout = true)
        m.contains("账号异常，请重新登录") -> SendFailure("登录状态已过期，请重新登录", terminal = true, logout = true)
        // 401 token 失效（middleware 放行但解析不到用户）：同样踢下线
        m.contains("无法识别用户身份") -> SendFailure("登录状态已失效，请重新登录", terminal = true, logout = true)
        // 拉黑：区分「对方拉黑我」与「我拉黑对方」（两条文案都已匹配，行为完全对称）
        m.contains("对方已将你拉入黑名单") -> SendFailure("对方已将你拉黑，拉出黑名单后才能发送", terminal = true, logout = false)
        m.contains("你已将对方拉入黑名单") -> SendFailure("你已拉黑对方，拉出黑名单后才能发送", terminal = true, logout = false)
        // 对方开启「非好友禁止发消息」（好友验证）
        m.contains("非好友禁止发消息") -> SendFailure("对方已开启好友验证，添加好友后才能发送", terminal = true, logout = false)
        // 对方账号异常
        m.contains("对方账号异常") -> SendFailure("对方账号异常，消息未送达", terminal = true, logout = false)
        // 群相关
        m.contains("您已被移出该群") -> SendFailure("你已被移出该群，消息未送达", terminal = true, logout = false)
        m.contains("已被禁言") || m.contains("群内禁言") -> SendFailure("你已被禁言，无法发送消息", terminal = true, logout = false)
        m.contains("尚未通过入群审核") -> SendFailure("你尚未通过入群审核，无法发言", terminal = true, logout = false)
        m.contains("全员禁言") -> SendFailure("当前群全员禁言中，无法发言", terminal = true, logout = false)
        // 群内发言频率超限：改成系统广播提示（居中系统提示），不再走弹窗分支。
        // 服务端文案为「本群一分钟内只能发 N 条消息」，原样用作广播文本（数字随群设置动态变化）。
        m.contains("一分钟内只能发") || m.contains("发言过于频繁") || m.contains("每分钟最多") ->
            SendFailure(m, terminal = true, logout = false)
        m.contains("官方群仅开发者") -> SendFailure("官方群仅开发者可发送图片、视频、文件", terminal = true, logout = false)
        // 全站全面禁言（管理员在后台开启，除开发者外一律拒收）
        m.contains("全站已开启全面禁言") -> SendFailure("全站已开启全面禁言，消息发送已暂停", terminal = true, logout = false)
        // 官方群禁言（与「官方群仅开发者可发媒体」是两条独立拦截，关键词不同）
        m.contains("官方群已开启禁言") -> SendFailure("官方群已开启禁言，暂不可发言", terminal = true, logout = false)
        // 群聊不存在 / 已被解散（to<0 时查不到群）
        m.contains("群聊不存在") -> SendFailure("群聊不存在或已解散，消息未送达", terminal = true, logout = false)
        // 客户端签名校验失败：非官方客户端或被篡改签名（极少见，但应明确提示）
        m.contains("请求校验失败") -> SendFailure("请使用官方客户端发送消息", terminal = true, logout = false)
        else -> null
    }
}

// 解析拍拍消息文本
// 注意：群聊拍一拍的 to_user_id 是「群会话 ID（负数）」而非被拍成员 ID，
// 因此不能用 m.toUserId == currentUserId 判断「被拍的是你」——否则群内"X拍了拍你"
// 会被误判成"X拍了拍<你的名字>"。被拍者身份必须取自 targetName（服务端落库的被拍成员名）。
internal fun parsePokeText(m: MessageInfo, currentUserId: Long, currentUserName: String): String {
    val pokerName = if (m.fromUserId == currentUserId) "你" else m.fromUserName.ifEmpty { "对方" }
    val target = if (m.targetName == currentUserName) "你" else m.targetName.ifEmpty { "对方" }
    return if (m.fromUserId == currentUserId && m.targetName == currentUserName) {
        // 自己拍自己（私聊 to_user_id=自己；群聊 to_user_id=群ID、targetName=自己名字）
        "你拍了拍自己"
    } else if (m.fromUserId == currentUserId) {
        "你拍了拍$target"
    } else if (target == "你") {
        "${pokerName}拍了拍你"
    } else {
        "${pokerName}拍了拍$target"
    }
}

// ==================== 群敏感词处置提示 ====================
// 服务端落库的处置提示**不带人称主语**（「因触发该群敏感词被禁言10分钟」），
// to_user_name 存被处置者的名字。渲染时按查看者补主语：
//   被处置者本人 → 「你因触发该群敏感词被禁言10分钟」
//   其他成员     → 「梦的翅膀 因触发该群敏感词被禁言10分钟」
// 与拍一拍按查看者个性化是同一套做法（不能用 toUserId 判断，群聊里它是群会话 ID）。
internal fun isSensitivePunishText(t: String): Boolean =
    t.startsWith("因触发该群敏感词") || t.startsWith("因发送链接")

internal fun parseSensitivePunishText(content: String, targetName: String, currentUserName: String): String {
    if (targetName.isEmpty()) return content
    return if (targetName == currentUserName) "你$content" else "$targetName $content"
}

// ==================== 转账 ====================
// 视角完全由结构化字段现算，不在服务端写死文案，群聊/私聊天然一致
const val TRANSFER_MSG_PREFIX = "系统转账数据"
const val TRANSFER_NOTICE_PREFIX = "系统转账通知"

const val TRANSFER_STATUS_PENDING = "pending"
const val TRANSFER_STATUS_CLAIMED = "claimed"
const val TRANSFER_STATUS_EXPIRED = "expired"

data class TransferData(
    val fromUserId: Long,
    val fromName: String,
    val toUserId: Long,
    val toName: String,
    val amount: Long,
    val transferId: Long = 0L,
    val status: String = TRANSFER_STATUS_CLAIMED,
    val createdAt: Long = 0L,
    val claimedAt: Long = 0L
)

/**
 * 金额拆成「数字」与「单位」两段。
 *
 * · token 是整数单位，普通金额不显示小数位（只加千分位），不再出现 "100.00 token"；
 * · 超大数值用 K/M/B/T 缩写，只有缩写后的近似值才保留小数，且 ".00" 会去掉；
 * · 详情页里数字用大字号、单位用小字号，避免一起按 36sp 排版挤成两行。
 * 卡片等紧凑场景仍可用 [formatTransferAmount] 合成一行。
 */
private fun compactTransferNumber(v: Double, div: Double): String =
    String.format(java.util.Locale.US, "%.2f", v / div).removeSuffix(".00")

fun formatTransferAmountParts(amount: Long): Pair<String, String> {
    val v = amount.toDouble()
    val a = kotlin.math.abs(v)
    // K/M/B/T 跟在数字后面，token 始终是独立的单位段（详情页用小字号显示）
    return when {
        a >= 1e12 -> compactTransferNumber(v, 1e12) + "T" to "token"
        a >= 1e9 -> compactTransferNumber(v, 1e9) + "B" to "token"
        a >= 1e6 -> compactTransferNumber(v, 1e6) + "M" to "token"
        a >= 1e5 -> compactTransferNumber(v, 1e3) + "K" to "token"
        else -> String.format(java.util.Locale.US, "%,d", amount) to "token"
    }
}

/** 数字 + 单位合成一行（聊天卡片、通知等紧凑场景） */
fun formatTransferAmount(amount: Long): String =
    formatTransferAmountParts(amount).let { "${it.first} ${it.second}" }

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
            amount = amount,
            transferId = get("transfer_id").toLongOrNull() ?: 0L,
            status = get("status").ifEmpty { TRANSFER_STATUS_CLAIMED },
            createdAt = get("created_at").toLongOrNull() ?: 0L,
            claimedAt = get("claimed_at").toLongOrNull() ?: 0L
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
fun buildTransferContent(
    fromUserId: Long,
    fromName: String,
    toUserId: Long,
    toName: String,
    amount: Long,
    transferId: Long = 0L,
    status: String = TRANSFER_STATUS_PENDING,
    createdAt: Long = System.currentTimeMillis() / 1000,
    claimedAt: Long = 0L
): String = buildString {
    appendLine(TRANSFER_MSG_PREFIX)
    appendLine("from_user_id=$fromUserId")
    appendLine("from_name=$fromName")
    appendLine("to_user_id=$toUserId")
    appendLine("to_name=$toName")
    appendLine("amount=$amount")
    appendLine("transfer_id=$transferId")
    appendLine("status=$status")
    appendLine("created_at=$createdAt")
    appendLine("claimed_at=$claimedAt")
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

// ==================== 群公告卡片 ====================
// 与后端 groupAnnouncementMsgPrefix 保持一致：content = 前缀 + 公告文本。
// 开启「需确认收到」的公告卡片协议升级为：前缀 + JSON{"text":...,"need_confirm":true}（前端兼容解析旧纯文本）。
// 该前缀消息在群聊中渲染为「群公告」大卡片，长按撤回后由服务端联动移除群公告列表项。
const val GROUP_ANNOUNCEMENT_MSG_PREFIX = "系统公告数据"

/** 判断是否为群公告卡片消息 */
fun isGroupAnnouncementMessage(text: String): Boolean = text.trimStart().startsWith(GROUP_ANNOUNCEMENT_MSG_PREFIX)

/** 提取公告正文（剥离前缀；兼容 JSON 协议与旧纯文本） */
fun parseGroupAnnouncementText(text: String): String {
    val raw = text.trim().removePrefix(GROUP_ANNOUNCEMENT_MSG_PREFIX).trim()
    if (raw.startsWith("{") && raw.endsWith("}")) {
        return try {
            org.json.JSONObject(raw).optString("text", "").trim()
        } catch (_: Exception) { raw }
    }
    return raw
}

/** 解析卡片消息是否开启「需确认收到」（旧纯文本协议一律 false） */
fun parseGroupAnnouncementNeedConfirm(text: String): Boolean {
    val raw = text.trim().removePrefix(GROUP_ANNOUNCEMENT_MSG_PREFIX).trim()
    if (raw.startsWith("{") && raw.endsWith("}")) {
        return try {
            org.json.JSONObject(raw).optBoolean("need_confirm", false)
        } catch (_: Exception) { false }
    }
    return false
}

/** 解析卡片消息携带的公告图片 URL 列表（旧纯文本协议一律为空） */
fun parseGroupAnnouncementImages(text: String): List<String> {
    val raw = text.trim().removePrefix(GROUP_ANNOUNCEMENT_MSG_PREFIX).trim()
    if (raw.startsWith("{") && raw.endsWith("}")) {
        return try {
            val arr = org.json.JSONObject(raw).optJSONArray("images") ?: return emptyList()
            buildList {
                for (j in 0 until arr.length()) {
                    val u = arr.optString(j, "").trim()
                    if (u.isNotEmpty()) add(u)
                }
            }.take(5)
        } catch (_: Exception) { emptyList() }
    }
    return emptyList()
}

/**
 * 归一化会话列表预览里的群公告卡片消息，统一返回「群公告：正文」；非公告消息原样返回。
 *
 * 背景（重要）：服务端群聊预览会在正文前加「发送者: 」前缀（friend_handlers.go 的
 * senderName + ": " + msg），因此列表拿到的群公告预览形如「张三: 系统公告数据{...}」——
 * 开头是发送者名而非公告前缀，裸 startsWith(前缀) 判定必败，原始串直接透出到列表。
 * 这里容忍可选的「发送者: 」前缀后再判定；前缀前面不是「名: 」形态的普通消息不误伤。
 */
fun normalizeGroupAnnouncementPreview(text: String): String {
    val trimmed = text.trimStart()
    val idx = trimmed.indexOf(GROUP_ANNOUNCEMENT_MSG_PREFIX)
    if (idx < 0) return text
    if (idx > 0) {
        val head = trimmed.substring(0, idx)
        if (!head.endsWith(": ") && !head.endsWith(":")) return text
    }
    val t = parseGroupAnnouncementText(trimmed.substring(idx))
    return if (t.isNotEmpty()) "群公告：$t" else "群公告"
}

/**
 * 公告发布日期文案（仅日期）：2026年10月20日；ts<=0 时返回空串。
 *
 * 单位归一化（重要）：调用方传入的时间戳并不统一——
 *  - 群设置公告列表/详情页传 ann.createdAt（epoch 毫秒，发布时 System.currentTimeMillis() 盖戳）
 *  - 聊天内公告卡片/从卡片打开的详情页传消息 createdAt（epoch 秒，与服务端消息时间同单位）
 * 秒级值（~1.7e9）与毫秒级值（~1.7e12）相差 1000 倍，直接 new Date(秒) 会落到 1970-01-22。
 * 这里按量级阈值统一换算成毫秒：> 1e11 视为毫秒原样用，否则视为秒 × 1000。
 */
fun formatAnnouncementDate(ts: Long): String {
    if (ts <= 0L) return ""
    val ms = if (ts > 100_000_000_000L) ts else ts * 1000L
    return try {
        java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.CHINA)
            .format(java.util.Date(ms))
    } catch (_: Exception) {
        ""
    }
}

/** 群公告弹窗/确认所需的最小数据结构（与后端 announcements JSON 字段对应） */
data class GroupAnnouncementInfo(
    val text: String,
    val images: List<String> = emptyList(),
    val popupEnabled: Boolean = false,
    val needConfirm: Boolean = false,
    val confirmedByMe: Boolean = false,
    val confirmCount: Int = 0
)

/** 从群信息 JSON 解析公告列表（兼容新对象结构 / 旧字符串数组 / 旧单条字符串） */
fun parseGroupAnnouncementsFromGroupInfo(groupJson: org.json.JSONObject): List<GroupAnnouncementInfo> {
    val arr = groupJson.optJSONArray("announcements")
    if (arr != null) {
        val list = mutableListOf<GroupAnnouncementInfo>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            if (item is org.json.JSONObject) {
                val images = mutableListOf<String>()
                val imgs = item.optJSONArray("images")
                if (imgs != null) {
                    for (j in 0 until imgs.length()) {
                        val u = imgs.optString(j, "").trim()
                        if (u.isNotEmpty()) images.add(u)
                    }
                }
                val text = item.optString("text", "").trim()
                if (text.isEmpty() && images.isEmpty()) continue
                list.add(
                    GroupAnnouncementInfo(
                        text = text,
                        images = images,
                        popupEnabled = item.optBoolean("popup_enabled", false),
                        needConfirm = item.optBoolean("need_confirm", false),
                        confirmedByMe = item.optBoolean("confirmed_by_me", false),
                        confirmCount = item.optInt("confirm_count", 0)
                    )
                )
            } else {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) list.add(GroupAnnouncementInfo(text = s))
            }
        }
        return list
    }
    val legacy = groupJson.optString("announcement", "").trim()
    return if (legacy.isNotEmpty()) listOf(GroupAnnouncementInfo(text = legacy)) else emptyList()
}

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
        // 文件名必须带扩展名：content:// 媒体 URI 的 lastPathSegment 常是无扩展名的数字 ID，
        // 直接上传会被后端 validateUploadExt 按"无扩展名"拒绝（表现为"任何文件都传不出去"）。
        // 用 MIME 兜底补扩展名，保证后端收到合法文件名。
        val fileName = ensureMediaNameWithExt(uri.lastPathSegment ?: "file", mimeType)
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
    val placeholderMsg = ChatMsg.create(serverId = placeholderId, text = "", isMine = true, fromUserId = currentUserId, isNew = true, mediaType = extraType, mediaUrl = "", isUploading = true, flashDuration = 0, localPreviewUri = uri.toString())
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
                mediaType = if (isVideo) "video" else "image", mediaUrl = localMediaUrl,
                // 沿用占位气泡的发送时刻，避免「处理耗时」把消息时间往后推
                createdAt = placeholderMsg.createdAt
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
    // 与 guessMediaMimeType 的图片集合保持一致（含 heic/heif），避免 HEIC 等被误判为"非图片"导致闪照入口消失
    return ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "avif", "tif", "tiff", "jfif", "svg", "svgz")
}

/**
 * 保证文件名带可用扩展名。系统返回的 DISPLAY_NAME 有时没有扩展名（或整个是数字 ID），
 * 而"是否图片/是否闪照/发送类型"都依赖扩展名判断；这里用 ContentResolver 的 MIME 兜底补一个扩展名。
 * 已有扩展名（且形如 xxx.ext）时原样返回。
 */
fun ensureMediaNameWithExt(rawName: String, mime: String): String {
    val name = rawName.trim().ifBlank { "文件" }
    val ext = name.substringAfterLast('.', "").lowercase()
    // 已带扩展名（必须真的含 '.'）→ 原样返回
    if (name.contains('.') && ext.isNotEmpty() && ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) return name
    val m = mime.lowercase()
    val newExt = when {
        m.startsWith("image/") -> when {
            m.contains("png") -> "png"
            m.contains("gif") -> "gif"
            m.contains("webp") -> "webp"
            m.contains("bmp") -> "bmp"
            m.contains("heic") || m.contains("heif") -> "heic"
            // SVG 必须保留自己的扩展名：补成 jpg 会让内容是 XML 的文件被误标成 JPEG，
            // 服务端按后缀落库后图片打不开，或被兜底改成 .bin
            m.contains("svg") -> "svg"
            else -> "jpg"
        }
        m.startsWith("video/") -> when {
            m.contains("quicktime") || m.contains("mov") -> "mov"
            m.contains("webm") -> "webm"
            m.contains("avi") -> "avi"
            m.contains("3gpp") -> "3gp"
            else -> "mp4"
        }
        m.startsWith("audio/") -> "m4a"
        m.contains("pdf") -> "pdf"
        m.contains("zip") -> "zip"
        m.contains("android.package") || m.contains("apk") -> "apk"
        m.startsWith("text/") -> "txt"
        else -> ""
    }
    if (newExt.isEmpty()) return name
    return if (name.endsWith(".")) name + newExt else "$name.$newExt"
}
