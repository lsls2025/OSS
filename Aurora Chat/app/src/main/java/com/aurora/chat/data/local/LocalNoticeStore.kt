package com.aurora.chat.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地系统广播（信息广播）的持久化。
 *
 * 场景：发送消息被服务器以「业务原因」拒绝时（对方拉黑 / 对方开启好友验证 / 对方账号异常 /
 * 被移出群 / 被禁言 / 未通过入群审核 …），客户端在会话里就地打印一条居中的系统提示，
 * 并且这条提示是「本地广播」——由客户端自己渲染、自己留存，不依赖服务器推送，
 * 重启 App、杀进程、切会话再回来都还在（像微信那样「对方已开启朋友验证」一直挂着）。
 *
 * 为什么单独建一个 store 而不走 LocalMessageStore：
 * 好友会话的消息落盘会刻意过滤掉 isSystemNotice（避免把普通聊天误固化为广播、避免与服务器撤回派生重复），
 * 而本地广播是客户端自己生成的、需要长期留存，与那套过滤规则冲突，故独立文件存储。
 *
 * 去重：同一会话里 60 秒内出现完全相同文案的广播只保留一条，防止「被拉黑后反复点发送」刷出一屏幕提示。
 */
data class LocalNotice(
    val conversationId: Long,
    val text: String,
    val createdAt: Long,   // 秒
    val type: String = "" // 分类（blocked / friend_verify / account_abnormal …），便于将来扩展
)

object LocalNoticeStore {

    private const val FILE_NAME = "local_notices.json"
    private const val DEDUP_WINDOW_SEC = 60L

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    private fun toJson(n: LocalNotice): JSONObject = JSONObject().apply {
        put("conversationId", n.conversationId)
        put("text", n.text)
        put("createdAt", n.createdAt)
        put("type", n.type)
    }

    private fun fromJson(o: JSONObject): LocalNotice = LocalNotice(
        conversationId = o.optLong("conversationId"),
        text = o.optString("text", ""),
        createdAt = o.optLong("createdAt", 0),
        type = o.optString("type", "")
    )

    /** 写入一条本地广播；同一会话 60 秒内同文案去重。 */
    @Synchronized
    fun save(ctx: Context, notice: LocalNotice) {
        try {
            val all = all(ctx).toMutableList()
            val now = notice.createdAt
            val dup = all.any {
                it.conversationId == notice.conversationId &&
                    it.text == notice.text &&
                    now - it.createdAt < DEDUP_WINDOW_SEC
            }
            if (dup) return
            all.add(notice)
            write(ctx, all)
        } catch (_: Exception) {}
    }

    /** 读取某个会话的全部本地广播（按时间升序，与消息列表同口径：旧在前）。 */
    fun list(ctx: Context, conversationId: Long): List<LocalNotice> =
        all(ctx).filter { it.conversationId == conversationId }.sortedBy { it.createdAt }

    /** 删除某个会话的全部本地广播（清空聊天记录时调用）。 */
    @Synchronized
    fun clear(ctx: Context, conversationId: Long) {
        try {
            write(ctx, all(ctx).filter { it.conversationId != conversationId })
        } catch (_: Exception) {}
    }

    private fun all(ctx: Context): List<LocalNotice> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(ctx: Context, list: List<LocalNotice>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(toJson(it)) }
            val f = file(ctx)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        } catch (_: Exception) {}
    }
}
