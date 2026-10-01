package com.aurora.chat.ui.open

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.open.OpenAuthManager
import kotlinx.coroutines.launch

/**
 * 第三方授权全屏界面（仿桌面端扫码登录的账号授权确认 LoginAuthReview）。
 *
 * 布局与二维码授权界面保持一致：浅灰底全屏 + 顶部栏 + 可滚动说明区
 * （平台信息卡片 + 安全提示卡片）+ 底部「取消 / 确定授权」双按钮。
 *
 * 确定 → 调 /api/open/authorize 签发一次性授权码，成功则拉起 redirect_uri?code=xxx&state=yyy；
 * 取消 → 拉起 redirect_uri?error=access_denied&state=yyy；无回调地址时若检测到来源应用（source_pkg /
 * referrer）则直接跳回发起方，仍无来源信息则仅 Toast 提示。
 */
@Composable
fun OpenAuthScreen(
    req: OpenAuthManager.OpenAuthRequest,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 服务端应用真实信息：进入授权页先拉取，展示以真实申请的 scopes 为准
    var serverAppName by remember { mutableStateOf<String?>(null) }
    var serverScopes by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(req.clientId) {
        val info = AuroraApi.openAppInfo(req.clientId)
        if (info.success && info.data != null) {
            val appName = info.data.optString("appName").takeIf { it.isNotBlank() }
            val scopesStr = info.data.optString("scopes").takeIf { it.isNotBlank() }
            if (appName != null) serverAppName = appName
            if (scopesStr != null) {
                serverScopes = scopesStr.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
    }
    // 展示名称与权限列表：优先服务端真实值，拉取失败时回退 deep link 参数
    val displayName = serverAppName ?: req.appName
    val displayScopes = serverScopes ?: req.scopes

    // 取消：若有回调地址则打开 redirect_uri?error=access_denied&state=yyy，再关闭界面
    fun cancelFlow() {
        openCallback(context, req, error = "access_denied")
        onBack()
    }

    // 确定：调后端 authorizeOpen 签发一次性授权码，成功后回调 redirect_uri?code=xxx&state=yyy
    fun confirmFlow() {
        scope.launch {
            val result = AuroraApi.authorizeOpen(
                clientId = req.clientId,
                scopes = displayScopes.joinToString(","),
                redirectUri = req.redirectUri,
                state = req.state,
            )
            if (result.success && result.data != null) {
                val code = result.data.optString("code")
                Toast.makeText(context, "授权成功", Toast.LENGTH_SHORT).show()
                if (code.isNotBlank()) {
                    openCallback(context, req, code = code, state = req.state)
                }
            } else {
                Toast.makeText(context, result.message.ifBlank { "授权失败" }, Toast.LENGTH_SHORT).show()
            }
            onConfirm()
        }
    }

    // 返回（含侧滑返回手势）等同取消
    BackHandler { cancelFlow() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FB))
            .statusBarsPadding(),
    ) {
        // 顶部栏（仿二维码授权界面）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp).clickable { cancelFlow() },
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 28.sp, color = Color(0xFF1A1D29)) }
            Spacer(Modifier.width(8.dp))
            Text("第三方授权", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
        }

        // 说明 + 平台信息 + 安全提示（可滚动）
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                "「${displayName}」请求获取你的以下账号信息。授权后，该平台可读取你授权范围内的数据。",
                fontSize = 13.sp,
                color = Color(0xFF6B7280),
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(16.dp))

            // 平台信息卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text(displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
                Spacer(Modifier.height(4.dp))
                Text("申请权限", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A1D29))
                Spacer(Modifier.height(6.dp))
                if (displayScopes.isEmpty()) {
                    Text("该应用未申请任何权限", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                } else {
                    displayScopes.forEach { scope ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("•", fontSize = 14.sp, color = Color(0xFF1A1D29))
                            Spacer(Modifier.width(6.dp))
                            Text(scopeLabel(scope), fontSize = 13.sp, color = Color(0xFF374151))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "若用户未填写 QQ 号或邮箱，对应数据字段将为空",
                    fontSize = 11.sp,
                    color = Color(0xFF9CA3AF),
                )
            }
            Spacer(Modifier.height(16.dp))

            // 安全提示卡片（仿二维码授权界面）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text("安全提示", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. 请确认该平台为你信任的第三方服务。\n" +
                        "2. 授权后对方可读取你授权范围内的账号信息。\n" +
                        "3. 如非本人操作，请点击「取消」并勿继续授权。\n",
                    fontSize = 12.sp,
                    color = Color(0xFF6B7280),
                    lineHeight = 18.sp,
                )
            }
        }

        // 底部操作（安全区）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 取消
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .weight(1f)
                        .background(Color(0xFFEFF1F5), RoundedCornerShape(12.dp))
                        .clickable { cancelFlow() },
                    contentAlignment = Alignment.Center,
                ) { Text("取消", color = Color(0xFF6B7280), fontSize = 15.sp, fontWeight = FontWeight.Medium) }

                // 确定授权
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .weight(1f)
                        .background(Color(0xFF1A1D29), RoundedCornerShape(12.dp))
                        .clickable { confirmFlow() },
                    contentAlignment = Alignment.Center,
                ) { Text("确定授权", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "请确认你信任该平台后再授权",
                fontSize = 11.sp, color = Color(0xFF9CA3AF),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** 权限 scope 中文展示 */
private fun scopeLabel(scope: String): String = when (scope.lowercase()) {
    "email" -> "邮箱地址"
    "qq" -> "QQ 号"
    "avatar" -> "头像"
    "name" -> "用户名/昵称"
    "uid" -> "用户ID"
    "phone" -> "手机号"
    else -> scope
}

/**
 * 拼接授权结果回调地址并用 ACTION_VIEW 拉起。
 * 成功：redirect_uri?code=xxx&state=yyy；失败/取消：redirect_uri?error=access_denied&state=yyy。
 * 无 redirect_uri 时不拉起浏览器，仅 Toast 提示。
 */
private fun openCallback(
    context: Context,
    req: OpenAuthManager.OpenAuthRequest,
    code: String? = null,
    state: String = "",
    error: String? = null,
) {
    // 优先标准回调：拉起 redirect_uri?code=xxx&state=yyy（成功）或 ?error=access_denied&state=yyy（取消）
    if (req.redirectUri.isNotBlank()) {
        val sb = StringBuilder(req.redirectUri)
        sb.append(if (req.redirectUri.contains("?")) "&" else "?")
        if (error != null) {
            sb.append("error=").append(error)
            if (state.isNotBlank()) sb.append("&state=").append(state)
        } else {
            if (code != null) sb.append("code=").append(code)
            if (state.isNotBlank()) sb.append("&state=").append(state)
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sb.toString()))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开回调地址", Toast.LENGTH_SHORT).show()
        }
        return
    }
    // 无回调地址：检测到来源应用则直接跳回发起方
    if (req.sourcePkg.isNotBlank()) {
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(req.sourcePkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return
            }
        } catch (e: Exception) {
            // 跳回失败继续走 Toast 兜底
        }
    }
    // 无回调地址且未检测到来源：仅提示
    if (error != null) {
        Toast.makeText(context, "已取消授权", Toast.LENGTH_SHORT).show()
    }
}
