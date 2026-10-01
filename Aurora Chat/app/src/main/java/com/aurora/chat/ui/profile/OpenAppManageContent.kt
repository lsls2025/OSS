package com.aurora.chat.ui.profile

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「开发者管理 → 应用」tab：第三方接入应用管理（真实后端版）。
 *
 * - 进入时拉取 /api/open/admin/list 展示全部申请记录
 * - 同意/拒绝/吊销/删除调用真实接口并刷新列表
 * - 「主动创建应用」：管理员手动创建应用并直接通过(approved)，返回 clientId / appSecret
 */
@Composable
fun OpenAppManageContent() {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<OpenAppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createdSecret by remember { mutableStateOf<String?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) {
        toastMsg = msg
        scope.launch {
            kotlinx.coroutines.delay(2200)
            toastMsg = null
        }
    }

    fun refresh() {
        loading = true
        scope.launch {
            val res = AuroraApi.openAdminList()
            if (res.success) {
                items = res.data?.map { OpenAppItem.fromJson(it) } ?: emptyList()
            } else {
                items = emptyList()
                toast("加载失败: ${res.message}")
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("第三方接入应用管理", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
        Spacer(Modifier.height(6.dp))
        Text(
            "管理第三方平台接入申请，可同意、拒绝或吊销授权；也可主动创建应用开给指定用户使用。",
            fontSize = 12.sp, color = Color(0xFF6B7280), lineHeight = 18.sp,
        )

        Spacer(Modifier.height(14.dp))

        // 顶部操作区：主动创建
        Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
        ) {
            Text("主动创建应用", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        if (toastMsg != null) {
            Spacer(Modifier.height(8.dp))
            Text(toastMsg!!, fontSize = 12.sp, color = Color(0xFFF04438))
        }

        Spacer(Modifier.height(16.dp))

        Text(
            if (loading) "申请列表（加载中…）" else "申请列表（${items.size}）",
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29),
        )
        Spacer(Modifier.height(8.dp))

        if (!loading && items.isEmpty()) {
            Text("暂无接入申请记录", fontSize = 13.sp, color = Color(0xFF9CA3AF))
        } else {
            items.forEach { item ->
                OpenAppItemCard(
                    item = item,
                    onAction = { action ->
                        scope.launch {
                            val res: com.aurora.chat.data.api.ApiResult<*> = when (action) {
                                OpenAppAction.APPROVE -> AuroraApi.openAdminReview(item.id, "approve")
                                OpenAppAction.REJECT -> AuroraApi.openAdminReview(item.id, "reject", "不符合接入要求")
                                OpenAppAction.REVOKE -> AuroraApi.openAdminRevoke(item.id)
                                OpenAppAction.DELETE -> AuroraApi.openAdminDelete(item.id)
                            }
                            if (res.success) {
                                toast(
                                    when (action) {
                                        OpenAppAction.APPROVE -> "已同意「${item.appName}」的接入申请"
                                        OpenAppAction.REJECT -> "已拒绝「${item.appName}」的接入申请"
                                        OpenAppAction.REVOKE -> "已吊销「${item.appName}」的授权"
                                        OpenAppAction.DELETE -> "已删除「${item.appName}」"
                                    }
                                )
                                refresh()
                            } else {
                                toast("操作失败: ${res.message}")
                            }
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (showCreateDialog) {
        OpenCreateAppDialog(
            onDismiss = { showCreateDialog = false },
            onSubmit = { appName, owner, scopes, remark ->
                scope.launch {
                    val res = AuroraApi.openAdminCreate(appName, owner, scopes, remark)
                    if (res.success) {
                        showCreateDialog = false
                        val d = res.data
                        createdSecret = "应用「${d?.optString("appName") ?: appName}」创建成功\n" +
                            "Client ID: ${d?.optString("clientId") ?: "-"}\n" +
                            "App Secret: ${d?.optString("appSecret") ?: "-"}"
                        toast("创建成功")
                        refresh()
                    } else {
                        toast("创建失败: ${res.message}")
                    }
                }
            },
        )
    }

    createdSecret?.let { secret ->
        AlertDialog(
            onDismissRequest = { createdSecret = null },
            title = { Text("应用创建成功", fontWeight = FontWeight.Bold) },
            text = {
                Text(secret, fontSize = 13.sp, lineHeight = 20.sp)
            },
            confirmButton = {
                TextButton(onClick = { createdSecret = null }) { Text("知道了") }
            },
        )
    }
}

/** 管理员主动创建应用表单弹窗 */
@Composable
private fun OpenCreateAppDialog(
    onDismiss: () -> Unit,
    onSubmit: (appName: String, owner: String, scopes: String, remark: String) -> Unit,
) {
    var appName by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var scopeEmail by remember { mutableStateOf(true) }
    var scopeQq by remember { mutableStateOf(true) }
    var scopeAvatar by remember { mutableStateOf(true) }
    var scopeName by remember { mutableStateOf(true) }
    var scopeUid by remember { mutableStateOf(true) }
    var remark by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主动创建应用", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text("应用名称 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it },
                    label = { Text("使用人用户名或 ID *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("所需权限 *（可多选）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A1D29))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF7F8FA), RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    PermissionRow("email", "邮箱", scopeEmail) { scopeEmail = it }
                    PermissionRow("qq", "QQ 号", scopeQq) { scopeQq = it }
                    PermissionRow("avatar", "头像", scopeAvatar) { scopeAvatar = it }
                    PermissionRow("name", "用户名/昵称", scopeName) { scopeName = it }
                    PermissionRow("uid", "用户ID", scopeUid) { scopeUid = it }
                }
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error!!, fontSize = 12.sp, color = Color(0xFFF04438))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (appName.isBlank()) {
                    error = "请填写应用名称"
                    return@TextButton
                }
                if (owner.isBlank()) {
                    error = "请填写使用人用户名或 ID"
                    return@TextButton
                }
                val scopes = buildList {
                    if (scopeEmail) add("email")
                    if (scopeQq) add("qq")
                    if (scopeAvatar) add("avatar")
                    if (scopeName) add("name")
                    if (scopeUid) add("uid")
                }
                if (scopes.isEmpty()) {
                    error = "请至少勾选一项所需权限"
                    return@TextButton
                }
                onSubmit(appName.trim(), owner.trim(), scopes.joinToString(","), remark.trim())
            }) { Text("创建并开通") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 一条接入申请记录(真实后端返回) */
private data class OpenAppItem(
    val id: Long,
    val appName: String,
    val clientId: String,
    val appSecret: String,
    val owner: String,
    val scopes: String,
    val status: String, // pending / approved / rejected / revoked
    val applyReason: String,
    val contact: String,
    val remark: String,
    val createdAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject): OpenAppItem = OpenAppItem(
            id = o.optLong("id"),
            appName = o.optString("appName"),
            clientId = o.optString("clientId"),
            appSecret = o.optString("appSecret"),
            owner = o.optString("owner"),
            scopes = o.optString("scopes"),
            status = o.optString("status"),
            applyReason = o.optString("applyReason"),
            contact = o.optString("contact"),
            remark = o.optString("remark"),
            createdAt = o.optLong("createdAt"),
        )
    }
}

private enum class OpenAppAction { APPROVE, REJECT, REVOKE, DELETE }

private fun statusLabel(status: String): String = when (status) {
    "pending" -> "待审核"
    "approved" -> "已通过"
    "rejected" -> "已拒绝"
    "revoked" -> "已吊销"
    else -> status
}

private fun statusColor(status: String): Color = when (status) {
    "pending" -> Color(0xFFD97706)
    "approved" -> Color(0xFF16A34A)
    "rejected" -> Color(0xFFF04438)
    "revoked" -> Color(0xFF9CA3AF)
    else -> Color(0xFF9CA3AF)
}

private fun formatTs(ts: Long): String =
    if (ts <= 0) "-" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts * 1000))

@Composable
private fun OpenAppItemCard(
    item: OpenAppItem,
    onAction: (OpenAppAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.appName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
                Spacer(Modifier.height(3.dp))
                Text(
                    "Client ID: ${item.clientId} · 使用人: ${item.owner.ifEmpty { "—" }}",
                    fontSize = 11.sp, color = Color(0xFF9CA3AF),
                )
            }
            Text(
                statusLabel(item.status),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = statusColor(item.status),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("申请权限: ${item.scopes.ifEmpty { "—" }}", fontSize = 12.sp, color = Color(0xFF6B7280))
        if (item.applyReason.isNotBlank()) {
            Text("申请理由: ${item.applyReason}", fontSize = 12.sp, color = Color(0xFF6B7280))
        }
        if (item.contact.isNotBlank()) {
            Text("联系方式: ${item.contact}", fontSize = 12.sp, color = Color(0xFF6B7280))
        }
        if (item.remark.isNotBlank()) {
            Text("备注: ${item.remark}", fontSize = 12.sp, color = Color(0xFF6B7280))
        }
        Text("申请时间: ${formatTs(item.createdAt)}", fontSize = 11.sp, color = Color(0xFF9CA3AF))
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            when (item.status) {
                "pending" -> {
                    ActionChip("同意", Color(0xFF16A34A)) { onAction(OpenAppAction.APPROVE) }
                    Spacer(Modifier.width(8.dp))
                    ActionChip("拒绝", Color(0xFFF04438)) { onAction(OpenAppAction.REJECT) }
                    Spacer(Modifier.width(8.dp))
                    ActionChip("删除", Color(0xFF9CA3AF)) { onAction(OpenAppAction.DELETE) }
                }
                "approved" -> {
                    ActionChip("吊销", Color(0xFFD97706)) { onAction(OpenAppAction.REVOKE) }
                    Spacer(Modifier.width(8.dp))
                    ActionChip("删除", Color(0xFF9CA3AF)) { onAction(OpenAppAction.DELETE) }
                }
                "rejected", "revoked" -> {
                    ActionChip("删除", Color(0xFF9CA3AF)) { onAction(OpenAppAction.DELETE) }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xFFF1F3F7), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** 权限勾选行：key 为 scopes 取值（email/qq/avatar） */
@Composable
private fun PermissionRow(
    key: String,
    title: String,
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
        Text("$title（$key）", fontSize = 14.sp, color = Color(0xFF1A1D29))
    }
}
