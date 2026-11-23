package com.aurora.chat.ui.custom

/** 编辑器里一键套用的画布模板；只在用户主动点击时应用，不作为默认内容。 */
data class CanvasTemplate(
    val name: String,
    val widthDp: Float,
    val heightDp: Float,
    val background: Long,
    val backgroundEnd: Long = 0L,
    val elements: List<CanvasElement>
)

object HostExtensionTemplates {

    /** 画布尺寸预设：整页 / 方形 / 横条（悬浮窗常用） */
    val sizes = listOf(
        Triple("整页 360×640", 360f, 640f),
        Triple("方形 320×320", 320f, 320f),
        Triple("横条 360×160", 360f, 160f),
        Triple("小卡 240×240", 240f, 240f)
    )

    // 配色基调：全部浅色底（白 / 浅灰 / 浅色渐变），文字用深灰，点缀品牌蓝。
    private val TEXT_DARK = 0xFF1F2937L
    private val TEXT_SUB = 0xFF64748BL
    private val TEXT_BODY = 0xFF475569L
    private val GRAY_BTN = 0xFFE2E8F0L
    private val BLUE = 0xFF1E40AF
    private val BG_PAGE = 0xFFF5F6F7

    /** 按扩展类型给模板：启动画面与悬浮窗场景不同，各有一套。 */
    fun forKind(kind: ExtensionKind): List<CanvasTemplate> =
        if (kind == ExtensionKind.LAUNCH_SCREEN) launch else floating

    // ─────────────────── 悬浮窗模板（横条 / 小卡为主） ───────────────────

