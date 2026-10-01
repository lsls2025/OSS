package com.aurora.chat.ui.profile

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.local.LoginHistoryManager
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SwitchAccountScreen(
    onBack: () -> Unit,
    onSwitchSuccess: (email: String, username: String, userId: Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 获取当前账号名（用于过滤）
    val currentEmail = com.aurora.chat.data.local.LocalStorage.loadLoginState(context).email
    // 读取登录历史（含解密）移 IO，避免组合期阻塞主线程
    var quickAccounts by remember { mutableStateOf(emptyList<LoginHistoryManager.LoginRecord>()) }
    LaunchedEffect(currentEmail) {
        quickAccounts = withContext(Dispatchers.IO) { LoginHistoryManager.getQuickSwitchAccounts(context, currentEmail) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(16.dp))

            // 顶部栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF3F4F6))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                }
                Spacer(Modifier.width(12.dp))
                Text("切换账号", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
            }

            // 快速切换胶囊体
            if (quickAccounts.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("快速切换", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF9CA3AF))
                Spacer(Modifier.height(8.dp))
                quickAccounts.forEach { record ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF3F4F6))
                            .clickable {
                                val pwd = LoginHistoryManager.getPassword(context, record.email)
                                if (pwd == null) {
                                    LoginHistoryManager.invalidatePassword(context, record.email)
                                    errorMsg = "密码数据异常，请手动输入"
                                    return@clickable
                                }
                                scope.launch {
                                    isLoading = true
                                    val result = ChatRepository.login(record.email, pwd)
                                    if (result.success && result.data != null) {
                                        val d = result.data!!
                                        LoginHistoryManager.recordLogin(context, d.email, d.username, d.id, pwd, d.qqNumber)
                                        onSwitchSuccess(d.email, d.username, d.id)
                                    } else {
                                        LoginHistoryManager.invalidatePassword(context, record.email)
                                        errorMsg = "登录失败，密码可能已更改，请手动输入"
                                    }
                                    isLoading = false
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var qsAvatar by remember(record.userId) { mutableStateOf<android.graphics.Bitmap?>(null) }
                        LaunchedEffect(record.userId) {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                qsAvatar = com.aurora.chat.data.repository.ChatRepository.loadAvatar(context, record.userId)
                            }
                        }
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFD1D5DB)),
                            contentAlignment = Alignment.Center) {
                            if (qsAvatar != null) {
                                Image(bitmap = qsAvatar!!.asImageBitmap(), contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Text(record.username.take(1).uppercase(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(record.username, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                            Text(if (record.qq.isNotBlank()) "QQ: ${record.qq}" else "未填写QQ号", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                        }
                        Spacer(Modifier.weight(1f))
                        Text("切换", fontSize = 13.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
                Spacer(Modifier.height(16.dp))
            }

            Text("用户名/QQ号", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; errorMsg = null },
                placeholder = { Text("输入用户名或QQ号", fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), cursorColor = Color(0xFF1E40AF))
            )

            Spacer(Modifier.height(20.dp))

            Text("密码", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMsg = null },
                placeholder = { Text("输入密码", fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), cursorColor = Color(0xFF1E40AF))
            )

            if (errorMsg != null) {
                Spacer(Modifier.height(12.dp))
                Text(errorMsg!!, fontSize = 13.sp, color = Color(0xFFDC2626))
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = {
                    if (email.isBlank() || password.isBlank()) {
                        errorMsg = "请输入账号和密码"
                        return@Button
                    }
                    isLoading = true
                    errorMsg = null
                    scope.launch {
                        val result = ChatRepository.login(email.trim(), password)
                        if (result.success && result.data != null) {
                            val d = result.data!!
                            LoginHistoryManager.recordLogin(context, d.email, d.username, d.id, password, d.qqNumber)
                            onSwitchSuccess(d.email, d.username, d.id)
                        } else {
                            errorMsg = result.message ?: "登录失败，请检查账号或密码"
                        }
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("切换账号", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
