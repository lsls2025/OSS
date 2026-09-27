package com.pm.manager.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 卡密会话状态。
 *
 * 原来是一个 `@Volatile var expired: Boolean`，因为它不是 Compose 状态，界面感知不到变化，
 * 只能靠 MainActivity 里 `while(true) { ... delay(1000) }` 每秒轮询一次——一个永不停止的协程
 * 常驻后台，且最长有 1 秒的延迟才踢回激活页。
 *
 * 改成 StateFlow：网络层在任意线程置位，界面用 `collectAsState` 立刻响应，省掉常驻轮询。
 */
object SessionState {

    private val _expired = MutableStateFlow(false)
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    private val _pushStatus = MutableStateFlow("")
    val pushStatus: StateFlow<String> = _pushStatus.asStateFlow()

    /** 网络层发现卡密失效/被删时调用（可能在 IO 线程）。 */
    fun markExpired() {
        _expired.value = true
    }

    /** 重新激活成功后复位。 */
    fun reset() {
        _expired.value = false
    }

    fun setPushStatus(text: String) {
        _pushStatus.value = text
    }
}
