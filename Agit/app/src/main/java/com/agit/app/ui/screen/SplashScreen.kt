package com.agit.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.agit.app.data.AppContainer
import com.agit.app.data.model.VersionResponse
import com.agit.app.ui.Routes
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(nc: NavHostController) {
    var showUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(400)
        if (!AppContainer.session.isLoggedIn()) {
            nc.navigate(Routes.LOGIN) { popUpTo(Routes.SPLASH) { inclusive = true } }
            return@LaunchedEffect
        }
        val token = AppContainer.session.token()!!
        // 登录态校验：token 无效则踢出登录；有效则强制从服务器拉取最新资料（含头像）并写入本地缓存
        val me = AppContainer.authRepository.me(token)
        if (me.isFailure) {
            AppContainer.session.clear()
            nc.navigate(Routes.LOGIN) { popUpTo(Routes.SPLASH) { inclusive = true } }
            return@LaunchedEffect
        }
        AppContainer.session.save(me.getOrNull()!!.copy(token = token))

        // 版本校验：低于服务器要求的最低版本则强制更新
        val ver: VersionResponse? = runCatching { AppContainer.repoRepository.getVersion() }.getOrNull()
        val needUpdate = ver != null && ver.minVersion.isNotBlank() && isOlder(APP_VERSION, ver.minVersion)
        if (needUpdate) showUpdate = true
        else nc.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Agit", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                Text("移动端代码仓库", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    if (showUpdate) {
        AlertDialog(
            onDismissRequest = { /* 版本过低，不可关闭 */ },
            title = { Text("发现新版本") },
            text = { Text("当前版本过低，请更新到最新版本后再继续使用。") },
            confirmButton = {
                TextButton(onClick = { android.os.Process.killProcess(android.os.Process.myPid()) }) {
                    Text("退出")
                }
            }
        )
    }
}

private const val APP_VERSION = "1.0.0"

private fun isOlder(app: String, min: String): Boolean {
    fun parse(v: String) = v.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    val a = parse(app)
    val b = parse(min)
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x < y) return true
        if (x > y) return false
    }
    return false
}
