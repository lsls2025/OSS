package com.pm.manager.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pm.manager.core.ToastBus
import com.pm.manager.model.SiteProxy
import com.pm.manager.terminal.ProxyApi
import com.pm.manager.ui.components.AppButton
import com.pm.manager.ui.components.AppOutlinedField
import com.pm.manager.ui.components.ConfirmDialog
import com.pm.manager.ui.components.EmptyState
import com.pm.manager.ui.components.LoadingState
import com.pm.manager.ui.components.PureWhiteDialog
import com.pm.manager.ui.components.TopBar
import com.pm.manager.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

/** 反向代理（A 级可见）：管理自己绑定域名下的反代，提交进待审态。 */
@Composable
fun ProxyScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<SiteProxy>>(emptyList()) }
    var editing by remember { mutableStateOf<SiteProxy?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SiteProxy?>(null) }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            runCatching { ProxyApi.list() }
                .onSuccess { list = it; loading = false }
                .onFailure { error = it.message ?: "加载失败"; loading = false }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().background(c.appBg)) {
        TopBar(title = "反向代理", onBack = onClose, actions = {
            IconButton(onClick = { reload() }) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = c.textPrimary) }
        })
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            if (loading) LoadingState()
            else if (error != null) com.pm.manager.ui.components.ErrorState(error ?: "加载失败") { reload() }
            else if (list.isEmpty()) EmptyState("还没有反代记录", Icons.Rounded.VpnKey)
            else LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { p ->
                    ProxyRow(p, onEdit = { editing = p }, onDelete = { pendingDelete = p })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            AppButton(text = "新增反向代理", onClick = { showAdd = true })
        }
    }

    if (showAdd) ProxyFormDialog(null, onDismiss = { showAdd = false }, onSave = {
        showAdd = false
        scope.launch {
            runCatching { ProxyApi.add(it) }
                .onSuccess { ToastBus.show(context, "已提交，待审核", long = true); reload() }
                .onFailure { ToastBus.show(context, it.message ?: "提交失败") }
        }
    })
    if (editing != null) ProxyFormDialog(editing, onDismiss = { editing = null }, onSave = {
        val e = editing!!
        editing = null
        scope.launch {
            runCatching { ProxyApi.update(e.copy(name = it.name, listenPath = it.listenPath, targetHost = it.targetHost, targetPort = it.targetPort, targetPath = it.targetPath)) }
                .onSuccess { ToastBus.show(context, "已提交修改，待审核", long = true); reload() }
                .onFailure { ToastBus.show(context, it.message ?: "修改失败") }
        }
    })
    if (pendingDelete != null) {
        ConfirmDialog(title = "删除反代", danger = true, message = "确定删除「${pendingDelete!!.name}」？若是已生效记录，对应反向代理会一并移除。", onConfirm = {
            val t = pendingDelete!!
            pendingDelete = null
            scope.launch {
                runCatching { ProxyApi.delete(t.id) }
                    .onSuccess { ToastBus.show(context, "已删除"); reload() }
                    .onFailure { ToastBus.show(context, it.message ?: "删除失败") }
            }
        }, onDismiss = { pendingDelete = null })
    }
}

@Composable
private fun ProxyRow(p: SiteProxy, onEdit: () -> Unit, onDelete: () -> Unit) {
    val c = LocalAppColors.current
    val statusColor = when (p.status) {
        "approved" -> c.teal
        "rejected" -> c.danger
        "existing" -> c.teal
        else -> c.textSecondary
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cardBg).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(p.statusLabel(), color = statusColor, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text("${p.listenPath}  →  ${p.targetHost}:${p.targetPort}${if (p.targetPath.isNotBlank()) "/${p.targetPath.trimStart('/')}" else ""}",
            color = c.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!p.isExisting) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "修改", tint = c.teal, modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "删除", tint = c.danger, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

/** 反代编辑弹窗：新增或修改。所有字段做基础非空校验，非法字符由服务端兜底拦截。 */
@Composable
private fun ProxyFormDialog(initial: SiteProxy?, onDismiss: () -> Unit, onSave: (SiteProxy) -> Unit) {
    PureWhiteDialog(onDismiss) {
        val c = LocalAppColors.current
        var name by remember { mutableStateOf(initial?.name ?: "") }
        var listenPath by remember { mutableStateOf(initial?.listenPath ?: "/") }
        var targetHost by remember { mutableStateOf(initial?.targetHost ?: "") }
        var targetPort by remember { mutableStateOf(if (initial != null) initial.targetPort.toString() else "") }
        var targetPath by remember { mutableStateOf(initial?.targetPath ?: "") }

        Column(Modifier.padding(20.dp).width(320.dp).padding(end = 8.dp)) {
            Text(if (initial == null) "新增反代" else "修改反代", color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            AppOutlinedField(value = name, onValueChange = { name = it }, label = "名称")
            Spacer(Modifier.height(10.dp))
            AppOutlinedField(value = listenPath, onValueChange = { listenPath = it }, label = "入口路径（如 /api/）")
            Spacer(Modifier.height(10.dp))
            AppOutlinedField(value = targetHost, onValueChange = { targetHost = it }, label = "目标地址（如 127.0.0.1 或域名）")
            Spacer(Modifier.height(10.dp))
            AppOutlinedField(
                value = targetPort, onValueChange = { targetPort = it },
                label = "目标端口", singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(10.dp))
            AppOutlinedField(value = targetPath, onValueChange = { targetPath = it }, label = "目标路径前缀（可留空）")
            Spacer(Modifier.height(18.dp))
            val valid = name.isNotBlank() && listenPath.startsWith("/") && targetHost.isNotBlank() &&
                (targetPort.toIntOrNull()?.let { it in 1..65535 } == true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.Button(onClick = onDismiss, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = c.textSecondary)) {
                    Text("取消")
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        val p = SiteProxy(
                            id = initial?.id ?: 0L, name = name.trim(),
                            listenPath = listenPath.trim(), targetHost = targetHost.trim(),
                            targetPort = targetPort.toInt(), targetPath = targetPath.trim(),
                            status = initial?.status ?: "pending", updatedTime = 0
                        )
                        onSave(p)
                    },
                    enabled = valid,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c.teal)
                ) { Text("提交", color = Color.White) }
            }
        }
    }
}

