package com.aurora.chat.ui.custom

import org.json.JSONArray
import org.json.JSONObject

/**
 * 宿主扩展（HostExtension）数据模型。
 *
 * 设计前提：**面向拖拽可视化编辑器**，不是给程序员写的配置文本。
 * 所以「外观」用一张虚拟画布 + 一串可拖拽元素描述，每个元素带 x/y/w/h/旋转/样式，
 * 编辑器直接读写这些字段即可，预览与真机渲染共用同一个 [ExtensionCanvas]。
 *
 * 一个扩展 = 触发器(trigger) + 画布(appearance) + 动作(action) 三元组。
 * 启动画面与悬浮窗共用这套模型，区别只在 [ExtensionKind] 与触发器取值。
 *
 * 安全边界：动作是宿主白名单枚举，不接受任意代码；触发器也只能从枚举里选。
 */

enum class ExtensionKind(val key: String, val label: String) {
    LAUNCH_SCREEN("launch_screen", "启动画面"),
    FLOATING_WINDOW("floating_window", "悬浮窗");

    companion object {
        fun from(key: String): ExtensionKind = entries.firstOrNull { it.key == key } ?: LAUNCH_SCREEN
    }
}

enum class ExtensionTrigger(val key: String, val label: String) {
    COLD_START("cold_start", "仅冷启动"),
    EVERY_START("every_start", "每次回到前台"),
    FIRST_LAUNCH("first_launch", "仅首次安装"),
    NOTIFICATION_ACTION("notification_action", "通知按钮"),
    APP_SHORTCUT("app_shortcut", "桌面快捷方式"),
    APP_EVENT("app_event", "应用内事件"),
    AGENT("agent", "由 AI 唤起"),
    MANUAL("manual", "手动点击");

    companion object {
        fun from(key: String): ExtensionTrigger = entries.firstOrNull { it.key == key } ?: MANUAL
    }
}

/**
 * 画布元素被点击时执行什么。
 *
 * 分组顺序就是选择器里的显示顺序：**硬件 / 权限这类真正能操控手机的能力排最上面**，
 * 复制、分享之类几乎用不上的放最后。全部是宿主白名单，不接受任意代码。
 */
