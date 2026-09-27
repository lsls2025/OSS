package com.pm.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pm.manager.core.ToastBus
import com.pm.manager.terminal.SiteConfig
import com.pm.manager.terminal.SiteFiles
import com.pm.manager.ui.components.AppButton
import com.pm.manager.ui.components.AppOutlinedField
import com.pm.manager.ui.components.ProgressOverlay
import androidx.compose.material.icons.rounded.*
import com.pm.manager.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

@Composable
fun ActivationScreen(onActivated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current

    var card by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(c.appBg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.9f).clip(RoundedCornerShape(18.dp)).background(c.cardBg).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(androidx.compose.material.icons.Icons.Rounded.CloudDone, contentDescription = null, tint = c.teal, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Text("站点管理器", color = c.textPrimary, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.Text("输入卡密与密码以连接服务器", color = c.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            AppOutlinedField(value = card, onValueChange = { card = it }, label = "卡密", singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
            Spacer(Modifier.height(12.dp))
            AppOutlinedField(value = password, onValueChange = { password = it }, label = "密码", singleLine = true, visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(8.dp))
            error?.let {
                androidx.compose.material3.Text(it, color = c.danger, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            AppButton(text = "激活", loading = loading, onClick = {
                if (card.isBlank() || password.isBlank()) { error = "请填写卡密和密码"; return@AppButton }
                loading = true
                error = null
                scope.launch {
                    SiteFiles.activate(context, card.trim(), password).fold(
                        onSuccess = { perm ->
                            SiteConfig.setPerm(context, perm)
                            ToastBus.show(context, "激活成功")
                            onActivated()
                        },
                        onFailure = { error = it.message ?: "激活失败"; loading = false }
                    )
                }
            })
        }
    }
    if (loading) ProgressOverlay(0f, "激活中…")
}
