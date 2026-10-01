package com.aurora.chat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.LoginResponse
import kotlinx.coroutines.launch

/**
 * 强制完善资料弹窗。
 * 用户在以下情况会被拦截：邮箱为占位符、QQ 号为空、用户名为空。
 * 弹窗不可跳过（返回键 / 点击外部均不关闭），必须完善后才能进入主界面。
 * 提交时仅 UPDATE 当前用户，不会创建新账号。
 */
@Composable
fun ForceProfileDialog(
    currentUsername: String,
    currentQQNumber: String,
    onComplete: (LoginResponse) -> Unit
) {
    var username by remember { mutableStateOf(currentUsername) }
    var qqNumber by remember { mutableStateOf(currentQQNumber) }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Dialog(
        onDismissRequest = { /* 不可跳过 */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("完善资料", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(6.dp))
                Text("请完善以下信息后继续使用", fontSize = 13.sp, color = Color(0xFF6B7280))
                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMsg = null },
                    label = { Text("用户名（必填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        cursorColor = Color(0xFF1E40AF)
                    )
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = qqNumber,
                    onValueChange = { qqNumber = it.filter { c -> c.isDigit() }; errorMsg = null },
                    label = { Text("QQ号（必填）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        cursorColor = Color(0xFF1E40AF)
                    )
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMsg = null },
                    label = { Text("密码（可选，建议设置）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        cursorColor = Color(0xFF1E40AF)
                    )
                )

                if (errorMsg != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(errorMsg!!, fontSize = 13.sp, color = Color(0xFFDC2626))
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (username.isBlank()) { errorMsg = "用户名不能为空"; return@Button }
                        if (qqNumber.isBlank() || qqNumber.length < 5 || qqNumber.length > 11) {
                            errorMsg = "请输入有效的QQ号（5-11位数字）"; return@Button
                        }
                        isLoading = true
                        errorMsg = null
                        scope.launch {
                            try {
                                val result = AuroraApi.completeProfile(username, qqNumber, password.ifEmpty { null })
                                if (result.success && result.data != null) {
                                    onComplete(result.data!!)
                                } else {
                                    errorMsg = result.message ?: "完善资料失败"
                                    isLoading = false
                                }
                            } catch (e: Exception) {
                                errorMsg = e.message ?: "完善资料失败"
                                isLoading = false
                            }
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
                        Text("保存", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
    }
}
