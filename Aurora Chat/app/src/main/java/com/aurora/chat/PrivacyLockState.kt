package com.aurora.chat

import android.content.Context
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 「禁止退出」互锁状态 —— 全进程唯一真源。
 *
 * 规则（产品定义）：
 *   - 双方都开启「禁止退出」→ 才真正锁住（locked = 我方 && 对方）
 *   - 任一方关闭 → 立即放行
 *   - **默认关闭**：不落盘、不继承。进程冷启动时复位为「关闭」；
 *     热启动（进程还活着、只是从后台回到前台）保持原状态不变。
 *
 * 只用 @Volatile 原始字段保存，不依赖 Compose / Flow。原因是判定发生在
 * 「返回键回调」和「无障碍服务回调」里，需要任意线程同步读取，不能等重组。
 */
object PrivacyLockState {

    /** 当前互锁对应的好友（正在和谁互锁）；0 表示无 */
    @Volatile
    var friendId: Long = 0L
        private set

    /** 我方是否开启「禁止退出」 */
    @Volatile
    var myNoExit: Boolean = false
        private set

    /** 对方是否开启「禁止退出」 */
    @Volatile
    var friendNoExit: Boolean = false
        private set

    /**
     * 本次进程内是否「被用户明确启用过」。
     *
     * 冷启动后为 false：即使服务端两边都还开着，也不会自动把用户重新锁住
     * （这就是「默认关闭、冷打开自动关闭、热打开不变」的落点）。
     * 只有用户在隐私面板里看到/拨动过开关，才会重新武装。
     */
    @Volatile
    var armed: Boolean = false
        private set

    /** 真正是否锁住 */
    @Volatile
    var locked: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appCtx: Context? = null

    /**
     * 进程冷启动时调用（Application.onCreate）：记录 Context 并**复位为关闭**。
     * 不继承任何上次的锁定状态，即「这个功能默认关闭」。
     */
    fun onProcessStart(ctx: Context) {
        appCtx = ctx.applicationContext
        friendId = 0L
        myNoExit = false
        friendNoExit = false
        armed = false
        locked = false
    }

    /**
     * 我方开关变化（会话内隐私面板 / 悬浮球面板拨动时）。
     *
     * 注意：换互锁对象时会把「对方」那一侧清掉，所以调用方若知道对方的当前值，
     * 应优先用 [setPair]，否则这里会补一次网络核对把对方的值捞回来
     * （否则会出现「我方刚开启、对方其实也开着，却永远锁不上」）。
     */
    fun setMine(fid: Long, value: Boolean) {
        if (fid <= 0L) return
        val switched = friendId != fid
        if (switched) {
            friendId = fid
            friendNoExit = false
        }
        myNoExit = value
        if (value) armed = true
        recompute()
        // 对方那一侧未知（或刚换对象）时补一次核对
        if (value && !friendNoExit) refresh(fid)
    }

    /** 一次性写入双方状态（面板里两个值都已拿到时用，可避免多一次网络往返） */
    fun setPair(fid: Long, mine: Boolean, theirs: Boolean) {
        if (fid <= 0L) return
        friendId = fid
        myNoExit = mine
        friendNoExit = theirs
        if (mine) armed = true
        recompute()
    }

    /** 清空并解除（退出登录 / 账号注销 / 双方同意取消锁定） */
    fun clear() {
        friendId = 0L
        myNoExit = false
        friendNoExit = false
        armed = false
        recompute()
    }

    /**
     * 从服务端核对「我方 + 对方」的禁止退出开关并更新状态。
     *
     * 拉取失败时**保持原状**（fail-closed）：网络抖动不会导致误放行。
     * 注意本方法**不会**把 armed 打开——冷启动后即使用户不操作、
     * 对方仍开着，也不会自动把用户锁住。
     */
    fun refresh(fid: Long = friendId) {
        if (fid <= 0L) return
        scope.launch {
            try {
                // 两个接口并发：串行会白白多花一个网络往返，直接拖慢「对方一开我就锁上」
                val mineTask = async { AuroraApi.getPrivacySettings(fid) }
                val theirsTask = async { AuroraApi.getFriendPrivacySettings(fid) }
                val mine = mineTask.await()
                val theirs = theirsTask.await()
                if (!mine.success || !theirs.success) return@launch
                val m = mine.data ?: return@launch
                val t = theirs.data ?: return@launch
                // 期间可能已经切换了互锁对象，丢弃过期结果
                if (friendId != fid) return@launch
                myNoExit = m.optBoolean("no_exit", false)
                friendNoExit = t.optBoolean("no_exit", false)
                recompute()
            } catch (_: Throwable) {
                // 保持原状
            }
        }
    }

    private fun recompute() {
        val was = locked
        locked = armed && myNoExit && friendNoExit
        // 锁定状态一变就给出即时反馈：双方开启的那一刻立刻能看到"已生效"，
        // 而不是毫无动静地等（此前用户感觉像"在发愣"）。
        if (locked != was) notifyLockChanged(locked)
        // 同步「屏幕固定」：锁住时进入固定模式，从根上阻止上滑进多任务；解锁时退出
        try {
            PrivacyEnforcer.syncLockTask()
        } catch (_: Throwable) {}
    }

    private fun notifyLockChanged(on: Boolean) {
        val ctx = appCtx ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    ctx,
                    if (on) "隐私锁定已生效：双方都无法退出应用" else "隐私锁定已解除",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (_: Throwable) {
        }
    }
}
