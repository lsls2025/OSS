package com.aurora.chat.ui.gomoku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 原生五子棋入口：全屏（仅隐藏底部导航栏，保留状态栏以避免内容被顶到最上），
 * 无标题/无网址信息，直接加载本地实现的棋盘与联机逻辑。
 */
class GomokuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 从「五子棋邀请卡片」进入时带着房间号：连上 WS 后自动 join 该房间
        val game = GomokuGame(applicationContext)
        intent?.getStringExtra("room_id")?.takeIf { it.isNotBlank() }?.let { game.setPendingJoin(it) }

        setContent {
            BackHandler {
                when (game.view) {
                    "game" -> game.backToRooms()
                    "rooms" -> game.backToLobby()
                    else -> finish()
                }
            }
            GomokuApp(game)
        }
    }
}