enum class ElementAction(
    val key: String,
    val label: String,
    val group: String,
    /** 非空表示该动作需要一个参数，属性面板会给出输入框 */
    val hint: String = ""
) {
    // ── 硬件：手电、振动、媒体、音量、屏幕 ──
    FLASHLIGHT("flashlight", "手电筒开关", "硬件"),
    TORCH_SOS("torch_sos", "手电爆闪", "硬件"),
    TORCH_LEVEL("torch_level", "手电亮度", "硬件", "1-5，留空循环切换"),
    VIBRATE("vibrate", "振动", "硬件", "毫秒，留空 80"),
    VIBRATE_PATTERN("vibrate_pattern", "振动节奏", "硬件", "如 0,100,50,100"),
    MEDIA_PLAY("media_play", "播放暂停", "硬件"),
    MEDIA_NEXT("media_next", "下一首", "硬件"),
    MEDIA_PREV("media_prev", "上一首", "硬件"),
    VOLUME_UP("volume_up", "音量加", "硬件"),
    VOLUME_DOWN("volume_down", "音量减", "硬件"),
    VOLUME_MUTE("volume_mute", "静音切换", "硬件"),
    KEEP_SCREEN_ON("keep_screen_on", "屏幕常亮", "硬件"),
    SCREEN_BRIGHT("screen_bright", "屏幕亮度", "硬件", "0-255"),
    AUTO_BRIGHTNESS("auto_brightness", "自动亮度", "硬件"),
    ROTATION_LOCK("rotation_lock", "旋转锁定", "硬件"),
    RINGER_MODE("ringer_mode", "铃音模式", "硬件", "normal / vibrate / silent"),
    DND_TOGGLE("dnd_toggle", "勿扰开关", "硬件"),
    WAKE_SCREEN("wake_screen", "点亮屏幕", "硬件"),

    // ── 权限：需要系统授权的能力，没授权时自动跳对应设置页 ──
    PERM_WRITE_SETTINGS("perm_write_settings", "修改系统设置", "权限"),
    PERM_DND("perm_dnd", "勿扰权限", "权限"),
    PERM_OVERLAY("perm_overlay", "悬浮窗权限", "权限"),
    PERM_BATTERY("perm_battery", "电池优化白名单", "权限"),
    PERM_USAGE("perm_usage", "使用情况权限", "权限"),
    PERM_ALL_FILES("perm_all_files", "所有文件权限", "权限"),
    APP_DETAILS("app_details", "本应用详情页", "权限"),
    SET_TIMER("set_timer", "系统计时器", "权限", "秒数，留空 60"),
    SET_ALARM("set_alarm", "设置闹钟", "权限", "07:30"),
    WIFI_PANEL("wifi_panel", "WLAN 面板", "权限"),
    BT_PANEL("bt_panel", "蓝牙面板", "权限"),
    NFC_PANEL("nfc_panel", "NFC 面板", "权限"),
    LOCATION_PANEL("location_panel", "定位面板", "权限"),
    SOUND_PANEL("sound_panel", "声音面板", "权限"),
    AIRPLANE_PANEL("airplane_panel", "飞行模式面板", "权限"),
    BATTERY_PANEL("battery_panel", "电池面板", "权限"),
    PRIVACY_PANEL("privacy_panel", "隐私面板", "权限"),

    // ── 窗口：只影响这个悬浮窗自己 ──
    NONE("none", "跟随整体", "窗口"),
    COLLAPSE("collapse", "收起浮窗", "窗口"),
    EXPAND("expand", "展开浮窗", "窗口"),
    CLOSE("close", "关闭浮窗", "窗口"),
    TOGGLE_LOCK("toggle_lock", "锁定位置", "窗口"),
    REFRESH("refresh", "重绘画面", "窗口"),
    TOUCH_THROUGH("touch_through", "穿透点击", "窗口"),

    // ── 跳转 ──
    BACK_TO_APP("back", "回到应用", "跳转"),
    OPEN_URL("url", "打开链接", "跳转", "https://..."),
    OPEN_APP("app", "打开应用", "跳转", "应用包名"),
    OPEN_SETTINGS("settings", "指定设置页", "跳转", "wifi / bluetooth / display / sound"),
    DIAL("dial", "拨号", "跳转", "电话号码"),
    SMS("sms", "发短信", "跳转", "电话号码"),

    // ── 数据：变量、元素状态、秒表 ──
    TTS_SPEAK("tts_speak", "语音朗读", "数据", "要念出来的文字，支持 {变量}"),
    TOGGLE_ELEMENT("toggle_element", "显隐元素", "数据", "元素序号，图层面板里的 1、2、3"),
    TIMER_TOGGLE("timer_toggle", "秒表开始暂停", "数据"),
    TIMER_RESET("timer_reset", "秒表归零", "数据"),

    // ── 杂项：低频，放最底下 ──
    TOAST("toast", "弹出提示", "杂项", "提示内容，支持 {变量}"),
    COPY_TEXT("copy", "复制文本", "杂项", "要复制的文字，支持 {变量}"),
    SHARE_TEXT("share", "分享文本", "杂项", "要分享的文字，支持 {变量}");

    val needsValue: Boolean get() = hint.isNotEmpty()

    companion object {
        val groups = listOf("硬件", "权限", "窗口", "跳转", "数据", "杂项")

        // v2 之前的整体动作单独有一套枚举，key 不一样；这里做兼容映射。
        private val legacyKeys = mapOf(
            "open_tab" to "back",
            "open_url" to "url",
            "open_app" to "app",
            "run_agent" to "none",
            "send_message" to "none",
            "plugin_call" to "none",
            "media_play_pause" to "media_play"
        )

        fun from(key: String): ElementAction {
            val k = legacyKeys[key] ?: key
            return entries.firstOrNull { it.key == k } ?: NONE
        }
    }
}

