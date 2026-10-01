package com.aurora.chat.data.local

import android.content.Context
import com.tencent.mmkv.MMKV
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 通用本地数据缓存（聊天列表/社区帖子/应用列表/推送配置）
 * 底层使用 MMKV：写操作原地更新、无 apply/commit 阻塞主线程、无整文件重写，避免大 JSON 落盘卡顿与 ANR。
 * 首次访问某偏好文件时通过 [MMKV.importFromSharedPreferences] 从旧 SharedPreferences 一次性迁移，保证升级不丢数据。
 */
object DataCache {

    // 记录已完成一次性迁移的偏好名，避免重复 import
    private val imported = HashSet<String>()

    private fun kv(ctx: Context, name: String): MMKV {
        val m = MMKV.mmkvWithID(name)
        if (imported.add(name)) {
            val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (sp.all.isNotEmpty()) m.importFromSharedPreferences(sp)
        }
        return m
    }

    // ── 聊天列表 ──
    fun saveChatList(ctx: Context, json: String) {
        kv(ctx, "data_cache").encode("chat_list", json)
    }

    fun loadChatList(ctx: Context): String? = kv(ctx, "data_cache").decodeString("chat_list", null)

    // ── 社区帖子 ──
    fun savePost(ctx: Context, postId: Long, json: String) {
        // MMKV 写入 + 文件双重保障（文件供 loadPost 兜底）
        kv(ctx, "data_cache").encode("post_$postId", json)
        try {
            val dir = File(ctx.filesDir, "community_posts")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "${postId}.json").writeText(json)
        } catch (_: Exception) {}
    }

    fun loadPost(ctx: Context, postId: Long): String? {
        kv(ctx, "data_cache").decodeString("post_$postId", null)?.let { return it }
        try {
            val f = File(File(ctx.filesDir, "community_posts"), "${postId}.json")
            if (f.exists()) return f.readText()
        } catch (_: Exception) {}
        return null
    }

    // ── 应用列表 ──
    fun saveAppList(ctx: Context, json: String) {
        kv(ctx, "data_cache").encode("app_list", json)
    }

    fun loadAppList(ctx: Context): String? = kv(ctx, "data_cache").decodeString("app_list", null)

    // ── 消息推送配置（按账号隔离） ──
    fun savePushConfig(ctx: Context, userId: Long, method: Int, templateMode: Int, ownEmail: String, ownAuth: String, dndIds: List<Long> = emptyList(), notifyEnabled: Int = 1) {
        val j = JSONObject()
        j.put("method", method)
        j.put("templateMode", templateMode)
        j.put("ownEmail", ownEmail)
        j.put("ownAuth", ownAuth)
        j.put("dndIds", org.json.JSONArray(dndIds))
        j.put("notifyEnabled", notifyEnabled)
        kv(ctx, "push_config").encode("push_$userId", j.toString())
    }

    private fun loadPushConfig(ctx: Context, userId: Long): JSONObject? {
        val s = kv(ctx, "push_config").decodeString("push_$userId", null) ?: return null
        return try {
            JSONObject(s)
        } catch (_: Exception) {
            null
        }
    }

    /** 推送方式：1=自己的邮箱，2=官方邮箱；默认官方(2) */
    fun loadPushMethod(ctx: Context, userId: Long): Int = loadPushConfig(ctx, userId)?.optInt("method", 2) ?: 2

    /** 提示内容模式：1=提示模板，2=真实消息；默认模板(1) */
    fun loadPushTemplateMode(ctx: Context, userId: Long): Int = loadPushConfig(ctx, userId)?.optInt("templateMode", 1) ?: 1

    fun loadOwnEmail(ctx: Context, userId: Long): String = loadPushConfig(ctx, userId)?.optString("ownEmail", "") ?: ""

    fun loadOwnAuth(ctx: Context, userId: Long): String = loadPushConfig(ctx, userId)?.optString("ownAuth", "") ?: ""

    /** 总通知开关：1=开启（默认），0=关闭（不推送邮箱通知） */
    fun loadNotifyEnabled(ctx: Context, userId: Long): Int = loadPushConfig(ctx, userId)?.optInt("notifyEnabled", 1) ?: 1

    /** 免打扰好友 ID 列表（JSON 数组），用于排除免打扰好友的私聊推送 */
    fun loadPushDndIds(ctx: Context, userId: Long): List<Long> {
        val cfg = loadPushConfig(ctx, userId) ?: return emptyList()
        val arr = cfg.optJSONArray("dndIds") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optLong(it).takeIf { l -> l != 0L } }
    }
}
