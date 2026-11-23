package com.aurora.chat.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「我已不在这个群里」的本地记录。
 *
 * ## 为什么需要它
 * 群会话在会话列表里是**跟着成员身份走**的：服务端 `/api/conversations` 用 `getGroupsForUser` 拼群聊，
 * 一旦被移出，这个群立刻从群列表里消失，客户端表现出来就是「会话凭空蒸发」——
 * 没有提示、没有解释，历史消息也再也点不进去。QQ/微信不是这个行为：会话会留着，变成只读，
 * 最后一条是「XX 将你踢出群聊」。
 *
 * 所以这里在本地留一条「退出记录」。会话列表会把这类群重新拼回去（预览用退出提示），
 * 对话页据此切换成只读态：历史照常翻，输入区整块隐藏，群信息面板只留能看的部分。
 *
 * ## 什么时候清掉
 * 重新入群（服务端群列表里又出现这个群）时由 `ChatViewModel.loadConversations` 删除记录，
 * 会话恢复正常；用户主动删除会话时也会一并删除。
 *
 * 存储用独立 JSON 文件而不是 SQLite：条目数量等于「这辈子被踢过的群」，量级极小，
 * 且写入只发生在被踢那一瞬间（不参与消息热路径）。
 */
data class GroupExit(
    /** 群的真实 ID（不是会话 ID）。会话 ID = -(1000 + groupId) */
    val groupId: Long,
    val groupName: String = "",
    /** 操作者昵称（「谁把你踢出去的」）；退群/解散等场景可为空 */
    val operatorName: String = "",
    /** 发生时间（秒，与服务端 created_at 同口径） */
    val time: Long = 0,
    /** kicked = 被管理员移出；left = 自己退出；dissolved = 群被解散 */
    val reason: String = REASON_KICKED,
    /**
     * 服务端指定的完整文案（可空）。
     *
     * 「被踢」有很多成因，不同成因该说的话不一样：手动踢人是「XX 将你踢出群聊」，
     * 触发群敏感词是「你因触发该群敏感词已被踢出群聊」。成因的判定只有服务端知道，
     * 所以由它把整句发下来，客户端不再靠 operator_name 拼装——拼出来的话永远缺因果，
     * 而且早期实现把 operator_name 填成「群敏感词」，界面上就像有个叫这名字的人踢了你。
     */
    val customText: String = ""
) {
    /** 会话 ID（负值，与 ChatScreen 里群会话的约定一致） */
    val convId: Long get() = -(1000 + groupId)

    /**
     * 会话列表预览 / 对话内系统提示的文案。
     * 被踢时带上「谁踢的」，这是用户唯一能从界面上得到的解释。
     *
     * 注意兜底分支：会话从服务端群列表里消失，成因只有「被移出」这一种
     * （自己退群走的是另一条路，群解散有独立标记）。早期版本用 "unknown" 记过一批，
     * 那时文案退化成「你已不在该群」——含糊到没有任何信息量。这里统一按被踢表述，
     * 老记录不改文件也能立刻显示正确文案。
     */
    val text: String
        get() = when {
            // 服务端给了完整文案就以它为准（成因只有服务端知道）
            customText.isNotBlank() -> customText
            reason == REASON_LEFT -> "你已退出群聊"
            // 解散是一个确定事件：群没了，不是「你被处理了」。
            // 早期版本落到兜底分支，群主解散完自己的群，底部写的却是「你已被踢出群聊」。
            reason == REASON_DISSOLVED -> "群主已解散群聊"
            operatorName.isNotEmpty() -> "$operatorName 将你踢出群聊"
            else -> "你已被踢出群聊"
        }

    companion object {
        const val REASON_KICKED = "kicked"
        const val REASON_LEFT = "left"
        const val REASON_DISSOLVED = "dissolved"
    }
}

object GroupExitStore {

    private const val FILE_NAME = "group_exits.json"

    /**
     * 进程内缓存。
     *
     * 对话页判断「我是不是被踢了」必须在组合期同步拿到结果（晚一拍，成员校验就会先把消息清空），
     * 所以这里缓存一份，避免每次进会话都在主线程读文件。写入时同步更新。
     */
    @Volatile
    private var cache: List<GroupExit>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    private fun toJson(e: GroupExit): JSONObject = JSONObject().apply {
        put("groupId", e.groupId)
        put("groupName", e.groupName)
        put("operatorName", e.operatorName)
        put("time", e.time)
        put("reason", e.reason)
        put("customText", e.customText)
    }

    private fun fromJson(o: JSONObject): GroupExit? {
        val gid = o.optLong("groupId", 0)
        if (gid <= 0) return null
        return GroupExit(
            groupId = gid,
            groupName = o.optString("groupName", ""),
            operatorName = o.optString("operatorName", ""),
            time = o.optLong("time", 0),
            reason = o.optString("reason", GroupExit.REASON_KICKED),
            customText = o.optString("customText", "")
        )
    }

    /** 写入/覆盖一条退出记录（同一个群只保留最新一条）。 */
    @Synchronized
    fun record(ctx: Context, exit: GroupExit) {
        try {
            val all = all(ctx).filter { it.groupId != exit.groupId }
            write(ctx, all + exit)
        } catch (_: Exception) {}
    }

    /** 按群 ID 查（null = 我还在这个群里，或从未记录过）。 */
    fun get(ctx: Context, groupId: Long): GroupExit? =
        if (groupId <= 0) null else all(ctx).firstOrNull { it.groupId == groupId }

    /** 按会话 ID（负值）查，供对话页直接判断。 */
    fun getByConv(ctx: Context, convId: Long): GroupExit? =
        if (convId >= 0) null else get(ctx, -(convId + 1000))

    fun all(ctx: Context): List<GroupExit> {
        cache?.let { return it }
        val f = file(ctx)
        if (!f.exists()) {
            cache = emptyList()
            return emptyList()
        }
        val list = try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
        cache = list
        return list
    }

    /** 删除记录（重新入群 / 用户清掉会话时调用）。 */
    @Synchronized
    fun remove(ctx: Context, groupId: Long) {
        try {
            val rest = all(ctx).filter { it.groupId != groupId }
            write(ctx, rest)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun clear(ctx: Context) {
        cache = emptyList()
        try {
            file(ctx).delete()
        } catch (_: Exception) {}
    }

    private fun write(ctx: Context, list: List<GroupExit>) {
        // 先更新缓存再落盘：即使写文件失败，本次进程内的判断也是正确的
        cache = list
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