/**
 * 悬浮窗的形态。CARD = 不折叠、直接常驻显示画布；其余三种可折叠成把手，点一下展开。
 * 展开态的标题栏、收起/关闭按钮都是可选装饰（默认全关），收起、关闭、锁定这些行为
 * 都可以由用户自己在画布上放按钮并挂对应动作，宿主不强加任何 chrome。
 */
enum class FloatingStyle(val key: String, val label: String) {
    CARD("card", "卡片"),
    BAR("bar", "悬浮条"),
    BALL("ball", "悬浮球"),
    MINI("mini", "小图标");

    val collapsible: Boolean get() = this != CARD

    companion object {
        fun from(key: String): FloatingStyle = entries.firstOrNull { it.key == key } ?: CARD
    }
}

/** 悬浮窗初始位置；REMEMBER 用上次拖动后的坐标。 */
enum class FloatingAnchor(val key: String, val label: String) {
    REMEMBER("remember", "记住位置"),
    TOP_LEFT("top_left", "左上"),
    TOP_RIGHT("top_right", "右上"),
    CENTER("center", "居中"),
    BOTTOM_LEFT("bottom_left", "左下"),
    BOTTOM_RIGHT("bottom_right", "右下");

    companion object {
        fun from(key: String): FloatingAnchor = entries.firstOrNull { it.key == key } ?: REMEMBER
    }
}

/** 画布元素种类 —— 拖拽编辑器的「可添加组件」清单。 */
enum class CanvasElementKind(val key: String, val label: String) {
    TEXT("text", "文字"),
    IMAGE("image", "图片"),
    ICON("icon", "图标"),
    SHAPE("shape", "色块"),
    BUTTON("button", "按钮");

    companion object {
        fun from(key: String): CanvasElementKind = entries.firstOrNull { it.key == key } ?: TEXT
    }
}

/**
 * 画布上的一个元素。坐标/尺寸单位都是**设计 dp**（相对 [CanvasSpec.widthDp] / heightDp），
 * 渲染时整体等比缩放到实际屏幕，编辑器里拖动改的就是这四个值。
 */
