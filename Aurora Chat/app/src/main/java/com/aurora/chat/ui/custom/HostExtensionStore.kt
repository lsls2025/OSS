package com.aurora.chat.ui.custom

import android.content.Context
import org.json.JSONArray

/**
 * 宿主扩展的本地注册表 / 存储。
 *
 * 刻意**不上传服务端、不复用 plugin_submissions 表**：这些是本机私有的 UI 配置，
 * 类型、权限模型、发布渠道都和「AI 可调用的 http_tool 模板」不同。
 *
 * 用 SharedPreferences 存一段 JSON 数组即可：数据量小，冷启动同步读取不阻塞首帧。
 *
 * 重要：**默认没有任何内置扩展**。首次安装是一张空列表，应用启动保持原来的系统启动流程，
 * 不会多出任何一层画面。只有用户在「自定义」里亲手创建并显式启用后，扩展才会生效。
 */
object HostExtensionStore {
    private const val PREF_NAME = "aurora_host_extensions"
    private const val KEY_LIST = "list"
    private const val KEY_SCHEMA = "schema_version"
    private const val SCHEMA_VERSION = 2

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 只写入空的注册表，不再预置任何启动画面。 */
    fun ensureInitialized(ctx: Context) {
        val p = prefs(ctx)
        if (p.getInt(KEY_SCHEMA, 0) == SCHEMA_VERSION) return
        // v1 曾内置一张默认启动画面；升级时一并清掉，回到"默认无扩展"。
        p.edit().putString(KEY_LIST, JSONArray().toString())
            .putInt(KEY_SCHEMA, SCHEMA_VERSION).apply()
    }

    /**
     * 读取注册表。单条坏数据直接丢掉，不让一段畸形 JSON 拖垮整页；
     * 注意这里**不**过滤安全模式隔离项 —— 管理页必须还能看见、能删除它们。
     */
    fun all(ctx: Context): List<HostExtension> {
        val raw = prefs(ctx).getString(KEY_LIST, null) ?: return emptyList()
        return ExtensionSafety.guard(ctx, "读取自定义配置", emptyList(), critical = true) {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { HostExtension.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }
    }

    /** 运行时可用列表：保护模式或被隔离的扩展不参与启动与悬浮窗恢复。 */
    fun usable(ctx: Context): List<HostExtension> = guardLoad(ctx).filter {
        !ExtensionSafety.isBad(ctx, it.id)
    }

    private fun guardLoad(ctx: Context): List<HostExtension> =
        if (ExtensionSafety.isSafeMode(ctx)) emptyList() else all(ctx)

    fun saveAll(ctx: Context, list: List<HostExtension>) {
        ExtensionSafety.guard(ctx, "保存自定义配置", Unit) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            prefs(ctx).edit().putString(KEY_LIST, arr.toString())
                .putInt(KEY_SCHEMA, SCHEMA_VERSION).apply()
        }
    }

    fun upsert(ctx: Context, ext: HostExtension) {
        val next = all(ctx).toMutableList()
        val idx = next.indexOfFirst { it.id == ext.id }
        if (idx >= 0) next[idx] = ext else next.add(ext)
        saveAll(ctx, next)
    }

    fun remove(ctx: Context, id: String) {
        saveAll(ctx, all(ctx).filterNot { it.id == id })
    }

    /** 启动画面同一时刻只允许一个生效；悬浮窗将来允许多个并存。 */
    fun setEnabled(ctx: Context, current: List<HostExtension>, id: String, on: Boolean): List<HostExtension> {
        val target = current.firstOrNull { it.id == id } ?: return current
        val now = System.currentTimeMillis()
        val next = if (on && target.kind == ExtensionKind.LAUNCH_SCREEN) {
            current.map {
                when {
                    it.id == id -> it.copy(enabled = true, updatedAt = now)
                    it.kind == ExtensionKind.LAUNCH_SCREEN -> it.copy(enabled = false, updatedAt = now)
                    else -> it
                }
            }
        } else {
            current.map { if (it.id == id) it.copy(enabled = on, updatedAt = now) else it }
        }
        saveAll(ctx, next)
        return next
    }

    /** 当前应展示的启动扩展；null = 不展示任何自定义层，走原来的朴素启动。 */
    fun activeLaunch(ctx: Context): HostExtension? = ExtensionSafety.guard(
        ctx, "加载启动画面", null, critical = true
    ) {
        usable(ctx).firstOrNull { it.enabled && it.kind == ExtensionKind.LAUNCH_SCREEN }
    }

    /** 清空全部扩展，恢复"默认无扩展"的开箱状态。 */
    fun clear(ctx: Context) = saveAll(ctx, emptyList())

    /** 新建一张空白扩展：默认关闭，必须由用户显式启用。 */
    fun blank(kind: ExtensionKind, name: String): HostExtension = HostExtension(
        id = "ext_" + System.nanoTime().toString(),
        name = name,
        kind = kind,
        enabled = false,
        builtin = false,
        trigger = if (kind == ExtensionKind.LAUNCH_SCREEN)
            ExtensionTrigger.COLD_START else ExtensionTrigger.MANUAL,
        skipOnExternalIntent = true,
        durationMs = 1500L,
        tapToDismiss = false,
        action = ElementAction.NONE,
        floatingStyle = FloatingStyle.CARD,
        floatingWidthDp = 240f,
        floatingHeightDp = 150f,
        floatingAutoExpand = true,
        floatingSnapToSide = false,
        canvas = if (kind == ExtensionKind.FLOATING_WINDOW) {
            // 新建悬浮窗就是一张空白卡片：画布尺寸给好，内容用户自己加或套模板
            CanvasSpec(
                widthDp = 360f,
                heightDp = 160f,
                background = 0xFFFFFFFF,
                elements = emptyList()
            )
        } else {
            CanvasSpec(
                widthDp = 360f,
                heightDp = 640f,
                background = 0xFFFFFFFF,
                elements = emptyList()
            )
        },
        updatedAt = System.currentTimeMillis()
    )
}
