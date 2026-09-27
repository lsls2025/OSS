package com.agit.app.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.agit.app.data.AppContainer
import com.agit.app.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AuthScreen(nc: NavHostController, mode: String) {
    val isLogin = mode == "login"
    val title = if (isLogin) "登录" else "注册"
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun submit() {
        if (username.isBlank() || password.isBlank()) {
            Toast.makeText(ctx, "请填写完整", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isLogin && password != confirm) {
            Toast.makeText(ctx, "两次密码不一致", Toast.LENGTH_SHORT).show()
            return
        }
        loading = true
        scope.launch(Dispatchers.IO) {
            val r = if (isLogin) AppContainer.authRepository.login(username.trim(), password)
                    else AppContainer.authRepository.register(username.trim(), password)
            r.onSuccess { u ->
                AppContainer.session.save(u)
                withContext(Dispatchers.Main) {
                    nc.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    loading = false
                    Toast.makeText(ctx, e.message ?: "失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                if (isLogin) "欢迎回来" else "创建你的账号",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("账号") },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (!isLogin) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("确认密码") },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = !loading,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                else Text(title)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = {
                    val target = if (isLogin) Routes.REGISTER else Routes.LOGIN
                    nc.navigate(target) { popUpTo(Routes.LOGIN) { inclusive = !isLogin } }
                }) {
                    Text(if (isLogin) "没有账号？去注册" else "已有账号？去登录")
                }
            }
        }
    }
}