data class CanvasElement(
    val id: String = "el_" + System.nanoTime().toString(),
    val kind: CanvasElementKind = CanvasElementKind.TEXT,
    val xDp: Float = 0f,
    val yDp: Float = 0f,
    val widthDp: Float = 200f,
    val heightDp: Float = 40f,
    val rotation: Float = 0f,
    val text: String = "",
    /** 本地图片绝对路径；为空时图片元素回退到内置品牌图。 */
    val assetPath: String = "",
    val fontSizeSp: Float = 16f,
    /** ARGB，0xAARRGGBB 形式的 Long */
    val textColor: Long = 0xFFFFFFFF,
    val bgColor: Long = 0x00000000,
    val cornerRadiusDp: Float = 0f,
    val alpha: Float = 1f,
    /** 文字加粗 */
    val bold: Boolean = false,
    /** 0=左 1=中 2=右 */
    val textAlign: Int = 1,
    val borderColor: Long = 0x00000000,
    val borderWidthDp: Float = 0f,
    /** 锁定后不响应拖动，防止误碰 */
    val locked: Boolean = false,
    /** 运行时点击该元素要执行的宿主动作 */
    val clickAction: ElementAction = ElementAction.NONE,
    /** 动作参数：OPEN_URL 用网址，OPEN_APP 用包名 */
    val clickValue: String = "",
    /** 长按执行另一个动作，一个按钮可以有两套行为；NONE = 不启用长按 */
    val longClickAction: ElementAction = ElementAction.NONE,
    val longClickValue: String = "",
    /** 连点两下触发的动作；配置后单击会延迟约 0.3 秒以区分 */
    val doubleClickAction: ElementAction = ElementAction.NONE,
    val doubleClickValue: String = "",
    /** 连点三下触发的动作 */
    val tripleClickAction: ElementAction = ElementAction.NONE,
    val tripleClickValue: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.key)
        put("xDp", xDp.toDouble())
        put("yDp", yDp.toDouble())
        put("widthDp", widthDp.toDouble())
        put("heightDp", heightDp.toDouble())
        put("rotation", rotation.toDouble())
        put("text", text)
        put("assetPath", assetPath)
        put("fontSizeSp", fontSizeSp.toDouble())
        put("textColor", textColor)
        put("bgColor", bgColor)
        put("cornerRadiusDp", cornerRadiusDp.toDouble())
        put("alpha", alpha.toDouble())
        put("bold", bold)
        put("textAlign", textAlign)
        put("borderColor", borderColor)
        put("borderWidthDp", borderWidthDp.toDouble())
        put("locked", locked)
        put("clickAction", clickAction.key)
        put("clickValue", clickValue)
        put("longClickAction", longClickAction.key)
        put("longClickValue", longClickValue)
        put("doubleClickAction", doubleClickAction.key)
        put("doubleClickValue", doubleClickValue)
        put("tripleClickAction", tripleClickAction.key)
        put("tripleClickValue", tripleClickValue)
    }

    companion object {
        fun fromJson(o: JSONObject): CanvasElement = CanvasElement(
            id = o.optString("id", "el"),
            kind = CanvasElementKind.from(o.optString("kind", "")),
            xDp = o.optDouble("xDp", 0.0).toFloat(),
            yDp = o.optDouble("yDp", 0.0).toFloat(),
            widthDp = o.optDouble("widthDp", 200.0).toFloat(),
            heightDp = o.optDouble("heightDp", 40.0).toFloat(),
            rotation = o.optDouble("rotation", 0.0).toFloat(),
            text = o.optString("text", ""),
            assetPath = o.optString("assetPath", ""),
            fontSizeSp = o.optDouble("fontSizeSp", 16.0).toFloat(),
            textColor = o.optLong("textColor", 0xFFFFFFFF),
            bgColor = o.optLong("bgColor", 0L),
            cornerRadiusDp = o.optDouble("cornerRadiusDp", 0.0).toFloat(),
            alpha = o.optDouble("alpha", 1.0).toFloat(),
            bold = o.optBoolean("bold", false),
            textAlign = o.optInt("textAlign", 1),
            borderColor = o.optLong("borderColor", 0L),
            borderWidthDp = o.optDouble("borderWidthDp", 0.0).toFloat(),
            locked = o.optBoolean("locked", false),
            clickAction = ElementAction.from(o.optString("clickAction", "")),
            clickValue = o.optString("clickValue", ""),
            longClickAction = ElementAction.from(o.optString("longClickAction", "")),
            longClickValue = o.optString("longClickValue", ""),
            doubleClickAction = ElementAction.from(o.optString("doubleClickAction", "")),
            doubleClickValue = o.optString("doubleClickValue", ""),
            tripleClickAction = ElementAction.from(o.optString("tripleClickAction", "")),
            tripleClickValue = o.optString("tripleClickValue", "")
        )
    }
}

