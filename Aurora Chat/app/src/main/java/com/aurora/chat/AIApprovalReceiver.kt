package com.aurora.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred

/**
 * AI 工具审批通知的点击回传接收器。
 *
 * 背景：手动审批模式下，审批弹窗是应用内 Compose 弹窗——AI 退到后台干活（如无障碍操控其它应用）
 * 时用户根本看不到弹窗，任务会一直挂起。应用不在前台时改发一条带「批准/拒绝」按钮的系统通知，
 * 用户在通知栏即可完成审批，不必回到应用内。本接收器负责接收通知按钮点击并回传审批结果。
 */
class AIApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val approve = intent.action == ACTION_APPROVE
        // 先撤掉通知与悬浮审批卡片再回传结果，无论批准与否这条通知都已完成使命
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(NotificationHelper.APPROVAL_NOTIFICATION_ID)
        }
        runCatching { AIApprovalOverlay.dismiss() }
        // 回传结果给挂起的审批协程；若协程已被应用内弹窗先行完成，complete() 幂等返回 false，无副作用
        AIApprovalBridge.complete(approve)
    }

    companion object {
        const val ACTION_APPROVE = "com.aurora.chat.TOOL_APPROVAL_APPROVE"
        const val ACTION_DENY = "com.aurora.chat.TOOL_APPROVAL_DENY"
    }
}

/**
 * 审批请求的进程级桥：聊天界面的审批挂起点在这里登记，通知按钮点击在这里回传。
 * 同一时刻最多只有一个待审批请求（工具循环串行执行），因此单槽位即可。
 */
object AIApprovalBridge {
    private val idCounter = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var activeDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var activeId: Long = -1L

    // 待审批请求的工具名与参数 JSON：供会话页离开重进后重建审批条使用
    @Volatile
    private var activeTool: String = ""

    @Volatile
    private var activeArgs: String = ""

    /** 登记一个新的待审批请求，返回其唯一 ID（通知的 PendingIntent 用它区分） */
    fun begin(deferred: CompletableDeferred<Boolean>, tool: String = "", args: String = ""): Long {
        val id = idCounter.incrementAndGet()
        activeId = id
        activeDeferred = deferred
        activeTool = tool
        activeArgs = args
        return id
    }

    /** 是否有尚未完成（isActive）的待审批请求 */
    fun hasActive(): Boolean = activeDeferred?.isActive == true

    /** 当前活跃请求的 ID；无活跃请求返回 null（用于「退后台补发通知」场景） */
    fun activeRequestId(): Long? = if (hasActive()) activeId else null

    /** 当前活跃请求的 deferred 引用；无活跃请求返回 null（会话页重进恢复审批条用） */
    fun activeDeferred(): CompletableDeferred<Boolean>? = activeDeferred

    /** 当前活跃请求的工具描述 (tool, argsJson)；无活跃请求返回 null（会话页重进恢复审批条用） */
    fun pendingDescription(): Pair<String, String>? =
        if (hasActive() && activeTool.isNotEmpty()) activeTool to activeArgs else null

    /** 回传审批结果；返回是否确实回传给了活跃请求（幂等：已完成的请求返回 false） */
    fun complete(approved: Boolean): Boolean =
        activeDeferred?.complete(approved) ?: false

    /** 清理登记（请求结束后由挂起点调用，防止陈旧引用） */
    fun clear(deferred: CompletableDeferred<Boolean>) {
        if (activeDeferred === deferred) {
            activeDeferred = null
            activeId = -1L
            activeTool = ""
            activeArgs = ""
        }
    }
}

/**
 * 进程级前后台标记（仅供审批通知决策使用）。
 * 由聊天界面的生命周期观察者维护：ON_RESUME 置 true，ON_PAUSE 置 false。
 */
object AppForegroundTracker {
    @Volatile
    var isForeground: Boolean = true
}

/**
 * 对话页可见性标记：由聊天界面组合期维护（进入置 true、组合销毁置 false）。
 * 供审批挂起点决策「是否必须走通知/悬浮卡」：用户离开对话页（不在对话内）时，
 * 应用内审批弹窗不可见，必须用通知/悬浮卡持续提示，绝不能让审批静默挂起或默认放行。
 */
object ConversationPageTracker {
    @Volatile
    var active: Boolean = true
}
