package com.pm.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pm.manager.core.AppPrefs
import com.pm.manager.terminal.SessionState
import com.pm.manager.terminal.SiteConfig
import com.pm.manager.terminal.WsManager
import com.pm.manager.ui.ActivationScreen
import com.pm.manager.ui.MainScreen
import com.pm.manager.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(this)
        setContent {
            val settings by AppPrefs.settings.collectAsStateWithLifecycle()
            AppTheme(settings.themeMode) {
                AppGate()
            }
        }
    }
}

/**
 * 应用总开关：激活态决定显示「文件管理器」还是「激活页」。
 * 卡密失效由网络层通过 [SessionState.expired] 直接推送（StateFlow），界面即时响应，
 * 不再需要每秒轮询一次。
 */
@Composable
private fun AppGate() {
    val context = LocalContext.current
    val expired by SessionState.expired.collectAsStateWithLifecycle()
    var activated by remember { mutableStateOf(SiteConfig.isActivated(context)) }

    LaunchedEffect(expired) {
        if (expired) {
            WsManager.stop()
            SiteConfig.clear(context)
            activated = false
        }
    }
    LaunchedEffect(activated) {
        if (activated) {
            SiteConfig.loadFromPrefs(context)
            WsManager.ensureStarted()
        }
    }

    if (activated) {
        MainScreen(onLogout = {
            WsManager.stop()
            SiteConfig.clear(context)
            activated = false
        })
    } else {
        ActivationScreen(onActivated = { activated = true })
    }
}
