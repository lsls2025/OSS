package com.aurora.chat.ui.chat

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── 配色（与工作区统一:主色蓝、危险红、中性灰;不引入其它色相）──
private val BkBlue = Color(0xFF1E40AF)   // 品牌深蓝(与 MainActivity 等主操作一致)
private val BkRed = Color(0xFFDC2626)    // 仅用于删除(危险)
private val BkGray = Color(0xFF6B7280)   // 中性:次要图标 / 副文字
private val BkBg = Color(0xFFF3F4F6)

/**
 * 备份元数据。每个备份是一个独立目录 `workspace_backups/backup_<时间戳>/`，
 * 内含 `data/`(被备份目录的整份拷贝) 与 `meta.json`(基础信息)。
 */
data class BackupMeta(
    val dir: File,
    val createdAt: Long,
    val type: String,      // "workspace" | "folder" | "unknown"
    val sourceRel: String, // 文件夹备份时的相对路径;整个工作区为 ""
    val sizeBytes: Long,
    val fileCount: Int
)

/** 备份根目录:位于 filesDir 下,与 ai_files 同级,绝不进入工作区沙盒(不会被工作区列表显示)。 */
fun backupRootDir(ctx: Context): File =
    File(ctx.filesDir, "workspace_backups").also { if (!it.exists()) it.mkdirs() }

private fun measureDir(dir: File): Pair<Long, Int> {
    var size = 0L
    var count = 0
    dir.walkTopDown().forEach { f ->
        if (f.isFile) { size += f.length(); count++ }
    }
    return size to count
}

/** 枚举所有备份,按时间倒序(最新在前)。 */
fun listBackups(ctx: Context): List<BackupMeta> {
    val root = backupRootDir(ctx)
    return (root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
        val metaFile = File(dir, "meta.json")
        var createdAt = 0L
        var type = "unknown"
        var sourceRel = ""
        var sizeBytes = 0L
        var fileCount = 0
        if (metaFile.exists()) {
            try {
                val j = JSONObject(metaFile.readText())
                createdAt = j.optLong("createdAt", 0L)
                type = j.optString("type", "unknown")
                sourceRel = j.optString("sourceRel", "")
                sizeBytes = j.optLong("sizeBytes", 0L)
                fileCount = j.optInt("fileCount", 0)
            } catch (_: Exception) { /* 损坏则走兜底 */ }
        }
        if (createdAt == 0L) {
            val n = dir.name.removePrefix("backup_")
            runCatching { createdAt = n.toLong() }
        }
        val data = File(dir, "data")
        if (sizeBytes == 0L || fileCount == 0) {
            val (s, c) = measureDir(data)
            if (sizeBytes == 0L) sizeBytes = s
            if (fileCount == 0) fileCount = c
        }
        BackupMeta(dir, createdAt, type, sourceRel, sizeBytes, fileCount)
    } ?: emptyList()).sortedByDescending { it.createdAt }
}

/** 创建备份:把 source 整份拷贝到 backup/data,并写 meta.json。IO 线程执行。 */
suspend fun createBackup(ctx: Context, source: File, type: String, sourceRel: String): BackupMeta =
    withContext(Dispatchers.IO) {
        val root = backupRootDir(ctx)
        val ts = System.currentTimeMillis()
        val dir = File(root, "backup_$ts")
        val data = File(dir, "data")
        data.mkdirs()
        source.copyRecursively(data, overwrite = true)
        val (s, c) = measureDir(data)
        val meta = JSONObject().apply {
            put("createdAt", ts)
            put("type", type)
            put("sourceRel", sourceRel)
            put("sizeBytes", s)
            put("fileCount", c)
        }
        File(dir, "meta.json").writeText(meta.toString())
        BackupMeta(dir, ts, type, sourceRel, s, c)
    }

