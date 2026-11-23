package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.aurora.chat.ui.components.blockPointerPassThrough
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.aurora.chat.ErrorReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button

// ── 配色（与工作区统一:主色蓝、危险红、中性灰;不引入其它色相）──
private val BkBlue = Color(0xFF1E40AF)   // 品牌深蓝(与 MainActivity 等主操作一致)
private val BkRed = Color(0xFFDC2626)    // 仅用于删除(危险)
private val BkGray = com.aurora.chat.ui.theme.appTextSecondary()   // 中性:次要图标 / 副文字
private val BkBg = com.aurora.chat.ui.theme.appCardBg()

/** 备份保存位置:internal=应用内部存储(filesDir);external=手机外部存储(app 专属目录,可被文件管理器访问)。 */
const val LOC_INTERNAL = "internal"
const val LOC_EXTERNAL = "external"

/**
 * 备份元数据。每个备份是一个独立目录 `workspace_backups/backup_<时间戳>/`(内部)
 * 或 `AuroraBackups/backup_<时间戳>/`(外部),内含 `data/`(被备份目录的整份拷贝) 与 `meta.json`。
 */
data class BackupMeta(
    val dir: File,
    val createdAt: Long,
    val type: String,      // "workspace" | "folder" | "unknown"
    val sourceRel: String, // 文件夹备份时的相对路径;整个工作区为 ""
    val sizeBytes: Long,
    val fileCount: Int,
    val location: String = LOC_INTERNAL   // 该备份保存在哪里
)

/** 内部备份根目录:位于 filesDir 下,与 ai_files 同级,绝不进入工作区沙盒(不会被工作区列表显示)。 */
fun backupRootDir(ctx: Context): File =
    File(ctx.filesDir, "workspace_backups").also { if (!it.exists()) it.mkdirs() }

/**
 * 外部备份根目录:位于手机外部存储的 app 专属目录 `Android/data/<pkg>/files/AuroraBackups`,
 * 无需任何权限,且能被文件管理器访问。随应用卸载被清除(与内部一致);
 * 若需跨卸载保留 / 导出到电脑,后续可接 SAF / MediaStore 写入公共 Download。
 * 外部存储不可用时返回 null(由调用方提示)。
 */
fun externalBackupRootDir(ctx: Context): File? = ctx.getExternalFilesDir("AuroraBackups")

private fun measureDir(dir: File): Pair<Long, Int> {
    var size = 0L
    var count = 0
    dir.walkTopDown().forEach { f ->
        if (f.isFile) { size += f.length(); count++ }
    }
    return size to count
}

/** 扫描某个根目录下的备份,写入 location 标记。 */
private fun scanBackups(root: File?, location: String): List<BackupMeta> {
    if (root == null || !root.exists()) return emptyList()
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
        BackupMeta(dir, createdAt, type, sourceRel, sizeBytes, fileCount, location)
    } ?: emptyList()).sortedByDescending { it.createdAt }
}

fun listBackupsInternal(ctx: Context): List<BackupMeta> = scanBackups(backupRootDir(ctx), LOC_INTERNAL)
fun listBackupsExternal(ctx: Context): List<BackupMeta> = scanBackups(externalBackupRootDir(ctx), LOC_EXTERNAL)
/** 兼容 AI 工具(restore_backup 仅感知内部备份);UI 侧用上面的双函数。 */
fun listBackups(ctx: Context): List<BackupMeta> = listBackupsInternal(ctx)

/**
 * 创建备份:把 source 整份拷贝到 <root>/backup_<ts>/data,并写 meta.json(含 location)。
 * location 决定落到内部还是手机外部存储。IO 线程执行。
 */
suspend fun createBackup(
    ctx: Context, source: File, type: String, sourceRel: String, location: String = LOC_INTERNAL
): BackupMeta = withContext(Dispatchers.IO) {
    val root = if (location == LOC_EXTERNAL) {
        externalBackupRootDir(ctx) ?: throw IOException("外部存储不可用(可能未挂载或被禁用)")
    } else backupRootDir(ctx)
    if (!source.exists()) throw IOException("备份来源不存在: ${source.absolutePath}")
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
        put("location", location)
    }
    File(dir, "meta.json").writeText(meta.toString())
    BackupMeta(dir, ts, type, sourceRel, s, c, location)
}

/**
 * 恢复备份:清空目标再写入 data,实现真正的"回退到该备份"。IO 线程执行。
 * 修复项:① 校验 data 存在;② 校验 sourceRel 不含越界路径;③ 校验目标始终在工作区内;
 * ④ 目标目录不存在时先 mkdirs(修复文件夹备份目标已被删除时恢复失败)。
 */
