package com.pm.manager.ui
import androidx.compose.foundation.layout.*

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pm.manager.core.ToastBus
import com.pm.manager.core.formatBytes
import com.pm.manager.model.SiteBackup
import com.pm.manager.terminal.SiteFiles
import com.pm.manager.ui.components.AppButton
import com.pm.manager.ui.components.ConfirmDialog
import com.pm.manager.ui.components.EmptyState
import com.pm.manager.ui.components.LoadingState
import com.pm.manager.ui.components.ProgressOverlay
import androidx.compose.material.icons.rounded.*
import com.pm.manager.ui.components.TopBar
import com.pm.manager.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var backups by remember { mutableStateOf<List<SiteBackup>>(emptyList()) }
    var balance by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var restoreTarget by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<String?>(null) }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            runCatching { SiteFiles.listBackups() }
                .onSuccess { balance = it.balance; backups = it.backups; loading = false }
                .onFailure { error = it.message ?: "加载失败"; loading = false }
        }
    }
    LaunchedEffect(Unit) { reload() }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val name = pendingDownload ?: return@rememberLauncherForActivityResult
        if (uri == null) { pendingDownload = null; return@rememberLauncherForActivityResult }
        scope.launch {
            busy = true
            progressText = "下载中…"
            runCatching { SiteFiles.downloadBackupToUri(name, uri, context.contentResolver) { progress = it } }
                .onSuccess { ToastBus.show(context, "下载完成：$name", long = true) }
                .onFailure { ToastBus.show(context, it.message ?: "下载失败") }
            busy = false
            progress = 0f
            pendingDownload = null
        }
    }

    Column(Modifier.fillMaxSize().background(c.appBg)) {
        TopBar(title = "站点备份", onBack = onClose, actions = {
            IconButton(onClick = { reload() }) { Icon(androidx.compose.material.icons.Icons.Rounded.Refresh, contentDescription = "刷新", tint = c.textPrimary) }
        })
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("可用备份额度", color = c.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text("$balance", color = c.teal, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            AppButton(text = if (loading) "加载中…" else "创建备份", onClick = {
                scope.launch {
                    busy = true
                    progressText = "备份中…"
                    runCatching { SiteFiles.createBackup() }
                        .onSuccess { ToastBus.show(context, "已创建备份：${it.name}", long = true); reload() }
                        .onFailure { ToastBus.show(context, it.message ?: "备份失败") }
                    busy = false
                }
            }, enabled = !loading)
        }

        if (loading) {
            LoadingState()
        } else if (error != null) {
            com.pm.manager.ui.components.ErrorState(error ?: "加载失败") { reload() }
        } else if (backups.isEmpty()) {
            EmptyState("还没有备份", androidx.compose.material.icons.Icons.Rounded.Backup)
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(backups, key = { it.id }) { b ->
                    BackupRow(
                        b = b,
                        onRestore = { restoreTarget = b.name },
                        onDelete = {
                            scope.launch {
                                busy = true
                                runCatching { SiteFiles.deleteBackup(b.name) }
                                    .onSuccess { ToastBus.show(context, "已删除"); reload() }
                                    .onFailure { ToastBus.show(context, it.message ?: "删除失败") }
                                busy = false
                            }
                        },
                        onDownload = { pendingDownload = b.name; downloadLauncher.launch(b.name) }
                    )
                }
            }
        }
    }

    if (restoreTarget != null) {
        ConfirmDialog(
            title = "还原备份", danger = true, confirmText = "还原",
            message = "将用「$restoreTarget」覆盖当前站点全部内容，且该操作不可撤销。确定继续？",
            onConfirm = {
                val name = restoreTarget!!
                restoreTarget = null
                scope.launch {
                    busy = true
                    progressText = "还原中…"
                    runCatching { SiteFiles.restoreBackup(name) }
                        .onSuccess { ToastBus.show(context, "已还原，覆盖 $it 个文件", long = true); reload() }
                        .onFailure { ToastBus.show(context, it.message ?: "还原失败") }
                    busy = false
                }
            },
            onDismiss = { restoreTarget = null }
        )
    }
    if (busy) ProgressOverlay(progress, progressText.ifEmpty { "处理中…" })
}

@Composable
private fun BackupRow(b: SiteBackup, onRestore: () -> Unit, onDelete: () -> Unit, onDownload: () -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cardBg).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(androidx.compose.material.icons.Icons.Rounded.Archive, contentDescription = null, tint = c.teal, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.name, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text("${formatBytes(b.size)}　·　${b.timeText()}", color = c.textSecondary, fontSize = 12.sp)
        }
        IconButton(onClick = onDownload) { Icon(androidx.compose.material.icons.Icons.Rounded.Download, contentDescription = "下载", tint = c.teal) }
        IconButton(onClick = onRestore) { Icon(androidx.compose.material.icons.Icons.Rounded.Restore, contentDescription = "还原", tint = c.teal) }
        IconButton(onClick = onDelete) { Icon(androidx.compose.material.icons.Icons.Rounded.Delete, contentDescription = "删除", tint = c.danger) }
    }
    Spacer(Modifier.height(8.dp))
}