/** 恢复备份:清空目标再写入 data,实现真正的"回退到该备份"。IO 线程执行。 */
suspend fun restoreBackup(ctx: Context, meta: BackupMeta) = withContext(Dispatchers.IO) {
    val root = AiChatManager.agentRoot(ctx)
    val target = if (meta.type == "workspace" || meta.sourceRel.isEmpty()) root else File(root, meta.sourceRel)
    target.listFiles()?.forEach { it.deleteRecursively() } // 清空当前内容
    File(meta.dir, "data").copyRecursively(target, overwrite = true)
}

suspend fun deleteBackup(meta: BackupMeta) = withContext(Dispatchers.IO) {
    meta.dir.deleteRecursively()
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f MB", bytes / (1024f * 1024f))
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024f * 1024f * 1024f))
    }
}

private fun typeLabel(meta: BackupMeta): String =
    if (meta.type == "workspace" || meta.sourceRel.isEmpty()) "整个工作区" else "文件夹: ai_files/${meta.sourceRel}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { AiChatManager.agentRoot(ctx) }
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val noRipple = remember { MutableInteractionSource() }

    var backups by remember { mutableStateOf(listOf<BackupMeta>()) }
    var busy by remember { mutableStateOf(false) }
    var pickMode by remember { mutableStateOf(false) }
    var pickDir by remember { mutableStateOf(root) }
    var confirmRestore by remember { mutableStateOf<BackupMeta?>(null) }
    var confirmDelete by remember { mutableStateOf<BackupMeta?>(null) }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    fun reload() { scope.launch { backups = listBackups(ctx) } }
    fun relOf(dir: File): String {
        val r = dir.absolutePath.removePrefix(root.absolutePath).trimStart('/')
        return if (r.isEmpty()) "" else r
    }

    LaunchedEffect(Unit) { reload() }

    val folderList = remember(pickDir) {
        (pickDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList())
    }

    // 工作区本身已全屏覆盖,这里再叠一层独立全屏;不消费手势以外的事件。
    Column(Modifier.fillMaxSize().background(BkBg)) {
        // ═══ 顶栏（与工作区同款,不嵌入任何面板） ═══
        Row(
            Modifier.fillMaxWidth().background(Color.White).statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) { onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937)) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("备份与恢复", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Text(
                    "备份存于工作区之外,可随时回退",
                    fontSize = 11.sp, color = BkGray, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ═══ 主体(可滚动) ═══
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            // ── 创建新备份 ──
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AddToDrive, null, tint = BkBlue, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("创建新备份", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (busy) return@Button
                            scope.launch {
                                busy = true
                                try {
                                    createBackup(ctx, root, "workspace", "")
                                    reload()
                                    toast("已备份整个工作区")
                                } finally { busy = false }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BkBlue)
                    ) {
                        Icon(Icons.Filled.Backup, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "备份中…" else "备份整个工作区", fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    // 选择文件夹备份
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(interactionSource = noRipple, indication = null) { pickMode = !pickMode }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.FolderCopy, null, tint = BkBlue, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("选择文件夹备份", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (pickMode) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            null, tint = BkGray, modifier = Modifier.size(20.dp)
                        )
                    }
                    if (pickMode) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = BkBg)
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                // 面包屑
                                val rel = relOf(pickDir)
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "ai_files",
                                        fontSize = 12.sp, color = if (rel.isEmpty()) BkBlue else BkGray,
                                        fontWeight = if (rel.isEmpty()) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.clickable(interactionSource = noRipple, indication = null) {
                                            if (rel.isNotEmpty()) pickDir = root
                                        }
                                    )
                                    if (rel.isNotEmpty()) {
                                        val segs = rel.split("/")
                                        var acc = ""
                                        segs.forEach { seg ->
                                            acc = if (acc.isEmpty()) seg else "$acc/$seg"
                                            Text(" / ", fontSize = 12.sp, color = BkGray)
                                            val cur = acc
                                            Text(
                                                seg, fontSize = 12.sp,
                                                color = if (cur == rel) BkBlue else BkGray,
                                                fontWeight = if (cur == rel) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.clickable(interactionSource = noRipple, indication = null) {
                                                    if (cur != rel) pickDir = File(root, cur)
                                                }
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                if (folderList.isEmpty()) {
                                    Text("此目录下没有子文件夹", fontSize = 12.sp, color = BkGray)
                                } else {
                                    LazyColumn(
                                        Modifier.fillMaxWidth().heightIn(max = 200.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(folderList, key = { it.absolutePath }) { d ->
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .clickable(interactionSource = noRipple, indication = null) { pickDir = d }
                                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Filled.Folder, null, tint = BkBlue, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(d.name, fontSize = 13.sp, color = Color(0xFF374151))
                                                Spacer(Modifier.weight(1f))
                                                Icon(Icons.Filled.ChevronRight, null, tint = BkGray, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        if (busy) return@Button
                                        val rel = relOf(pickDir)
                                        scope.launch {
                                            busy = true
                                            try {
                                                createBackup(ctx, pickDir, "folder", rel)
                                                reload()
                                                toast("已备份文件夹: ai_files/$rel")
                                            } finally { busy = false }
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BkBlue)
                                ) {
                                    Icon(Icons.Filled.Backup, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (busy) "备份中…" else "备份此文件夹: ai_files/${relOf(pickDir)}",
                                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 备份列表 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Inventory2, null, tint = BkBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("备份列表", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.weight(1f))
                Text("共 ${backups.size} 个", fontSize = 12.sp, color = BkGray)
            }
            Spacer(Modifier.height(10.dp))
            if (backups.isEmpty()) {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, tint = Color(0xFFD1D5DB), modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("还没有任何备份", fontSize = 13.sp, color = BkGray)
                        Text("点击上方按钮创建第一个备份", fontSize = 12.sp, color = Color(0xFFC4C8CF))
                    }
                }
            } else {
                backups.forEach { meta ->
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (meta.type == "workspace" || meta.sourceRel.isEmpty()) Icons.Filled.Storage else Icons.Filled.Folder,
                                    null,
                                    tint = BkBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(typeLabel(meta), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "备份时间: ${if (meta.createdAt > 0) timeFmt.format(Date(meta.createdAt)) else "未知"}",
                                        fontSize = 12.sp, color = BkGray
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "${formatSize(meta.sizeBytes)} · ${meta.fileCount} 个文件",
                                        fontSize = 12.sp, color = BkGray
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { confirmRestore = meta },
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BkBlue)
                                ) {
                                    Icon(Icons.Filled.Restore, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("恢复", fontSize = 13.sp)
                                }
                                OutlinedButton(
                                    onClick = { confirmDelete = meta },
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BkRed)
                                ) {
                                    Icon(Icons.Filled.DeleteOutline, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("删除", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // ── 恢复确认 ──
    if (confirmRestore != null) {
        val m = confirmRestore!!
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("恢复到此备份?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将把工作区${if (m.type == "workspace" || m.sourceRel.isEmpty()) "整个内容" else "文件夹 ai_files/${m.sourceRel}" }" +
                        "替换为「${if (m.createdAt > 0) timeFmt.format(Date(m.createdAt)) else "未知时间"}」的备份。\n" +
                        "当前内容会被覆盖,无法自动撤销。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = null
                        if (busy) return@TextButton
                        scope.launch {
                            busy = true
                            try {
                                restoreBackup(ctx, m)
                                toast("已恢复: ${typeLabel(m)}")
                            } finally { busy = false }
                        }
                    }
                ) { Text("确认恢复", color = BkBlue) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = null }) { Text("取消", color = BkGray) }
            }
        )
    }

    // ── 删除确认 ──
    if (confirmDelete != null) {
        val m = confirmDelete!!
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除该备份?", fontWeight = FontWeight.Bold) },
            text = { Text("备份「${if (m.createdAt > 0) timeFmt.format(Date(m.createdAt)) else "未知时间"}」将被永久删除,无法找回。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        scope.launch {
                            deleteBackup(m)
                            reload()
                            toast("已删除备份")
                        }
                    }
                ) { Text("确认删除", color = BkRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消", color = BkGray) }
            }
        )
    }
}