/** 管理后台（仅 owner 可见）：先输口令进入，再对所有域名待审反代放行/驳回。 */
@Composable
fun AdminProxyScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current

    var password by remember { mutableStateOf("") }
    var authed by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<Pair<String, SiteProxy>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var pendingReject by remember { mutableStateOf<Pair<String, SiteProxy>?>(null) }

    val pwKey = "proxy_admin_pw"
    fun rememberPassword() {
        if (password.isNotBlank()) {
            runCatching { com.pm.manager.core.AppPrefs.raw().edit().putString(pwKey, password).apply() }
        }
    }

    fun load() {
        loading = true
        error = null
        scope.launch {
            runCatching { ProxyApi.adminList(password) }
                .onSuccess { items = it; loading = false }
                .onFailure { authed = false; error = it.message ?: "加载失败"; loading = false }
        }
    }

    LaunchedEffect(Unit) {
        // 上次登录过的口令本机会话内记住，避免每次进入都要重输；非空则自动进入
        val saved = com.pm.manager.core.AppPrefs.raw().getString(pwKey, "") ?: ""
        password = saved
        if (saved.isNotBlank()) {
            authed = true
            load()
        }
    }

    Column(Modifier.fillMaxSize().background(c.appBg)) {
        TopBar(title = "管理后台", onBack = onClose, actions = {
            if (authed) IconButton(onClick = { load() }) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = c.textPrimary) }
        })

        if (!authed) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text("输入管理口令后进入", color = c.textSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                AppOutlinedField(
                    value = password, onValueChange = { password = it },
                    label = "管理口令", singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error ?: "验证失败", color = c.danger, fontSize = 12.sp)
                }
                Spacer(Modifier.height(14.dp))
                AppButton(text = "进入审核", loading = verifying, onClick = {
                    if (password.isBlank()) return@AppButton
                    scope.launch {
                        verifying = true
                        error = null
                        // 通过列表接口验证口令：口令正确才放行列表，借两次校验的 server.round 兜底
                        runCatching { ProxyApi.adminList(password) }
                            .onSuccess {
                                authed = true
                                items = it
                                loading = false
                                rememberPassword()
                            }
                            .onFailure { error = it.message ?: "管理口令错误" }
                        verifying = false
                    }
                })
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Text("共 ${items.size} 条记录 · 放行后写入 nginx 生效",
                    color = c.textTertiary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (loading) LoadingState("加载中…")
                else if (items.isEmpty()) EmptyState("暂无反代记录", Icons.Rounded.VpnKey)
                else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(items, key = { "${it.first}_${it.second.id}" }) { (domain, p) ->
                        AdminProxyRow(domain, p, busy,
                            onApprove = {
                                scope.launch {
                                    busy = true
                                    runCatching { ProxyApi.approve(domain, p.id, password) }
                                        .onSuccess { ToastBus.show(context, "已放行：「${p.name}」", long = true); load() }
                                        .onFailure { ToastBus.show(context, it.message ?: "放行失败") }
                                    busy = false
                                }
                            },
                            onReject = { pendingReject = domain to p }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (pendingReject != null) {
        ConfirmDialog(title = "驳回反代", message = "确定驳回「${pendingReject!!.second.name}」（${pendingReject!!.first}）？", onConfirm = {
            val (domain, p) = pendingReject!!
            pendingReject = null
            scope.launch {
                busy = true
                runCatching { ProxyApi.reject(domain, p.id, password) }
                    .onSuccess { ToastBus.show(context, "已驳回"); load() }
                    .onFailure { ToastBus.show(context, it.message ?: "驳回失败") }
                busy = false
            }
        }, onDismiss = { pendingReject = null })
    }
    if (busy) com.pm.manager.ui.components.ProgressOverlay(0f, "处理中…")
}

@Composable
private fun AdminProxyRow(domain: String, p: SiteProxy, busy: Boolean, onApprove: () -> Unit, onReject: () -> Unit) {
    val c = LocalAppColors.current
    val statusColor = when (p.status) {
        "approved" -> c.teal
        "rejected" -> c.danger
        else -> c.textSecondary
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cardBg).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(domain, color = c.teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(p.statusLabel(), color = statusColor, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(p.name, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("${p.listenPath}  →  ${p.targetHost}:${p.targetPort}${if (p.targetPath.isNotBlank()) "/${p.targetPath.trimStart('/')}" else ""}",
            color = c.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (p.status != "approved") {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.Button(
                    onClick = onReject, enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c.danger)
                ) { Text("驳回", color = Color.White, fontSize = 13.sp) }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Button(
                    onClick = onApprove, enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c.teal)
                ) { Text("放行", color = Color.White, fontSize = 13.sp) }
            }
        }
    }
}