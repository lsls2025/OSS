package com.aurora.chat

import androidx.compose.runtime.Composable

// TODO 临时桩：撤回修复验证用。PerfMarker 真实实现在旧 release 映射中存在但当前源码缺失，
// 此处仅为让 :app:compileDebugKotlin 通过；待补全真实 PerfMarker 后删除本文件。
@Composable
fun PerfMarker(content: @Composable () -> Unit) {
    content()
}

var perfLastSwitchNs: Long = 0
