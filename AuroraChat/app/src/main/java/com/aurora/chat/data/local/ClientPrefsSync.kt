package com.aurora.chat.data.local

import android.content.Context
import com.aurora.chat.ActivityReadStateManager
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.community.CommunityActivityBadge
import com.aurora.chat.core.domain.ChatEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 客户端偏好的服务端持久化（置顶会话 / 社区动态已读 / 社区排序方式）。
 *
 * 为什么需要它：这三项此前只写在 SharedPreferences 里，重装 APK、换机、清数据之后
 * 全部归零 —— 用户得重新一个个置顶、重新把红点消一遍、重新把「综合」切成「最新」。
 *
 * 做法（不侵入各业务模块的读写路径）：
 *  - 上：把本地现有状态打包成一份 JSON，整份写到服务端用户 KV（key = client_prefs）；
 *  - 下：登录成功 / 冷启动时拉一次，按服务端记录回写本地 SharedPreferences，
 *    这样所有读本地偏好的老代码（会话排序、动态红点、信息流排序）不需要改一行。
 *
 * 冲突策略：
 *  - 置顶、排序：服务端一旦有记录就是权威（另一台设备取消置顶后，本机要跟着取消）；
 *  - 已读动态：取并集。已读只增不减，并集既不会丢本机刚点的，也不会把红点点回来。
 */
object ClientPrefsSync {

    private const val SP_FRIEND = "aurora_friend_settings"
    private const val PIN_PREFIX = "pin_"
    private const val PUSH_DELAY_MS = 600L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null
    private var pullJob: Job? = null

    /** 正在把服务端数据回写本地：此时不接受推送，避免把「改到一半」的状态传上去 */
    @Volatile private var applying = false

    /** 本地改动计数：拉取过程中用户又改了设置时，不能用服务端的旧值覆盖他刚做的操作 */
    @Volatile private var localChangeSeq = 0

    // ==================== 对外入口 ====================

