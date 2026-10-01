package com.aurora.chat.ui.profile

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.launch

/**
 * 「开发者管理 → 申请第三方接入」tab：普通登录用户即可提交接入申请（无 open_apps.manage 权限限制）。
 *
 * 必填项：应用名称、申请理由/用途说明、所需权限（勾选）、联系方式（QQ/邮箱）。
 * 提交后调用 POST /api/open/apply，进入 pending 待管理员审核。
 */
@Composable
fun OpenApplyContent() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var appName by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var scopeEmail by remember { mutableStateOf(false) }
    var scopeQq by remember { mutableStateOf(false) }
    var scopeAvatar by remember { mutableStateOf(false) }
    var scopeName by remember { mutableStateOf(false) }
    var scopeUid by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("申请第三方接入", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
        Spacer(Modifier.height(6.dp))
        Text(
            "第三方平台/应用如需接入 Aurora Chat 开放接口，请填写以下信息提交申请，管理员审核通过后即可使用。",
            fontSize = 12.sp, color = Color(0xFF6B7280), lineHeight = 18.sp,
        )

        Spacer(Modifier.height(14.dp))

        // 「如何调用」说明：基于当前后端真实能力，无 token 下发/用户数据接口
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF0F4FF), RoundedCornerShape(10.dp))
                .padding(12.dp),
        ) {
            Text("如何调用", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
            Spacer(Modifier.height(6.dp))
            Text(
                "1. 审核通过后获得 Client ID 与 App Secret；\n" +
                    "2. 拉起授权页（浏览器 / 系统跳转均可）：\n" +
                    "   aurora://oauth/authorize?client_id=xxx&app_name=应用名&scopes=email,qq,avatar&redirect_uri=https://your-site.com/oauth/callback\n" +
                    "3. 用户在 Aurora Chat 内确认授权后，自动回跳 redirect_uri?code=xxx&state=xxx，即完成接入。\n" +
                    "scopes 支持 email、qq、avatar、name、uid，多个用英文逗号分隔；当前开放能力仅此一项，暂无 token 换取与用户数据接口。",
                fontSize = 12.sp, color = Color(0xFF374151), lineHeight = 19.sp,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "完整调用及使用指南详见官方文档 → ",
                    fontSize = 12.sp,
                    color = Color(0xFF374151),
                )
                Text(
                    "查看官方文档",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.YOUR_SERVER_DOMAIN/backend/aurora-open-docs.html"),
                        )
                        context.startActivity(intent)
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = appName,
            onValueChange = { appName = it },
            label = { Text("应用名称 *") },
            placeholder = { Text("例如：晨光助手") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            label = { Text("申请理由 / 用途说明 *") },
            placeholder = { Text("请说明接入用途、目标用户等，便于管理员审核") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Text("所需权限 *（可多选）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A1D29))
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7F8FA), RoundedCornerShape(10.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            PermissionCheckRow("email", "邮箱", "读取用户绑定邮箱", scopeEmail) { scopeEmail = it }
            PermissionCheckRow("qq", "QQ 号", "读取用户 QQ 号", scopeQq) { scopeQq = it }
            PermissionCheckRow("avatar", "头像", "读取用户头像", scopeAvatar) { scopeAvatar = it }
            PermissionCheckRow("name", "用户名/昵称", "读取用户名/昵称", scopeName) { scopeName = it }
            PermissionCheckRow("uid", "用户ID", "读取用户 ID", scopeUid) { scopeUid = it }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = contact,
            onValueChange = { contact = it },
            label = { Text("联系方式 *（QQ / 邮箱）") },
            placeholder = { Text("用于管理员与你联系") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        if (errorMsg != null) {
            Text(errorMsg!!, fontSize = 12.sp, color = Color(0xFFF04438))
            Spacer(Modifier.height(8.dp))
        }
        if (message != null) {
            Text(message!!, fontSize = 13.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                errorMsg = null
                message = null
                val scopes = buildList {
                    if (scopeEmail) add("email")
                    if (scopeQq) add("qq")
                    if (scopeAvatar) add("avatar")
                    if (scopeName) add("name")
                    if (scopeUid) add("uid")
                }
                when {
                    appName.isBlank() -> errorMsg = "请填写应用名称"
                    reason.isBlank() -> errorMsg = "请填写申请理由 / 用途说明"
                    scopes.isEmpty() -> errorMsg = "请至少勾选一项所需权限"
                    contact.isBlank() -> errorMsg = "请填写联系方式"
                    else -> {
                        submitting = true
                        scope.launch {
                            val res = AuroraApi.openApply(appName.trim(), reason.trim(), scopes.joinToString(","), contact.trim())
                            if (res.success) {
                                message = "已提交，等待管理员审核"
                                appName = ""
                                reason = ""
                                contact = ""
                                scopeEmail = false
                                scopeQq = false
                                scopeAvatar = false
                                scopeName = false
                                scopeUid = false
                            } else {
                                errorMsg = "提交失败: ${res.message}"
                            }
                            submitting = false
                        }
                    }
                }
            },
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
        ) {
            if (submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("提交申请", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PermissionCheckRow(
    key: String,
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1E40AF)),
        )
        Column {
            Text("$title（$key）", fontSize = 14.sp, color = Color(0xFF1A1D29))
            Text(desc, fontSize = 11.sp, color = Color(0xFF9CA3AF))
        }
    }
}

/**
 * 「申请第三方接入」独立全屏页面（从「我的 → 其他 → 申请第三方接入」进入）。
 * 顶部自带标题与返回，不套在开发者管理里。
 */
@Composable
fun OpenApplyScreen(onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FA))
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶部栏：状态栏避让 + 返回 + 标题
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F8FA))
                    .statusBarsPadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "申请第三方接入",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            // 申请表单内容
            Box(Modifier.fillMaxSize()) {
                OpenApplyContent()
            }
        }
    }
}
