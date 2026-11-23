package com.aurora.chat.ui.chat

import android.content.Context
import com.aurora.chat.AuroraChatApplication
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 转账状态的本地权威缓存。
 *
 * 之前只在内存里：清后台重开就丢了，卡片又变回「请收款」（点进详情拉一次接口才临时恢复）。
 * 现在每次 put 都落 SharedPreferences，进程重启后 init 自动读回。
 *
 * 另外用 fullSyncPending 通知消息拉取做一次全量对齐：领取时服务端会改写老转账消息的正文、
 * 并插入一条「已收款」卡片，只按 afterId 增量拉取永远拿不到老消息的改动。
 */
object TransferStatusCache {
    private const val PREFS_NAME = "transfer_status_cache"
    private const val KEY_PREFIX = "t_"

    private val states = mutableMapOf<Long, Pair<String, Long>>()
    val version = MutableStateFlow(0)

    private val fullSyncPending = AtomicBoolean(false)

    private fun prefs() = runCatching {
        AuroraChatApplication.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.getOrNull()

    init {
        runCatching {
            val all = prefs()?.all ?: return@runCatching
            for ((k, v) in all) {
                if (!k.startsWith(KEY_PREFIX)) continue
                val id = k.removePrefix(KEY_PREFIX).toLongOrNull() ?: continue
                val parts = (v as? String)?.split("|") ?: continue
                states[id] = parts[0] to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
            }
        }
    }

    fun put(transferId: Long, status: String, claimedAt: Long, requestFullSync: Boolean = true) {
        if (transferId <= 0L) return
        synchronized(states) { states[transferId] = status to claimedAt }
        runCatching {
            prefs()?.edit()?.putString("$KEY_PREFIX$transferId", "$status|$claimedAt")?.apply()
        }
        if (requestFullSync) fullSyncPending.set(true)
        version.value = version.value + 1
    }

    fun statusOf(transferId: Long): Pair<String, Long>? = synchronized(states) { states[transferId] }

    /** 消费一次「需要全量对齐」标记（只触发一次，避免 15s 轮询反复全量拉） */
    fun consumeFullSync(): Boolean = fullSyncPending.getAndSet(false)
}