    /**
     * 拉取服务端偏好并回写本地。登录成功后与冷启动各调一次。
     * 服务端还没有记录时，反过来把本地现有设置上传（老用户升级/首次开启时的平滑迁移）。
     */
    fun pull(context: Context) {
        val app = context.applicationContext
        pullJob?.cancel()
        pullJob = scope.launch {
            if (applying) return@launch
            if (AuroraApi.authToken.isNullOrEmpty()) return@launch
            applying = true
            try {
                val seqAtStart = localChangeSeq
                val raw = AuroraApi.fetchClientPrefs() ?: return@launch // 请求失败：保留本地，下次再说
                if (raw.isBlank()) {
                    pushNow(app) // 服务端首次：把本地设置作为初始值上传
                    return@launch
                }
                val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
                var needUpload = false

                // 拉取期间用户又动过设置：服务端那份已经过期，直接以本地为准上传，
                // 否则表现为「刚置顶完，页面自己又取消了」。
                if (localChangeSeq != seqAtStart) {
                    pushNow(app)
                    return@launch
                }

                if (obj.has("pinned")) applyPinned(app, obj.optJSONArray("pinned")) else needUpload = true

                if (obj.has("activity_read_ids")) {
                    val merged = mergeReadIds(app, obj.optJSONArray("activity_read_ids"))
                    val serverIds = obj.optJSONArray("activity_read_ids")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optLong(it, 0L).takeIf { id -> id > 0 } }.toSet()
                    } ?: emptySet()
                    // 本机比服务端多（上次没传成功 / 刚点过）→ 需要补传
                    if (!serverIds.containsAll(merged)) needUpload = true
                    CommunityActivityBadge.refresh(app)
                } else {
                    needUpload = true
                }

                if (obj.has("community_sort")) applySort(app, obj.optString("community_sort")) else needUpload = true

                // 「已读显示」开关：服务端有记录就以服务端为准（另一台设备改过就跟着改）
                if (obj.has("read_receipt")) {
                    ReadReceiptPrefs.set(app, obj.optInt("read_receipt", ReadReceiptPrefs.MODE_INFO_ONLY))
                } else {
                    needUpload = true
                }

                // 「输入框设置」同理：外观属于个人偏好，换机后应保持一致
                if (obj.has("input_bar_style")) {
                    InputBarStylePrefs.set(app, obj.optInt("input_bar_style", InputBarStylePrefs.DEFAULT))
                } else {
                    needUpload = true
                }
                // 自定义样式的参数（圆角 + 三种颜色）跟着一起走，否则换机后「自定义」会掉回初值
                obj.optJSONObject("input_bar_custom")?.let { c ->
                    val d = InputBarStylePrefs.DEFAULT_CUSTOM
                    InputBarStylePrefs.setCustom(
                        app,
                        InputBarStylePrefs.Custom(
                            radius = c.optInt("radius", d.radius),
                            barColor = c.optInt("bar", d.barColor),
                            fieldColor = c.optInt("field", d.fieldColor),
                            borderOn = c.optBoolean("border_on", d.borderOn),
                            borderColor = c.optInt("border", d.borderColor)
                        )
                    )
                }

                if (needUpload) pushNow(app)
            } finally {
                applying = false
            }
        }
    }

    /**
     * 本地偏好发生变化后调用。
     *
     * @param immediate 置顶/排序这类「单次点击」的改动立即上传（用户可能马上杀进程）；
     *                  动态已读可能连续触发多次，走 600ms 合并，避免一次翻页发几十个请求。
     */
    fun schedulePush(context: Context, immediate: Boolean = false) {
        val app = context.applicationContext
        localChangeSeq++
        pushJob?.cancel()
        pushJob = scope.launch {
            if (!immediate) delay(PUSH_DELAY_MS)
            if (applying) {
                // 拉取正在回写本地，这时上传会把服务端旧值再推一遍；
                // 让拉取结束后由它自己按本地状态补传即可
                return@launch
            }
            pushNow(app)
        }
    }

    // ==================== 落地（服务端 → 本地） ====================

    private fun applyPinned(context: Context, arr: JSONArray?) {
        val prefs = context.getSharedPreferences(SP_FRIEND, Context.MODE_PRIVATE)
        val server = mutableSetOf<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val k = arr.optString(i, "")
                if (k.startsWith(PIN_PREFIX)) server.add(k)
            }
        }
        // 先取快照再改，避免边遍历边写
        val localKeys = prefs.all.keys.filter { it.startsWith(PIN_PREFIX) }
        var changed = false
        val ed = prefs.edit()
        // 服务端有记录即为权威：本地有、服务端没有 = 已在别处取消置顶
        for (k in localKeys) {
            if (k !in server && prefs.getBoolean(k, false)) {
                ed.putBoolean(k, false)
                changed = true
            }
        }
        for (k in server) {
            if (!prefs.getBoolean(k, false)) {
                ed.putBoolean(k, true)
                changed = true
            }
        }
        if (changed) {
            ed.apply()
            ChatEvents.sink.notifyPinChanged()
        }
    }

    /** 已读动态取并集（只增不减）：返回合并后的集合，供调用方判断是否需要补传 */
    private fun mergeReadIds(context: Context, arr: JSONArray?): Set<Long> {
        val server = mutableSetOf<Long>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val id = arr.optLong(i, 0L)
                if (id > 0) server.add(id)
            }
        }
        val merged = ActivityReadStateManager.getReadActivityIds(context) + server
        ActivityReadStateManager.applyFromServer(context, merged)
        return merged
    }

    private fun applySort(context: Context, value: String) {
        CommunitySortPrefs.set(context, value)
    }

    // ==================== 上传（本地 → 服务端） ====================

    /** 打包本地当前状态。置顶来自 SharedPreferences 里所有值为 true 的 pin_* 键 */
    private fun snapshot(context: Context): String {
        val pinned = JSONArray()
        context.getSharedPreferences(SP_FRIEND, Context.MODE_PRIVATE).all
            .filter { (k, v) -> k.startsWith(PIN_PREFIX) && v == true }
            .keys.sorted()
            .forEach { pinned.put(it) }

        val ids = JSONArray()
        ActivityReadStateManager.getReadActivityIds(context).sorted().forEach { ids.put(it) }

        val sort = CommunitySortPrefs.get(context)
        val custom = InputBarStylePrefs.custom(context)

        return JSONObject()
            .put("pinned", pinned)
            .put("activity_read_ids", ids)
            .put("community_sort", sort)
            .put("read_receipt", ReadReceiptPrefs.get(context))
            .put("input_bar_style", InputBarStylePrefs.get(context))
            .put(
                "input_bar_custom",
                JSONObject()
                    .put("radius", custom.radius)
                    .put("bar", custom.barColor)
                    .put("field", custom.fieldColor)
                    .put("border_on", custom.borderOn)
                    .put("border", custom.borderColor)
            )
            .toString()
    }

    private suspend fun pushNow(context: Context) {
        if (AuroraApi.authToken.isNullOrEmpty()) return
        runCatching { AuroraApi.saveClientPrefs(snapshot(context)) }
    }
}
