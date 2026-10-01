package com.aurora.chat.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.aurora.chat.host.HostConfig
import com.aurora.chat.host.pm.HostMainScreen
import com.aurora.chat.host.pm.HostPmPrefs
import com.aurora.chat.host.pm.ProvideHostColors

/**
 * 虚拟主机：从工作区右侧入口点进来即全屏界面。
 * 底部照搬「项目管理」的 文件 / 终端 / 备份 / 设置 四 tab，所有功能均对接 site_server.py，与「项目管理」一致。
 */
@Composable
fun HostFileManagerScreen(onBack: () -> Unit, onBackToWorkspace: () -> Unit = {}) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        HostConfig.loadFromPrefs(ctx)
        HostPmPrefs.load(ctx)
    }
    ProvideHostColors {
        HostMainScreen(onExit = onBack, onBackToWorkspace = onBackToWorkspace)
    }
}
