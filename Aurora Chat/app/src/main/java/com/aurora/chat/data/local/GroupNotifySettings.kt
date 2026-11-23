package com.aurora.chat.data.local

import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 群通知方式设置（按群维度，服务端持久化 + 本地内存缓存）。
 *
 * 两种互斥方式：
 *  - [MODE_GROUP]     以群身份通知：提示以群的名义发出，正文带 @成员（可点击跳转）
 *  - [MODE_BROADCAST] 以系统广播通知：灰色居中提示，不带 @，不可点击
 *
 * 逐项开关见 [GroupNotices.items]，关闭的项服务端直接不发（真实生效）。
 */
data class GroupNotifySettings(
    val mode: String = MODE_GROUP,
    val disabled: Set<String> = emptySet()
) {
    fun enabled(key: String): Boolean = key !in disabled
    val isGroupIdentity: Boolean get() = mode != MODE_BROADCAST

    companion object {
        const val MODE_GROUP = "group"
        const val MODE_BROADCAST = "broadcast"
        val DEFAULT = GroupNotifySettings()
    }
}

/** 一条可开关的群提示定义 */
data class GroupNoticeItem(
    val key: String,
    val title: String,
    val category: String,
    /** 群身份方式下的示例文案（带 @） */
    val identitySample: String,
    /** 系统广播方式下的示例文案（不带 @） */
    val broadcastSample: String
)

/**
 * 群提示项清单。key 必须与服务端 group_notify.go 中的 notifyKey* 一致，
 * 新增项要同时改服务端，否则开关不会生效。
 */
object GroupNotices {
    const val KEY_MEMBER_JOIN = "member_join"
    const val KEY_MEMBER_LEAVE = "member_leave"
    const val KEY_MEMBER_KICK = "member_kick"
    const val KEY_OWNER_TRANSFER = "owner_transfer"
    const val KEY_MUTE = "mute"
    const val KEY_UNMUTE = "unmute"
    const val KEY_ADMIN_CHANGE = "admin_change"
    const val KEY_RECALL = "recall"

    val items: List<GroupNoticeItem> = listOf(
        GroupNoticeItem(
            key = KEY_MEMBER_JOIN,
            title = "新成员入群",
            category = "成员变动",
            identitySample = "@小星 加入了群聊",
            broadcastSample = "小星 加入了群聊"
        ),
        GroupNoticeItem(
            key = KEY_MEMBER_LEAVE,
            title = "成员主动退群",
            category = "成员变动",
            identitySample = "@小星 退出了群聊",
            broadcastSample = "小星 退出了群聊"
        ),
        GroupNoticeItem(
            key = KEY_MEMBER_KICK,
            title = "成员被移出群聊",
            category = "成员变动",
            identitySample = "@群主 将 @小星 移出了群聊",
            broadcastSample = "群主 将 小星 移出了群聊"
        ),
        GroupNoticeItem(
            key = KEY_OWNER_TRANSFER,
            title = "群主转让",
            category = "成员变动",
            identitySample = "@小星 成为了新群主",
            broadcastSample = "小星 成为了新群主"
        ),
        GroupNoticeItem(
            key = KEY_MUTE,
            title = "成员被禁言",
            category = "管理操作",
            identitySample = "@管理员 将 @小星 禁言了1小时",
            broadcastSample = "管理员 将 小星 禁言了1小时"
        ),
        GroupNoticeItem(
            key = KEY_UNMUTE,
            title = "成员被解除禁言",
            category = "管理操作",
            identitySample = "@管理员 已将 @小星 解除禁言",
            broadcastSample = "管理员 已将 小星 解除禁言"
        ),
        GroupNoticeItem(
            key = KEY_ADMIN_CHANGE,
            title = "管理员变更",
            category = "管理操作",
            identitySample = "@群主 将 @小星 设为管理员",
            broadcastSample = "群主 将 小星 设为管理员"
        ),
        GroupNoticeItem(
            key = KEY_RECALL,
            title = "消息撤回",
            category = "消息",
            identitySample = "小星 撤回了一条消息",
            broadcastSample = "小星 撤回了一条消息"
        )
    )

    fun categories(): List<String> = items.map { it.category }.distinct()
    fun itemsOf(category: String): List<GroupNoticeItem> = items.filter { it.category == category }
    fun sampleOf(item: GroupNoticeItem, mode: String): String =
        if (mode == GroupNotifySettings.MODE_BROADCAST) item.broadcastSample else item.identitySample
}

/**
 * 进程内缓存：进群设置页 / 进群会话时拉一次，撤回等本地合成的提示据此判断是否展示。
 */
object GroupNotifyPrefs {
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, GroupNotifySettings>()

    fun get(groupId: Long): GroupNotifySettings = cache[groupId] ?: GroupNotifySettings.DEFAULT

    fun put(groupId: Long, settings: GroupNotifySettings) {
        if (groupId > 0) cache[groupId] = settings
    }

    fun clear() = cache.clear()

    /** 从服务端刷新；失败时保留旧缓存并返回 null */
    suspend fun load(groupId: Long): GroupNotifySettings? = withContext(Dispatchers.IO) {
        if (groupId <= 0) return@withContext null
        try {
            val r = AuroraApi.getGroupNotifySettings(groupId)
            val data = r.data
            if (!r.success || data == null) return@withContext null
            val mode = data.optString("mode", GroupNotifySettings.MODE_GROUP)
            val arr = data.optJSONArray("disabled")
            val disabled = buildSet {
                if (arr != null) for (i in 0 until arr.length()) {
                    val k = arr.optString(i, "")
                    if (k.isNotBlank()) add(k)
                }
            }
            val settings = GroupNotifySettings(
                mode = if (mode == GroupNotifySettings.MODE_BROADCAST) GroupNotifySettings.MODE_BROADCAST
                else GroupNotifySettings.MODE_GROUP,
                disabled = disabled
            )
            put(groupId, settings)
            settings
        } catch (_: Exception) {
            null
        }
    }
}