/** 虚拟画布：编辑器里的「设计稿」，也是运行时渲染的输入。 */
data class CanvasSpec(
    val widthDp: Float = 360f,
    val heightDp: Float = 640f,
    val background: Long = 0xFF111827,
    /** 非 0 时与 background 组成竖向渐变 */
    val backgroundEnd: Long = 0L,
    val elements: List<CanvasElement> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("widthDp", widthDp.toDouble())
        put("heightDp", heightDp.toDouble())
        put("background", background)
        put("backgroundEnd", backgroundEnd)
        put("elements", JSONArray().also { arr -> elements.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): CanvasSpec {
            val arr = o.optJSONArray("elements") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                runCatching { CanvasElement.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
            return CanvasSpec(
                widthDp = o.optDouble("widthDp", 360.0).toFloat(),
                heightDp = o.optDouble("heightDp", 640.0).toFloat(),
                background = o.optLong("background", 0xFF111827),
                backgroundEnd = o.optLong("backgroundEnd", 0L),
                elements = list
            )
        }
    }
}

data class HostExtension(
    val id: String,
    val name: String,
    val kind: ExtensionKind,
    /** 用户/AI 新建的扩展默认关闭，必须由用户显式启用；内置默认可默认开启。 */
    val enabled: Boolean = false,
    val builtin: Boolean = false,
    val trigger: ExtensionTrigger = ExtensionTrigger.COLD_START,
    /** 分享 / deep link / 通知点击等外部唤起时跳过展示，避免挡住用户要做的事。 */
    val skipOnExternalIntent: Boolean = true,
    val durationMs: Long = 1200L,
    val tapToDismiss: Boolean = true,
    /** 点击画布空白处执行什么；元素自己的 clickAction 为 NONE 时回落到它 */
    val action: ElementAction = ElementAction.NONE,
    val actionValue: String = "",
    val canvas: CanvasSpec = CanvasSpec(),
    // ── 悬浮窗运行时属性（启动画面忽略） ──
    val floatingStyle: FloatingStyle = FloatingStyle.CARD,
    val floatingWidthDp: Float = 240f,
    val floatingHeightDp: Float = 150f,
    val floatingAlpha: Float = 1f,
    val floatingRadiusDp: Float = 14f,
    /** true = 开启后直接展开画布，不用先点把手 */
    val floatingAutoExpand: Boolean = true,
    /** 默认 false：松手停在哪就在哪。开启才会在松手后贴到左右边缘。 */
    val floatingSnapToSide: Boolean = false,
    val floatingLockPosition: Boolean = false,
    val floatingAnchor: FloatingAnchor = FloatingAnchor.REMEMBER,
    /** 悬浮球/小图标上显示的文字，留空用默认符号 */
    val floatingBadge: String = "",
    /** 展开后自动收起，0 = 不自动收起 */
    val floatingAutoCollapseMs: Long = 0L,

    // ── 窗口装饰：默认全部关掉，标题、按钮、边框都不再强加给用户 ──
    /** 显示顶部标题栏；关掉后画布占满整个窗口 */
    val floatingShowHeader: Boolean = false,
    /** 标题文字，留空用扩展名称 */
    val floatingHeaderTitle: String = "",
    val floatingHeaderHeightDp: Float = 28f,
    val floatingHeaderBg: Long = 0xFFFFFFFF,
    val floatingHeaderTextColor: Long = 0xFF6B7280,
    /** 只在标题栏显示时生效 */
    val floatingShowCollapse: Boolean = false,
    val floatingShowClose: Boolean = false,
    /** 顶部极细拖拽条；关掉后热区还在但完全透明，仍然能拖 */
    val floatingShowDragBar: Boolean = true,
    /** 去掉白色外框与描边，窗口外观完全等于画布 */
    val floatingBareCanvas: Boolean = false,
    /** 折叠把手的配色，不再是硬编码的蓝色渐变球 */
    val floatingCollapsedBg: Long = 0xFF1E40AF,
    val floatingCollapsedTextColor: Long = 0xFFFFFFFF,
    val updatedAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.key)
        put("enabled", enabled)
        put("builtin", builtin)
        put("trigger", trigger.key)
        put("skipOnExternalIntent", skipOnExternalIntent)
        put("durationMs", durationMs)
        put("tapToDismiss", tapToDismiss)
        put("action", action.key)
        put("actionValue", actionValue)
        put("canvas", canvas.toJson())
        put("floatingStyle", floatingStyle.key)
        put("floatingWidthDp", floatingWidthDp.toDouble())
        put("floatingHeightDp", floatingHeightDp.toDouble())
        put("floatingAlpha", floatingAlpha.toDouble())
        put("floatingRadiusDp", floatingRadiusDp.toDouble())
        put("floatingAutoExpand", floatingAutoExpand)
        // 旧字段 floatingEdgeSnap 不再写入：v2 默认强制贴边，升级后一律回到自由放置。
        put("floatingSnapToSide", floatingSnapToSide)
        put("floatingLockPosition", floatingLockPosition)
        put("floatingAnchor", floatingAnchor.key)
        put("floatingBadge", floatingBadge)
        put("floatingAutoCollapseMs", floatingAutoCollapseMs)
        put("floatingShowHeader", floatingShowHeader)
        put("floatingHeaderTitle", floatingHeaderTitle)
        put("floatingHeaderHeightDp", floatingHeaderHeightDp.toDouble())
        put("floatingHeaderBg", floatingHeaderBg)
        put("floatingHeaderTextColor", floatingHeaderTextColor)
        put("floatingShowCollapse", floatingShowCollapse)
        put("floatingShowClose", floatingShowClose)
        put("floatingShowDragBar", floatingShowDragBar)
        put("floatingBareCanvas", floatingBareCanvas)
        put("floatingCollapsedBg", floatingCollapsedBg)
        put("floatingCollapsedTextColor", floatingCollapsedTextColor)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): HostExtension = HostExtension(
            id = o.optString("id", "ext"),
            name = o.optString("name", "未命名扩展"),
            kind = ExtensionKind.from(o.optString("kind", "")),
            enabled = o.optBoolean("enabled", false),
            builtin = o.optBoolean("builtin", false),
            trigger = ExtensionTrigger.from(o.optString("trigger", "")),
            skipOnExternalIntent = o.optBoolean("skipOnExternalIntent", true),
            durationMs = o.optLong("durationMs", 1200L),
            tapToDismiss = o.optBoolean("tapToDismiss", true),
            action = ElementAction.from(o.optString("action", "")),
            actionValue = o.optString("actionValue", ""),
            canvas = o.optJSONObject("canvas")?.let { CanvasSpec.fromJson(it) } ?: CanvasSpec(),
            floatingStyle = FloatingStyle.from(o.optString("floatingStyle", "")),
            floatingWidthDp = o.optDouble("floatingWidthDp", 240.0).toFloat(),
            floatingHeightDp = o.optDouble("floatingHeightDp", 150.0).toFloat(),
            floatingAlpha = o.optDouble("floatingAlpha", 1.0).toFloat().coerceIn(0.3f, 1f),
            floatingRadiusDp = o.optDouble("floatingRadiusDp", 14.0).toFloat(),
            floatingAutoExpand = o.optBoolean("floatingAutoExpand", true),
            floatingSnapToSide = o.optBoolean("floatingSnapToSide", false),
            floatingLockPosition = o.optBoolean("floatingLockPosition", false),
            floatingAnchor = FloatingAnchor.from(o.optString("floatingAnchor", "")),
            floatingBadge = o.optString("floatingBadge", ""),
            floatingAutoCollapseMs = o.optLong("floatingAutoCollapseMs", 0L),
            floatingShowHeader = o.optBoolean("floatingShowHeader", false),
            floatingHeaderTitle = o.optString("floatingHeaderTitle", ""),
            floatingHeaderHeightDp = o.optDouble("floatingHeaderHeightDp", 28.0).toFloat()
                .coerceIn(16f, 56f),
            floatingHeaderBg = o.optLong("floatingHeaderBg", 0xFFFFFFFF),
            floatingHeaderTextColor = o.optLong("floatingHeaderTextColor", 0xFF6B7280),
            floatingShowCollapse = o.optBoolean("floatingShowCollapse", false),
            floatingShowClose = o.optBoolean("floatingShowClose", false),
            floatingShowDragBar = o.optBoolean("floatingShowDragBar", true),
            floatingBareCanvas = o.optBoolean("floatingBareCanvas", false),
            floatingCollapsedBg = o.optLong("floatingCollapsedBg", 0xFF1E40AF),
            floatingCollapsedTextColor = o.optLong("floatingCollapsedTextColor", 0xFFFFFFFF),
            updatedAt = o.optLong("updatedAt", 0L)
        )
    }
}
