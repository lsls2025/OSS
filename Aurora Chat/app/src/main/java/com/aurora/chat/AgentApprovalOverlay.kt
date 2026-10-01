package com.aurora.chat

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AI 工具审批的系统悬浮卡片（2026-09-25 彻底重写版）。
 *
 * 与旧版的结构性区别（为根治「弹窗一大片空白、霸屏挡点击」）：
 * 1. 窗口宽度不再用 WRAP_CONTENT（依赖内容循环测量，在部分 ROM 上会拿到错误结果），
 *    而是直接算死像素：min(320dp, 屏宽 72%)，窗口尺寸=卡片尺寸，从物理上不可能铺满屏幕；
 * 2. 全部子 View 一律显式 LayoutParams，杜绝任何隐式 MATCH_PARENT 向上撑爆；
 * 3. 去掉 elevation 阴影（部分 ColorOS/深度定制 ROM 上阴影层会异常扩展绘制区域）；
 * 4. 描述文本上限 4 行 => 卡片总高度天然有界（约 190dp 以内），高度同样不可能霸屏；
 * 5. 关键路径打日志（adb logcat -s AgentApproval 可查），下次再出问题有据可查。
 *
 * 不走 Service（后台 startService 受系统限制），直接应用级 WindowManager addView/removeView；
 * 批准/拒绝经 AgentApprovalBridge.complete() 回传，与应用内弹窗、通知按钮共用同一
 * CompletableDeferred，哪边先批都幂等。应用回到前台时由会话页生命周期主动 dismiss()。
 */
object AgentApprovalOverlay {
    private const val TAG = "AgentApproval"
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var wm: WindowManager? = null
    @Volatile private var attached: View? = null

    /** 显示审批卡片（幂等：重复调用先移除旧卡再弹新卡） */
    fun show(context: Context, description: String) {
        val appCtx = context.applicationContext
        main.post { runCatching { showInternal(appCtx, description) } }
    }

    /** 撤下审批卡片（幂等：没有卡片时无副作用） */
    fun dismiss() {
        main.post { runCatching { removeInternal() } }
    }

    private fun removeInternal() {
        attached?.let { v ->
            runCatching { wm?.removeView(v) }
            attached = null
            wm = null
        }
    }

    private fun showInternal(appCtx: Context, description: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appCtx)) {
            Log.w(TAG, "show skip: no overlay permission")
            return
        }
        removeInternal()

        val dm = appCtx.resources.displayMetrics
        val density = dm.density
        fun dp(v: Int) = (v * density).toInt().coerceAtLeast(1)

        // 描述兜底:调用方漏传/为空时给出可读说明,绝不出空卡
        val desc = description.ifBlank { "AI 请求执行一个需要你批准的操作" }

        // 卡片宽度:算死像素,不用 WRAP_CONTENT(320dp 与 屏宽72% 取小,小屏也放得下)
        val cardWidth = minOf(dp(320), (dm.widthPixels * 0.72f).toInt().coerceAtLeast(dp(220)))
        Log.d(TAG, "show card width=${cardWidth}px screen=${dm.widthPixels}x${dm.heightPixels}")

        // ── 根容器:透明 FrameLayout,尺寸=卡片 ──
        val root = FrameLayout(appCtx)

        // ── 卡片:白底圆角,全部显式 LayoutParams ──
        val card = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }

        // 标题
        card.addView(TextView(appCtx).apply {
            text = "AI 请求审批"
            setTextColor(Color.parseColor("#1F2937"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 描述(上限 4 行 => 卡片高度有界)
        card.addView(TextView(appCtx).apply {
            text = desc
            setTextColor(Color.parseColor("#4B5563"))
            textSize = 13f
            lineHeight = dp(19)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        // 按钮行:拒绝(描边红) + 批准(实心品牌蓝)
        val buttons = LinearLayout(appCtx).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, filled: Boolean, onClick: () -> Unit): TextView =
            TextView(appCtx).apply {
                text = label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                if (filled) {
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E40AF"))
                        cornerRadius = dp(10).toFloat()
                    }
                } else {
                    setTextColor(Color.parseColor("#DC2626"))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.parseColor("#DC2626"))
                    }
                }
                setOnClickListener {
                    Log.d(TAG, "clicked: $label")
                    onClick()
                }
            }
        buttons.addView(button("拒绝", filled = false) {
            AgentApprovalBridge.complete(false)
            runCatching { NotificationHelper.cancelToolApprovalNotification(appCtx) }
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
        buttons.addView(button("批准", filled = true) {
            AgentApprovalBridge.complete(true)
            runCatching { NotificationHelper.cancelToolApprovalNotification(appCtx) }
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        card.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        root.addView(card, FrameLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            cardWidth,                        // 宽:算死像素,永不铺满
            WindowManager.LayoutParams.WRAP_CONTENT, // 高:内容有界(标题+4行+按钮≈190dp)
            type,
            // NOT_FOCUSABLE:不抢底层焦点;NOT_TOUCH_MODAL:窗口矩形外触摸全部穿透到底层应用
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
        }

        // 拖动:按住卡片任意非按钮区域(标题/描述/留白)即可拖走;触摸监听只观察不消费(始终返回
        // false),按钮自身的点击事件完全不受影响。超过 8px 视为拖动,轻微抖动不算。
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        root.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (dragging || kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        dragging = true
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { wm?.updateViewLayout(v, params) }
                    }
                }
            }
            false // 不消费:子 View(批准/拒绝按钮)的点击照常工作
        }

        wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm?.addView(root, params)
        attached = root
        Log.d(TAG, "card added to wm")
    }
}