suspend fun restoreBackup(ctx: Context, meta: BackupMeta) = withContext(Dispatchers.IO) {
    val data = File(meta.dir, "data")
    if (!data.exists() || !data.isDirectory) throw IOException("备份数据缺失或已损坏")
    val rel = meta.sourceRel.trim()
    if (rel.contains("..") || rel.startsWith("/") || rel.startsWith("\\"))
        throw SecurityException("备份来源路径非法,已阻止恢复")
    val root = AiChatManager.agentRoot(ctx).canonicalFile
    val target = if (meta.type == "workspace" || rel.isEmpty()) root else File(root, rel).canonicalFile
    if (target != root && !target.path.startsWith(root.path + File.separator))
        throw SecurityException("恢复目标越界,已阻止")
    target.mkdirs()
    target.listFiles()?.forEach { it.deleteRecursively() } // 清空当前内容
    data.copyRecursively(target, overwrite = true)
}

suspend fun deleteBackup(meta: BackupMeta) = withContext(Dispatchers.IO) {
    if (!meta.dir.deleteRecursively()) throw IOException("删除备份失败: ${meta.dir.absolutePath}")
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

    var internalBackups by remember { mutableStateOf(listOf<BackupMeta>()) }
    var externalBackups by remember { mutableStateOf(listOf<BackupMeta>()) }
    var busy by remember { mutableStateOf(false) }
    var saveLocation by remember { mutableStateOf(LOC_INTERNAL) }   // 用户自选保存位置
    var pickMode by remember { mutableStateOf(false) }
    var pickDir by remember { mutableStateOf(root) }
    var confirmRestore by remember { mutableStateOf<BackupMeta?>(null) }
    var confirmDelete by remember { mutableStateOf<BackupMeta?>(null) }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    suspend fun reloadBoth() {
        withContext(Dispatchers.IO) {
            val i = listBackupsInternal(ctx)
            val e = listBackupsExternal(ctx)
            withContext(Dispatchers.Main.immediate) {
                internalBackups = i
                externalBackups = e
            }
        }
    }
    /** 统一封装:busy 守卫 + try/catch + 失败日志 + 完成后刷新双列表。 */
    fun runOp(label: String, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                block()
                reloadBoth()
            } catch (e: Exception) {
                ErrorReporter.warn("Backup", "$label 失败: ${e.message}")
                toast("$label 失败: ${e.message}")
            } finally { busy = false }
        }
    }
    fun relOf(dir: File): String {
        val r = dir.absolutePath.removePrefix(root.absolutePath).trimStart('/')
        return if (r.isEmpty()) "" else r
    }

    LaunchedEffect(Unit) { reloadBoth() }

    val folderList = remember(pickDir) {
        (pickDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList())
    }
    val internalPath = remember { backupRootDir(ctx).absolutePath }
    val extPath = remember { externalBackupRootDir(ctx)?.absolutePath ?: "（外部存储不可用）" }

    // 工作区本身已全屏覆盖,这里再叠一层独立全屏;不消费手势以外的事件。
    Column(
        Modifier.fillMaxSize().background(BkBg)
            // 全屏浮层：吃掉空白区域触摸，避免穿透到下层聊天界面
            .blockPointerPassThrough()
    ) {
        // ═══ 顶栏（与工作区同款,不嵌入任何面板） ═══
        Row(
            Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = noRipple, indication = null) { onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = com.aurora.chat.ui.theme.appTextPrimary()) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("备份与恢复", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Text(
                    "可存到应用内部或手机外部存储,随时回退",
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
                colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appSurface())
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AddToDrive, null, tint = BkBlue, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("创建新备份", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                    }
                    Spacer(Modifier.height(12.dp))

                    // ── 保存位置自选 ──
                    Text("保存位置", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocChip(
                            selected = saveLocation == LOC_INTERNAL,
                            label = "应用内部存储",
                            icon = Icons.Filled.Storage,
                            modifier = Modifier.weight(1f)
                        ) { if (!busy) saveLocation = LOC_INTERNAL }
                        LocChip(
                            selected = saveLocation == LOC_EXTERNAL,
                            label = "手机外部存储",
                            icon = Icons.Filled.SdCard,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (busy) return@LocChip
                            if (externalBackupRootDir(ctx) == null) { toast("外部存储不可用,已保持内部存储"); saveLocation = LOC_INTERNAL }
                            else saveLocation = LOC_EXTERNAL
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (saveLocation == LOC_EXTERNAL) "将保存到: $extPath" else "将保存到: $internalPath（随应用卸载清除）",
                        fontSize = 11.sp, color = BkGray, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))

                    // 备份整个工作区
                    val onBackupWorkspace: () -> Unit = {
                        runOp("备份整个工作区") {
                            createBackup(ctx, root, "workspace", "", saveLocation)
                            toast("已备份整个工作区")
                        }
                    }
                    Button(onClick = onBackupWorkspace, enabled = !busy,
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
                        Text("选择文件夹备份", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
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
                                                Text(d.name, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                Spacer(Modifier.weight(1f))
                                                Icon(Icons.Filled.ChevronRight, null, tint = BkGray, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                val onBackupFolder: () -> Unit = {
                                    val rel = relOf(pickDir)
                                    runOp("备份文件夹") {
                                        createBackup(ctx, pickDir, "folder", rel, saveLocation)
                                        toast("已备份文件夹: ai_files/$rel")
                                    }
                                }
                                Button(onClick = onBackupFolder, enabled = !busy,
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

            // ── 恢复入口①:应用内部存储的备份 ──
            BackupSection(
                title = "① 从应用内部存储恢复",
                icon = Icons.Filled.Storage,
                backups = internalBackups,
                timeFmt = timeFmt,
                subtitle = "位于 $internalPath",
                onRestore = { confirmRestore = it },
                onDelete = { confirmDelete = it }
            )

            Spacer(Modifier.height(14.dp))

            // ── 恢复入口②:手机外部存储的备份 ──
            BackupSection(
                title = "② 从手机外部存储恢复",
                icon = Icons.Filled.SdCard,
                backups = externalBackups,
                timeFmt = timeFmt,
                subtitle = if (externalBackups.isEmpty()) "位于 $extPath（可用文件管理器访问,随应用卸载清除）" else "位于 $extPath",
                onRestore = { confirmRestore = it },
                onDelete = { confirmDelete = it }
            )

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
                        "替换为「${if (m.createdAt > 0) timeFmt.format(Date(m.createdAt)) else "未知时间"}」的备份" +
                        "（来源:${if (m.location == LOC_EXTERNAL) "手机外部存储" else "应用内部存储"}）。\n" +
                        "当前内容会被覆盖,无法自动撤销。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = null
                        runOp("恢复") { restoreBackup(ctx, m); toast("已恢复: ${typeLabel(m)}") }
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
                        runOp("删除") { deleteBackup(m); toast("已删除备份") }
                    }
                ) { Text("确认删除", color = BkRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消", color = BkGray) }
            }
        )
    }
}

/** 保存位置选择小卡片。 */
@Composable
private fun LocChip(selected: Boolean, label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val sel = selected
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (sel) Color(0xFFEFF3FF) else com.aurora.chat.ui.theme.appCardBg())
            .then(if (sel) Modifier.border(1.dp, BkBlue, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (sel) BkBlue else BkGray, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = if (sel) BkBlue else com.aurora.chat.ui.theme.appTextSecondary(), fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * 单个恢复来源的备份列表(应用内部 / 手机外部 各一个,相互独立)。
 * 每个备份项均支持一键恢复与删除。
 */
@Composable
private fun BackupSection(
    title: String,
    icon: ImageVector,
    backups: List<BackupMeta>,
    timeFmt: SimpleDateFormat,
    subtitle: String,
    onRestore: (BackupMeta) -> Unit,
    onDelete: (BackupMeta) -> Unit
) {
    val noRipple = remember { MutableInteractionSource() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = BkBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
        Spacer(Modifier.weight(1f))
        Text("共 ${backups.size} 个", fontSize = 12.sp, color = BkGray)
    }
    Spacer(Modifier.height(4.dp))
    Text(subtitle, fontSize = 11.sp, color = BkGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(10.dp))
    if (backups.isEmpty()) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg())
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.FolderOpen, null, tint = Color(0xFFD1D5DB), modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text("该位置还没有备份", fontSize = 13.sp, color = BkGray)
            }
        }
    } else {
        backups.forEach { meta ->
            Card(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface()),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (meta.type == "workspace" || meta.sourceRel.isEmpty()) Icons.Filled.Storage else Icons.Filled.Folder,
                            null, tint = BkBlue, modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(typeLabel(meta), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
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
                                onClick = { onRestore(meta) },
                                modifier = Modifier.weight(1f).height(40.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BkBlue)
                            ) {
                                Icon(Icons.Filled.Restore, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("恢复", fontSize = 13.sp)
                            }
                        OutlinedButton(
                            onClick = { onDelete(meta) },
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
}