    val floating = listOf(
        CanvasTemplate(
            name = "空白",
            widthDp = 360f, heightDp = 160f,
            background = 0xFFFFFFFF,
            elements = emptyList()
        ),
        CanvasTemplate(
            name = "简洁卡片",
            widthDp = 360f, heightDp = 640f,
            background = BG_PAGE,
            elements = listOf(
                CanvasElement(
                    id = "tpl_card", kind = CanvasElementKind.SHAPE,
                    xDp = 24f, yDp = 180f, widthDp = 312f, heightDp = 280f,
                    bgColor = 0xFFFFFFFF, cornerRadiusDp = 20f,
                    borderColor = 0xFFE5E7EB, borderWidthDp = 1f
                ),
                CanvasElement(
                    id = "tpl_h", kind = CanvasElementKind.TEXT,
                    xDp = 48f, yDp = 220f, widthDp = 264f, heightDp = 40f,
                    text = "标题", fontSizeSp = 22f, bold = true,
                    textColor = TEXT_DARK
                ),
                CanvasElement(
                    id = "tpl_p", kind = CanvasElementKind.TEXT,
                    xDp = 48f, yDp = 272f, widthDp = 264f, heightDp = 80f,
                    text = "在这里写点什么", fontSizeSp = 14f,
                    textColor = TEXT_SUB
                ),
                CanvasElement(
                    id = "tpl_btn", kind = CanvasElementKind.BUTTON,
                    xDp = 48f, yDp = 380f, widthDp = 120f, heightDp = 44f,
                    text = "开始", fontSizeSp = 15f, textColor = 0xFFFFFFFF,
                    bgColor = BLUE, cornerRadiusDp = 10f
                )
            )
        ),
        CanvasTemplate(
            name = "悬浮条",
            widthDp = 360f, heightDp = 160f,
            background = 0xFFFFFFFF,
            elements = listOf(
                CanvasElement(
                    id = "tpl_bar_icon", kind = CanvasElementKind.ICON,
                    xDp = 20f, yDp = 52f, widthDp = 56f, heightDp = 56f,
                    cornerRadiusDp = 14f
                ),
                CanvasElement(
                    id = "tpl_bar_title", kind = CanvasElementKind.TEXT,
                    xDp = 92f, yDp = 48f, widthDp = 180f, heightDp = 32f,
                    text = "快捷面板", fontSizeSp = 18f, bold = true,
                    textColor = TEXT_DARK, textAlign = 0
                ),
                CanvasElement(
                    id = "tpl_bar_sub", kind = CanvasElementKind.TEXT,
                    xDp = 92f, yDp = 82f, widthDp = 180f, heightDp = 26f,
                    text = "点一下即可执行", fontSizeSp = 12f,
                    textColor = TEXT_SUB, textAlign = 0
                ),
                CanvasElement(
                    id = "tpl_bar_btn", kind = CanvasElementKind.BUTTON,
                    xDp = 276f, yDp = 60f, widthDp = 64f, heightDp = 40f,
                    text = "GO", fontSizeSp = 13f, textColor = 0xFFFFFFFF,
                    bgColor = BLUE, cornerRadiusDp = 10f
                )
            )
        ),
        CanvasTemplate(
            name = "时钟卡片",
            widthDp = 320f, heightDp = 320f,
            background = 0xFFFFFFFF,
            elements = listOf(
                CanvasElement(
                    id = "ck_time", kind = CanvasElementKind.TEXT,
                    xDp = 20f, yDp = 48f, widthDp = 280f, heightDp = 90f,
                    text = "{time}", fontSizeSp = 54f, bold = true,
                    textColor = TEXT_DARK
                ),
                CanvasElement(
                    id = "ck_date", kind = CanvasElementKind.TEXT,
                    xDp = 20f, yDp = 148f, widthDp = 280f, heightDp = 34f,
                    text = "{date} {week}", fontSizeSp = 16f,
                    textColor = TEXT_SUB
                ),
                line("ck_line", 20f, 196f, 280f),
                CanvasElement(
                    id = "ck_bat", kind = CanvasElementKind.TEXT,
                    xDp = 20f, yDp = 216f, widthDp = 280f, heightDp = 30f,
                    text = "电量 {battery}  {charging}", fontSizeSp = 14f,
                    textColor = TEXT_BODY, textAlign = 0
                ),
                CanvasElement(
                    id = "ck_net", kind = CanvasElementKind.TEXT,
                    xDp = 20f, yDp = 250f, widthDp = 280f, heightDp = 30f,
                    text = "{net}  网速 {speed}", fontSizeSp = 14f,
                    textColor = TEXT_BODY, textAlign = 0
                )
            )
        ),
        CanvasTemplate(
            name = "网速浮条",
            widthDp = 360f, heightDp = 100f,
            background = 0xFFFFFFFF,
            elements = listOf(
                CanvasElement(
                    id = "ns_speed", kind = CanvasElementKind.TEXT,
                    xDp = 20f, yDp = 14f, widthDp = 150f, heightDp = 44f,
                    text = "{speed}", fontSizeSp = 22f, bold = true,
                    textColor = BLUE, textAlign = 0
                ),
                CanvasElement(
                    id = "ns_time", kind = CanvasElementKind.TEXT,
                    xDp = 190f, yDp = 14f, widthDp = 150f, heightDp = 44f,
                    text = "{time}", fontSizeSp = 22f, bold = true,
                    textColor = TEXT_DARK, textAlign = 2
                ),
                CanvasElement(
                    id = "ns_net", kind = CanvasElementKind.TEXT,
                    xDp = 20f, yDp = 60f, widthDp = 320f, heightDp = 26f,
                    text = "{net}  电量 {battery}  内存 {mem}", fontSizeSp = 12f,
                    textColor = TEXT_SUB, textAlign = 0
                )
            )
        ),
        CanvasTemplate(
            name = "秒表",
            widthDp = 300f, heightDp = 240f,
            background = 0xFFFFFFFF,
            elements = listOf(
                CanvasElement(
                    id = "sw_text", kind = CanvasElementKind.TEXT,
                    xDp = 20f, yDp = 40f, widthDp = 260f, heightDp = 80f,
                    text = "{timer}", fontSizeSp = 48f, bold = true,
                    textColor = TEXT_DARK
                ),
                btn("sw_toggle", 20f, 150f, 125f, 52f, "开始暂停", ElementAction.TIMER_TOGGLE, BLUE),
                btn("sw_reset", 155f, 150f, 125f, 52f, "归零", ElementAction.TIMER_RESET, GRAY_BTN)
                    .copy(textColor = TEXT_DARK)
            )
        ),
        CanvasTemplate(
            name = "一键联系人",
            widthDp = 360f, heightDp = 200f,
            background = BG_PAGE,
            elements = listOf(
                CanvasElement(
                    id = "ct_t", kind = CanvasElementKind.TEXT,
                    xDp = 20f, yDp = 18f, widthDp = 320f, heightDp = 30f,
                    text = "常用联系人", fontSizeSp = 16f, bold = true,
                    textColor = TEXT_DARK, textAlign = 0
                ),
                btn("ct_call", 20f, 66f, 155f, 56f, "打电话回家", ElementAction.DIAL, BLUE)
                    .copy(clickValue = "10086"),
                btn("ct_sms", 185f, 66f, 155f, 56f, "发条短信", ElementAction.SMS, GRAY_BTN)
                    .copy(textColor = TEXT_DARK, clickValue = "10086"),
                btn("ct_alarm", 20f, 134f, 155f, 48f, "定个闹钟", ElementAction.SET_ALARM, GRAY_BTN)
                    .copy(textColor = TEXT_DARK, clickValue = "07:30"),
                btn("ct_timer", 185f, 134f, 155f, 48f, "计时 5 分钟", ElementAction.SET_TIMER, BLUE)
                    .copy(clickValue = "300")
            )
        ),
        CanvasTemplate(
            name = "晚安一键",
            widthDp = 360f, heightDp = 160f,
            background = BG_PAGE,
            elements = listOf(
                CanvasElement(
                    id = "nt_t", kind = CanvasElementKind.TEXT,
                    xDp = 20f, yDp = 12f, widthDp = 320f, heightDp = 26f,
                    text = "睡前把手机调安静", fontSizeSp = 14f,
                    textColor = TEXT_SUB, textAlign = 0
                ),
                btn("nt_dnd", 20f, 46f, 155f, 46f, "勿扰开关", ElementAction.DND_TOGGLE, BLUE),
                btn("nt_mute", 185f, 46f, 155f, 46f, "静音切换", ElementAction.VOLUME_MUTE, BLUE),
                btn("nt_dim", 20f, 102f, 155f, 46f, "屏幕调暗", ElementAction.SCREEN_BRIGHT, GRAY_BTN)
                    .copy(textColor = TEXT_DARK, clickValue = "30"),
                btn("nt_off", 185f, 102f, 155f, 46f, "关闭浮窗", ElementAction.CLOSE, GRAY_BTN)
                    .copy(textColor = TEXT_DARK)
            )
        ),
        CanvasTemplate(
            name = "系统监控",
            widthDp = 320f, heightDp = 320f,
            background = 0xFFFFFFFF,
            elements = listOf(
                label("m_time", 20f, 20f, 280f, 48f, "{time}", 30f, TEXT_DARK, bold = true),
                label("m_bat", 20f, 76f, 280f, 26f, "电量 {battery}%  {charging}", 13f, TEXT_BODY),
                label("m_net", 20f, 106f, 280f, 26f, "网速 {speed}", 13f, TEXT_BODY),
                label("m_wifi", 20f, 136f, 280f, 26f, "网络 {net}  {wifi}", 13f, TEXT_BODY),
                label("m_vol", 20f, 172f, 280f, 26f, "音量 {volume}  亮度 {brightness}", 13f, TEXT_BODY),
                label("m_mem", 20f, 202f, 280f, 26f, "内存 {mem}", 13f, TEXT_BODY),
                label("m_st", 20f, 232f, 280f, 26f, "存储 {storage}  光感 {light}", 13f, TEXT_BODY),
                btn("m_refresh", 230f, 272f, 70f, 34f, "刷新", ElementAction.REFRESH, BLUE)
            )
        ),
        CanvasTemplate(
            name = "媒体控制",
            widthDp = 360f, heightDp = 160f,
            background = 0xFFFFFFFF,
            elements = listOf(
                label("md_t", 20f, 12f, 320f, 30f, "{time}   音量 {volume}", 16f, TEXT_DARK),
                btn("md_prev", 20f, 52f, 100f, 40f, "上一首", ElementAction.MEDIA_PREV, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("md_play", 130f, 52f, 100f, 40f, "播放", ElementAction.MEDIA_PLAY, BLUE),
                btn("md_next", 240f, 52f, 100f, 40f, "下一首", ElementAction.MEDIA_NEXT, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("md_down", 20f, 104f, 100f, 40f, "音量减", ElementAction.VOLUME_DOWN, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("md_mute", 130f, 104f, 100f, 40f, "静音", ElementAction.VOLUME_MUTE, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("md_up", 240f, 104f, 100f, 40f, "音量加", ElementAction.VOLUME_UP, BLUE)
            )
        ),
        CanvasTemplate(
            name = "硬件快捷",
            widthDp = 360f, heightDp = 160f,
            background = 0xFFFFFFFF,
            elements = listOf(
                label("hw_t", 20f, 12f, 320f, 26f, "手电 {torch}  常亮 {keepon}  勿扰 {dnd}", 12f, TEXT_SUB),
                btn("hw_torch", 20f, 46f, 100f, 40f, "手电筒", ElementAction.FLASHLIGHT, BLUE)
                    .copy(longClickAction = ElementAction.TORCH_SOS),
                btn("hw_sos", 130f, 46f, 100f, 40f, "爆闪", ElementAction.TORCH_SOS, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("hw_vib", 240f, 46f, 100f, 40f, "振动", ElementAction.VIBRATE, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("hw_keep", 20f, 100f, 100f, 40f, "屏幕常亮", ElementAction.KEEP_SCREEN_ON, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("hw_through", 130f, 100f, 100f, 40f, "穿透", ElementAction.TOUCH_THROUGH, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("hw_hide", 240f, 100f, 100f, 40f, "收起", ElementAction.COLLAPSE, GRAY_BTN)
                    .copy(textColor = TEXT_DARK)
            )
        ),
        CanvasTemplate(
            name = "权限直达",
            widthDp = 360f, heightDp = 160f,
            background = 0xFFFFFFFF,
            elements = listOf(
                label("pm_t", 20f, 12f, 320f, 26f, "旋转 {rotation}  网络 {net}  亮度 {brightness}", 12f, TEXT_SUB),
                btn("pm_wifi", 20f, 46f, 100f, 40f, "WLAN", ElementAction.WIFI_PANEL, BLUE),
                btn("pm_bt", 130f, 46f, 100f, 40f, "蓝牙", ElementAction.BT_PANEL, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("pm_nfc", 240f, 46f, 100f, 40f, "NFC", ElementAction.NFC_PANEL, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("pm_loc", 20f, 100f, 100f, 40f, "定位", ElementAction.LOCATION_PANEL, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("pm_dnd", 130f, 100f, 100f, 40f, "勿扰", ElementAction.DND_TOGGLE, GRAY_BTN)
                    .copy(textColor = TEXT_DARK),
                btn("pm_write", 240f, 100f, 100f, 40f, "修改设置", ElementAction.PERM_WRITE_SETTINGS, GRAY_BTN)
                    .copy(textColor = TEXT_DARK)
            )
        )
    )

    // ─────────────────── 启动画面模板（整页为主） ───────────────────

    val launch = listOf(
        CanvasTemplate(
            name = "空白开屏",
            widthDp = 360f, heightDp = 640f,
            background = 0xFFFFFFFF,
            elements = emptyList()
        ),
        CanvasTemplate(
            name = "品牌渐变",
            widthDp = 360f, heightDp = 640f,
            background = 0xFF1E40AF, backgroundEnd = 0xFF6D28D9,
            elements = listOf(
                CanvasElement(
                    id = "lc_logo", kind = CanvasElementKind.IMAGE,
                    xDp = 110f, yDp = 190f, widthDp = 140f, heightDp = 140f,
                    cornerRadiusDp = 30f
                ),
                CanvasElement(
                    id = "lc_title", kind = CanvasElementKind.TEXT,
                    xDp = 30f, yDp = 360f, widthDp = 300f, heightDp = 48f,
                    text = "Aurora Chat", fontSizeSp = 28f, bold = true,
                    textColor = 0xFFFFFFFF
                ),
                CanvasElement(
                    id = "lc_sub", kind = CanvasElementKind.TEXT,
                    xDp = 30f, yDp = 414f, widthDp = 300f, heightDp = 30f,
                    text = "聊有回响，办有着落", fontSizeSp = 15f,
                    textColor = 0xFFDBEAFE
                ),
                CanvasElement(
                    id = "lc_line", kind = CanvasElementKind.SHAPE,
                    xDp = 130f, yDp = 470f, widthDp = 100f, heightDp = 3f,
                    bgColor = 0x80FFFFFFL, cornerRadiusDp = 2f
                )
            )
        ),
        CanvasTemplate(
            name = "时钟开屏",
            widthDp = 360f, heightDp = 640f,
            background = 0xFFFFFFFF,
            elements = listOf(
                CanvasElement(
                    id = "lc_time", kind = CanvasElementKind.TEXT,
                    xDp = 30f, yDp = 170f, widthDp = 300f, heightDp = 110f,
                    text = "{time}", fontSizeSp = 64f, bold = true,
                    textColor = TEXT_DARK
                ),
                CanvasElement(
                    id = "lc_date", kind = CanvasElementKind.TEXT,
                    xDp = 30f, yDp = 292f, widthDp = 300f, heightDp = 36f,
                    text = "{date} {week}", fontSizeSp = 17f,
                    textColor = TEXT_SUB
                ),
                CanvasElement(
                    id = "lc_line", kind = CanvasElementKind.SHAPE,
                    xDp = 30f, yDp = 356f, widthDp = 80f, heightDp = 3f,
                    bgColor = BLUE, cornerRadiusDp = 2f
                ),
                CanvasElement(
                    id = "lc_app", kind = CanvasElementKind.TEXT,
                    xDp = 30f, yDp = 560f, widthDp = 300f, heightDp = 30f,
                    text = "Aurora Chat", fontSizeSp = 14f,
                    textColor = TEXT_SUB, textAlign = 2
                )
            )
        ),
        CanvasTemplate(
            name = "极简开屏",
            widthDp = 360f, heightDp = 640f,
            background = BG_PAGE,
            elements = listOf(
                CanvasElement(
                    id = "lc_icon", kind = CanvasElementKind.ICON,
                    xDp = 130f, yDp = 220f, widthDp = 100f, heightDp = 100f,
                    cornerRadiusDp = 24f
                ),
                CanvasElement(
                    id = "lc_name", kind = CanvasElementKind.TEXT,
                    xDp = 30f, yDp = 344f, widthDp = 300f, heightDp = 44f,
                    text = "Aurora Chat", fontSizeSp = 24f, bold = true,
                    textColor = TEXT_DARK, textAlign = 1
                ),
                CanvasElement(
                    id = "lc_slogan", kind = CanvasElementKind.TEXT,
                    xDp = 30f, yDp = 396f, widthDp = 300f, heightDp = 30f,
                    text = "聊有回响，办有着落", fontSizeSp = 14f,
                    textColor = TEXT_SUB, textAlign = 1
                )
            )
        ),
        CanvasTemplate(
            name = "状态开屏",
            widthDp = 360f, heightDp = 640f,
            background = 0xFFFFFFFF,
            elements = listOf(
                CanvasElement(
                    id = "ls_icon", kind = CanvasElementKind.ICON,
                    xDp = 140f, yDp = 150f, widthDp = 80f, heightDp = 80f,
                    cornerRadiusDp = 20f
                ),
                CanvasElement(
                    id = "ls_time", kind = CanvasElementKind.TEXT,
                    xDp = 30f, yDp = 280f, widthDp = 300f, heightDp = 96f,
                    text = "{time}", fontSizeSp = 56f, bold = true,
                    textColor = TEXT_DARK, textAlign = 1
                ),
                CanvasElement(
                    id = "ls_date", kind = CanvasElementKind.TEXT,
                    xDp = 30f, yDp = 386f, widthDp = 300f, heightDp = 32f,
                    text = "{date} {week}", fontSizeSp = 15f,
                    textColor = TEXT_SUB, textAlign = 1
                ),
                CanvasElement(
                    id = "ls_info", kind = CanvasElementKind.TEXT,
                    xDp = 30f, yDp = 552f, widthDp = 300f, heightDp = 28f,
                    text = "电量 {battery}  网速 {speed}", fontSizeSp = 13f,
                    textColor = TEXT_BODY, textAlign = 1
                ),
                CanvasElement(
                    id = "ls_net", kind = CanvasElementKind.TEXT,
                    xDp = 30f, yDp = 582f, widthDp = 300f, heightDp = 28f,
                    text = "{net}  {charging}", fontSizeSp = 13f,
                    textColor = TEXT_SUB, textAlign = 1
                )
            )
        ),
        CanvasTemplate(
            name = "欢迎页",
            widthDp = 360f, heightDp = 640f,
            background = 0xFFFFFFFF,
            elements = listOf(
                CanvasElement(
                    id = "lw_icon", kind = CanvasElementKind.ICON,
                    xDp = 120f, yDp = 140f, widthDp = 120f, heightDp = 120f,
                    cornerRadiusDp = 28f
                ),
                CanvasElement(
                    id = "lw_title", kind = CanvasElementKind.TEXT,
                    xDp = 40f, yDp = 300f, widthDp = 280f, heightDp = 44f,
                    text = "欢迎回来", fontSizeSp = 26f, bold = true,
                    textColor = TEXT_DARK, textAlign = 1
                ),
                CanvasElement(
                    id = "lw_body", kind = CanvasElementKind.TEXT,
                    xDp = 40f, yDp = 356f, widthDp = 280f, heightDp = 60f,
                    text = "今天也从一条消息开始", fontSizeSp = 15f,
                    textColor = TEXT_SUB, textAlign = 1
                ),
                CanvasElement(
                    id = "lw_date", kind = CanvasElementKind.TEXT,
                    xDp = 40f, yDp = 560f, widthDp = 280f, heightDp = 28f,
                    text = "{date} {week}", fontSizeSp = 13f,
                    textColor = TEXT_SUB, textAlign = 1
                )
            )
        )
    )

    private fun label(
        id: String, x: Float, y: Float, w: Float, h: Float,
        text: String, size: Float, color: Long,
        bold: Boolean = false, align: Int = 0
    ) = CanvasElement(
        id = id, kind = CanvasElementKind.TEXT,
        xDp = x, yDp = y, widthDp = w, heightDp = h,
        text = text, fontSizeSp = size, bold = bold,
        textColor = color, textAlign = align
    )

    private fun btn(
        id: String, x: Float, y: Float, w: Float, h: Float,
        text: String, action: ElementAction, bg: Long = BLUE
    ) = CanvasElement(
        id = id, kind = CanvasElementKind.BUTTON,
        xDp = x, yDp = y, widthDp = w, heightDp = h,
        text = text, fontSizeSp = 13f, bold = true, textColor = 0xFFFFFFFF,
        bgColor = bg, cornerRadiusDp = 10f, clickAction = action
    )

    private fun line(id: String, x: Float, y: Float, w: Float) = CanvasElement(
        id = id, kind = CanvasElementKind.SHAPE,
        xDp = x, yDp = y, widthDp = w, heightDp = 1f,
        bgColor = 0xFFE2E8F0L, cornerRadiusDp = 1f
    )

    /** 套模板时顺手把悬浮窗尺寸调成同比例，横条模板不会再被塞进一张方卡里。 */
    fun apply(ext: HostExtension, t: CanvasTemplate): HostExtension {
        val base = ext.copy(
            canvas = CanvasSpec(
                widthDp = t.widthDp,
                heightDp = t.heightDp,
                background = t.background,
                backgroundEnd = t.backgroundEnd,
                elements = t.elements
            )
        )
        if (ext.kind != ExtensionKind.FLOATING_WINDOW) return base
        val ratio = (t.heightDp / t.widthDp.coerceAtLeast(1f)).coerceIn(0.25f, 1.8f)
        return base.copy(floatingWidthDp = 240f, floatingHeightDp = 240f * ratio)
    }
}
