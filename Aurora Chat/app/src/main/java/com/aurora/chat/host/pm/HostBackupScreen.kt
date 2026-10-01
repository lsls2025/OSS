package com.aurora.chat.host.pm

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.host.HostBackup
import com.aurora.chat.host.HostFiles
import kotlinx.coroutines.launch
import java.io.File

private fun toast(ctx: Context, msg: String, long: Boolean = false) =
    Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

/** 备份：照搬「项目管理」BackupScreen，走 HostFiles 备份接口。 */
@Composable
fun HostBackupScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var backups by remember { mutableStateOf<List<HostBackup>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var restoreTarget by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<String?>(null) }

    fun reload() {
        loading = true; error = null
        scope.launch {
            runCatching { HostFiles.listBackups() }
                .onSuccess { backups = it; loading = false }
                .onFailure { error = it.message ?: "加载失败"; loading = false }
        }
    }
    LaunchedEffect(Unit) { reload() }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val name = pendingDownload ?: return@rememberLauncherForActivityResult
        if (uri == null) { pendingDownload = null; return@rememberLauncherForActivityResult }
        scope.launch {
            busy = true; progressText = "下载中…"
            val tmp = File(context.cacheDir, "bk_${System.currentTimeMillis()}_$name")
            runCatching {
                HostFiles.downloadBackup(name, tmp)
                context.contentResolver.openOutputStream(uri)?.use { os -> tmp.inputStream().use { it.copyTo(os) } }
            }.onSuccess { toast(context, "下载完成：$name", long = true) }
                .onFailure { toast(context, it.message ?: "下载失败") }
            runCatching { tmp.delete() }
            busy = false; progress = 0f; pendingDownload = null
        }
    }

    Column(Modifier.fillMaxSize().background(c.appBg)) {
        TopBar(title = "站点备份", onBack = onClose, actions = {
            IconButton(onClick = { reload() }) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = c.textPrimary) }
        })
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("可用备份额度", color = c.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            AppButton(text = if (loading) "加载中…" else "创建备份", onClick = {
                scope.launch {
                    busy = true; progressText = "备份中…"
                    runCatching { HostFiles.createBackup() }
                        .onSuccess { toast(context, "已创建备份：${it.name}", long = true); reload() }
                        .onFailure { toast(context, it.message ?: "备份失败") }
                    busy = false
                }
            }, enabled = !loading)
        }

        when {
            loading -> LoadingState()
            error != null -> ErrorState(error ?: "加载失败") { reload() }
            backups.isEmpty() -> EmptyState("还没有备份", Icons.Rounded.Backup)
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(backups, key = { it.id }) { b ->
                    BackupRow(b,
                        onRestore = { restoreTarget = b.name },
                        onDelete = {
                            scope.launch {
                                busy = true
                                runCatching { HostFiles.deleteBackup(b.name) }
                                    .onSuccess { toast(context, "已删除"); reload() }
                                    .onFailure { toast(context, it.message ?: "删除失败") }
                                busy = false
                            }
                        },
                        onDownload = { pendingDownload = b.name; downloadLauncher.launch(b.name) }
                    )
                    Spacer(Modifier.height(8.dp))
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
                    busy = true; progressText = "还原中…"
                    runCatching { HostFiles.restoreBackup(name) }
                        .onSuccess { toast(context, "已还原，覆盖 $it 个文件", long = true); reload() }
                        .onFailure { toast(context, it.message ?: "还原失败") }
                    busy = false
                }
            },
            onDismiss = { restoreTarget = null }
        )
    }
    if (busy) ProgressOverlay(progress, progressText.ifEmpty { "处理中…" })
}

@Composable
private fun BackupRow(b: HostBackup, onRestore: () -> Unit, onDelete: () -> Unit, onDownload: () -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cardBg).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Archive, contentDescription = null, tint = c.teal, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.name, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text("${formatBytes(b.size)}　·　${b.timeText()}", color = c.textSecondary, fontSize = 12.sp)
        }
        IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, contentDescription = "下载", tint = c.teal) }
        IconButton(onClick = onRestore) { Icon(Icons.Rounded.Restore, contentDescription = "还原", tint = c.teal) }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "删除", tint = c.danger) }
    }
}
