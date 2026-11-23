package com.aurora.chat.ui.chat

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderDelete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.components.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * 「查看所有改动」全屏界面(独立全屏,绝不嵌套):
 * 列出本条 AI 消息会话内改动的全部文件(新建/修改/删除/重命名/复制/删除文件夹),
 * 点卡片进入全屏 diff 视图(红删绿增 + 行号 + 未改动折叠),可精确定位 AI 改了哪一行;
 * 全屏视图内可「打开原文件」跳转真实文件编辑器(工作区根 + FileChange.path 定位)。
 * 数据源为消息内持久化的 ai_file_changes JSON 字符串,由 AiChatManager 在工具执行时采集。
 */
@Composable
fun AiChangesScreen(
    changesJson: String,
    onBack: () -> Unit,
    onChangesUpdated: (String) -> Unit = {},
    focusPath: String? = null,
    /** focusPath 命中时是否**直接打开该条改动的详情**（而非停在列表滚动定位）。
     *  交付总结里点击已被删除的产物即走此路径：文件已不存在，唯一有意义的目的地就是它的改动记录。 */
    openDetail: Boolean = false,
    /** 对话基线快照键(由上层传 AiConversationSession.storageKey()),供二进制/截断场景兜底退回 */
    baselineKey: String = ""
) {
    // 可变改动列表:退回成功后更新对应项的 reverted 标记并回传新 JSON,由上层写回消息持久化
    var changes by remember(changesJson) { mutableStateOf(AiChatManager.parseFileChanges(changesJson)) }
    val ctx = LocalContext.current
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    // ──「退回本轮对话」：只退【本条回复 / 本轮任务】携带的文件改动，绝不影响其它轮次 ──
    var showConvRevertConfirm by remember { mutableStateOf(false) }
    var convReverting by remember { mutableStateOf(false) }
    fun revertRound() {
        if (changes.isEmpty()) {
            Toast.makeText(ctx, "没有可退回的改动", Toast.LENGTH_SHORT).show()
            showConvRevertConfirm = false
            return
        }
        convReverting = true
        scope.launch(Dispatchers.IO) {
            val result = revertFileChanges(ctx, changes) { rel -> baselineTextForPath(ctx, baselineKey, rel) }
            withContext(Dispatchers.Main) {
                convReverting = false
                showConvRevertConfirm = false
                val fail = result.count { !it.value }
                if (fail == 0) Toast.makeText(ctx, "已退回本轮全部改动", Toast.LENGTH_SHORT).show()
                else Toast.makeText(ctx, "已退回 ${result.size - fail} 处，$fail 处失败（详见日志）", Toast.LENGTH_SHORT).show()
                // 标记本条回复全部改动为已退回并写回消息持久化（仅本条，不波及其它回复）
                changes = changes.map { it.copy(reverted = true) }
                onChangesUpdated(AiChatManager.serializeFileChanges(changes))
            }
        }
    }

    // ── 全屏 diff 视图 / 文件夹内容视图：先声明，定位副作用命中时可直接打开对应详情 ──
    var viewing by remember { mutableStateOf<AiChatManager.FileChange?>(null) }
    var viewingFolder by remember { mutableStateOf<AiChatManager.FileChange?>(null) }

    // ── 外部定位:删除类产物点击跳转时,滚动到目标改动卡片并高亮被删除文件;
    //    openDetail=true(文件已从工作区消失)时直接进入该条改动的详情,不再让用户停在一堆记录里自己找 ──
    val listState = rememberLazyListState()
    var highlightPath by remember(focusPath) { mutableStateOf("") }
    // 【红框自动消退】高亮原来是"设了就一直留着"，从详情/预览返回后红框会长期挂在那里（用户反馈不好看）。
    // 现在：只要**没有**停在子视图（diff / 文件夹内容）里，1.5 秒后就把高亮清掉；
    // 进入子视图期间不清（这样返回列表时还看得到它在哪），返回后重新计时 → 1.5 秒后淡出。
    val hlAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (highlightPath.isNotEmpty()) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(500)
    )
    LaunchedEffect(highlightPath, viewing, viewingFolder) {
        if (highlightPath.isNotEmpty() && viewing == null && viewingFolder == null) {
            kotlinx.coroutines.delay(1500)
            highlightPath = ""
        }
    }
    LaunchedEffect(focusPath, changes, openDetail) {
        val fp = focusPath?.trim()?.replace('\\', '/')?.trim('/') ?: return@LaunchedEffect
        val idx = changes.indexOfFirst { c ->
            val cp = c.path.trim().replace('\\', '/').trim('/')
            cp == fp || cp.endsWith("/$fp") || fp.endsWith("/$cp") ||
                (cp.substringAfterLast('/').isNotBlank() && cp.substringAfterLast('/') == fp.substringAfterLast('/'))
        }
        if (idx >= 0) {
            val c = changes[idx]
            highlightPath = c.path
            // 无论是否直接开详情，列表都先滚到目标并高亮：从详情返回时正好停在它上面
            listState.animateScrollToItem(idx)
            if (openDetail) {
                // 目录级操作且有内容清单 → 文件夹内容视图;其余 → 全屏 diff
                if (isDirChangeOp(c.op) && parseChildren(c.children).items.isNotEmpty()) viewingFolder = c
                else viewing = c
            }
        }
    }
    // ── 「打开原文件」真实文件编辑器(与 WorkspaceFileManagerScreen 的 editingFile + CodeEditor 同一套用法) ──
    var editingFile by remember { mutableStateOf<File?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var editLoading by remember { mutableStateOf(false) }
    // ── 删除文件夹子文件备份预览:文本类读备份内容进 CodeEditor 只读;二进制/超大/空文件进元信息页 ──
    var previewFile by remember { mutableStateOf<File?>(null) }
    var previewContent by remember { mutableStateOf("") }
    var previewLoading by remember { mutableStateOf(false) }
    var previewBinary by remember { mutableStateOf(false) }
    var previewMetaName by remember { mutableStateOf("") }
    var previewMetaReason by remember { mutableStateOf("") }
    var previewMetaSize by remember { mutableStateOf(0L) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    /** 工作区相对路径 → 真实文件:拼工作区根定位;校验不逃出沙盒,非法/越界返回 null */
    fun resolveChangeRel(relRaw: String): File? {
        val rel = relRaw.trim().replace('\\', '/').trim('/')
        if (rel.isBlank() || rel.split('/').any { it == ".." }) { toast("路径非法,无法定位"); return null }
        val root = AiChatManager.agentRoot(ctx)
        val f = File(root, rel)
        val rootAbs = root.absolutePath.trimEnd('/', '\\') + File.separator
        if (!f.absolutePath.startsWith(rootAbs)) { toast("路径越界,已拦截"); return null }
        return f
    }

    /** FileChange.path 是工作区相对路径:拼工作区根定位真实文件;校验不逃出沙盒,非法/越界返回 null */
    fun resolveChangeFile(c: AiChatManager.FileChange): File? = resolveChangeRel(c.path)

    /** 按相对路径打开真实文件编辑器(供文件夹内容视图点击子项使用):大小护栏 + IO 读取,加载完成后叠加 CodeEditor */
    fun openFileByRel(relRaw: String) {
        val f = resolveChangeRel(relRaw) ?: return
        if (!f.exists()) { toast("文件不存在(可能已删除或仅存在于记录中)"); return }
        if (f.isDirectory) { toast("这是文件夹,点击列表内文件即可打开"); return }
        if (f.length() > 2_000_000L) { toast("文件过大(超过 2MB),不支持编辑"); return }
        editingFile = f
        editLoading = true
        scope.launch(Dispatchers.IO) {
            val content = runCatching { f.readText() }.getOrElse { "（无法读取:${it.message ?: "未知错误"}）" }
            withContext(Dispatchers.Main) { editingContent = content; editLoading = false }
        }
    }

    /** 打开真实文件编辑器:大小护栏 + IO 读取,加载完成后叠加 CodeEditor(参考 WorkspaceFileManagerScreen.openEditor) */
    fun openOriginal(c: AiChatManager.FileChange) {
        val f = resolveChangeFile(c) ?: return
        if (!f.exists()) {
            // 已删除的文件工作区已不存在:不硬切编辑器报「未找到」,引导用户走退回
            toast(if (c.op == "deleted" || c.op == "deleted_folder") "文件已被删除,如需恢复请点击「退回」" else "文件不存在(可能已删除或仅存在于记录中)")
            return
        }
        openFileByRel(c.path)
    }

    /** 整体退回:把已删除文件夹的整份备份复制回原路径(颗粒化退回之「整个退回」) */
    fun revertFolderWhole(v: AiChatManager.FileChange) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val root = AiChatManager.agentRoot(ctx)
                val rel = v.path.trim().replace('\\', '/').trim('/')
                if (rel.isBlank() || rel.split('/').any { it == ".." }) throw IllegalStateException("路径非法")
                val dest = File(root, rel)
                val rootAbs = root.absolutePath.trimEnd('/', '\\') + File.separator
                if (!dest.absolutePath.startsWith(rootAbs)) throw IllegalStateException("路径越界")
                if (v.backup.isBlank()) throw IllegalStateException("该文件夹删除时未备份,无法整体退回")
                val bk = File(v.backup)
                if (!bk.isDirectory) throw IllegalStateException("备份已丢失,无法退回")
                dest.parentFile?.mkdirs()
                bk.copyRecursively(dest, overwrite = true)
                Pair(true, "已退回:恢复整个文件夹 ${v.path}")
            }.getOrElse { Pair(false, "退回失败:${it.message}") }
            withContext(Dispatchers.Main) {
                if (result.first) {
                    changes = changes.map { if (it.path == v.path) it.copy(reverted = true) else it }
                    onChangesUpdated(AiChatManager.serializeFileChanges(changes))
                    viewingFolder = null
                }
                toast(result.second)
            }
        }
    }

    /** 颗粒化退回之单文件:从文件夹备份中恢复指定子项到工作区原相对路径(不整体退回,可逐个挑文件恢复) */
    fun revertFolderChild(v: AiChatManager.FileChange, childRel: String) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                if (v.backup.isBlank()) throw IllegalStateException("该文件夹删除时未备份,无法退回")
                val bk = File(v.backup)
                // 备份根结构 = 被删目录内容直接放根下:子项相对操作目录部分才是备份内相对路径
                val base = v.path.trim().replace('\\', '/').trim('/')
                var inBk = childRel.trim().replace('\\', '/').trim('/')
                if (base.isNotEmpty() && inBk.startsWith(base + "/")) inBk = inBk.removePrefix(base + "/")
                val src = File(bk, inBk)
                if (!src.exists()) throw IllegalStateException("备份中未找到 $childRel")
                val root = AiChatManager.agentRoot(ctx)
                val dest = File(root, childRel)
                val rootAbs = root.absolutePath.trimEnd('/', '\\') + File.separator
                if (!dest.absolutePath.startsWith(rootAbs)) throw IllegalStateException("路径越界")
                dest.parentFile?.mkdirs()
                if (src.isDirectory) src.copyRecursively(dest, overwrite = true) else src.copyTo(dest, overwrite = true)
                Pair(true, "已退回:恢复文件 $childRel")
            }.getOrElse { Pair(false, "退回失败:${it.message}") }
            withContext(Dispatchers.Main) { toast(result.second) }
        }
    }

    /** 展示二进制/超大文件元信息页:文件名 + 大小 + 原因说明(须在主线程调用) */
    fun showPreviewMeta(src: File, reason: String) {
        previewBinary = true
        previewMetaName = src.name
        previewMetaReason = reason
        previewMetaSize = src.length()
        previewFile = src
    }

    /** 删除文件夹子文件预览:children 只记元数据不含正文,内容一律从整目录备份定位读取。
     *  文本类 → CodeEditor 只读预览(超长截断);二进制/超大/空文件 → 元信息页并提示可退回。 */
    fun previewFolderChild(v: AiChatManager.FileChange, childRel: String) {
        val rel = childRel.trim().replace('\\', '/').trim('/')
        if (rel.isBlank()) { toast("路径非法,无法预览"); return }
        if (v.backup.isBlank()) { toast("该文件夹删除时未备份,无法预览内容;如需恢复请点「退回」"); return }
        val bk = File(v.backup)
        if (!bk.isDirectory) { toast("备份已丢失,无法预览;如需恢复请点「退回」"); return }
        // 备份根结构 = 被删目录内容直接放根下:去掉被删文件夹前缀后才是备份内相对路径(与单文件退回定位一致)
        val base = v.path.trim().replace('\\', '/').trim('/')
        var inBk = rel
        if (base.isNotEmpty() && inBk.startsWith(base + "/")) inBk = inBk.removePrefix(base + "/")
        val src = File(bk, inBk)
        val bkAbs = bk.absolutePath.trimEnd('/', '\\') + File.separator
        if (!src.absolutePath.startsWith(bkAbs)) { toast("路径越界,已拦截"); return }
        if (!src.exists() || !src.isFile) { toast("备份中未找到该文件,无法预览"); return }
        val size = src.length()
        // 空文件:无内容可预览
        if (size == 0L) { toast("文件为空,无内容可预览"); return }
        // 超大文件:超出预览上限,直接展示元信息页
        if (size > PREVIEW_MAX_BYTES) { showPreviewMeta(src, "文件过大,无法预览正文"); return }
        previewLoading = true
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                val bytes = src.readBytes()
                if (bytes.contains(0.toByte())) null else String(bytes, Charsets.UTF_8)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                previewLoading = false
                if (text == null) {
                    showPreviewMeta(src, "二进制文件,无法预览正文")
                } else {
                    previewFile = src
                    previewBinary = false
                    previewContent = if (text.length > PREVIEW_TEXT_CAP)
                        text.take(PREVIEW_TEXT_CAP) + "\n\n…(内容过长,仅预览前 $PREVIEW_TEXT_CAP 字符)"
                    else text
                }
            }
        }
    }

    /** 保存编辑器内容(与 WorkspaceFileManagerScreen.saveEditor 一致) */
    suspend fun saveEditor(text: String, closeAfter: Boolean) {
        val f = editingFile ?: return
        editLoading = true
        withContext(Dispatchers.IO) {
            val ok = runCatching { f.writeText(text) }.isSuccess
            withContext(Dispatchers.Main) {
                editLoading = false
                if (ok) {
                    toast(if (closeAfter) "已保存并退出" else "已保存")
                    if (closeAfter) editingFile = null
                } else toast("保存失败")
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            // 消费空区点击,防止穿透到下层 AI 聊天界面(误触返回箭头等导致退出对话)。
            .clickable(interactionSource = noRipple, indication = null) {}
    ) {
        // ── 顶栏 ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937))
            }
            Column(Modifier.weight(1f)) {
                Text("改动详情", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                val deleteCount = changes.count { it.op == "deleted" || it.op == "deleted_folder" }
                Text(
                    if (deleteCount > 0) "共 ${changes.size} 处文件改动 · ${deleteCount} 处删除" else "共 ${changes.size} 处文件改动",
                    fontSize = 11.sp, color = Color(0xFF9CA3AF)
                )
            }
            if (changes.isNotEmpty()) {
                val convColor = if (convReverting) Color(0xFF9CA3AF) else Color(0xFFDC2626)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(convColor.copy(alpha = 0.12f))
                        .clickable(enabled = !convReverting, interactionSource = noRipple, indication = null) { showConvRevertConfirm = true }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Undo, contentDescription = null, tint = convColor, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (convReverting) "退回中…" else "退回本轮对话", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = convColor)
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = {
                val copy = buildString {
                    changes.forEach { c ->
                        append("[${opLabel(c.op)}] ${c.path}")
                        if (c.fromPath.isNotEmpty()) append("  (原 ${c.fromPath})")
                        append("\n")
                    }
                }
                try {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("AI 改动清单", copy))
                    Toast.makeText(ctx, "已复制改动清单", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }) {
                Icon(Icons.Filled.Description, contentDescription = "复制清单", tint = Color(0xFF6B7280))
            }
        }


        // ── 退回本轮对话确认弹窗 ──
        if (showConvRevertConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { if (!convReverting) showConvRevertConfirm = false },
                title = { Text("退回本轮对话？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)) },
                text = {
                    Text(
                        "将退回本条回复里 AI 触碰过的文件改动（仅本轮，不影响其它回复），把涉及的文件还原到本轮动手之前。",
                        fontSize = 13.sp, color = Color(0xFF374151)
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { revertRound() }, enabled = !convReverting) {
                        Text(if (convReverting) "退回中…" else "确认退回", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showConvRevertConfirm = false }, enabled = !convReverting) {
                        Text("取消", color = Color(0xFF6B7280))
                    }
                }
            )
        }

        if (changes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有文件改动记录", fontSize = 13.sp, color = Color(0xFF9CA3AF))
            }
            return@Column
        }

        // ── 改动列表:点卡片进全屏 diff 视图;目录级操作(删除文件夹/解压/压缩/移动目录)有 children 时进文件夹内容视图 ──
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(changes.size) { i ->
                val c = changes[i]
                ChangeCard(
                    c,
                    onClick = {
                        // 目录级操作且有内容清单 → 打开文件夹内容视图(能看里面到底有什么);否则照旧进 diff
                        if (isDirChangeOp(c.op) && parseChildren(c.children).items.isNotEmpty()) viewingFolder = c
                        else viewing = c
                    },
                    // 高亮按 alpha 走：1.5 秒后目标值变 0 → 边框在 500ms 内淡出（不是啪一下消失）
                    highlightAlpha = if (c.path == highlightPath) hlAlpha else 0f
                )
            }
        }
    }

    // ── 全屏文件夹内容视图(删除文件夹/解压/压缩/移动目录等目录级操作) ──
    viewingFolder?.let { v ->
        FolderContentScreen(
            change = v,
            onBack = { viewingFolder = null },
            onOpenChildRel = { rel -> openFileByRel(rel) },
            onPreviewChild = { rel -> previewFolderChild(v, rel) },
            onRevertWhole = { revertFolderWhole(v) },
            onRevertChild = { rel -> revertFolderChild(v, rel) },
            reverted = v.reverted,
        )
    }

    // ── 全屏 diff 视图(覆盖列表,独立全屏不嵌套) ──
    viewing?.let { v ->
        AiDiffScreen(
            change = v, onBack = { viewing = null }, onOpenOriginal = { openOriginal(v) },
            onReverted = {
                // 退回成功:更新列表中的 reverted 标记,序列化后回传上层写回消息 JSON,重进不再可退回
                changes = changes.map { if (it.path == v.path) it.copy(reverted = true) else it }
                onChangesUpdated(AiChatManager.serializeFileChanges(changes))
            }
        )
    }

    // ── 删除文件夹子文件预览:文本 → CodeEditor 只读;二进制/超大 → 元信息页(全屏覆盖) ──
    previewFile?.let { pf ->
        if (previewBinary) {
            PreviewMetaScreen(
                fileName = previewMetaName,
                reason = previewMetaReason,
                size = previewMetaSize,
                onBack = { previewFile = null; previewContent = ""; previewBinary = false }
            )
        } else {
            CodeEditor(
                title = pf.name,
                subtitle = "备份预览 · 已删除文件(只读)",
                initialContent = previewContent,
                isLoading = previewLoading,
                onSave = { _, _ -> },
                onDismiss = { previewFile = null; previewContent = "" },
                readOnly = true
            )
        }
    }

    // ── 「打开原文件」:CodeEditor 全屏编辑器 ──
    if (editingFile != null) {
        CodeEditor(
            title = editingFile?.name ?: "",
            subtitle = "工作区文件",
            initialContent = editingContent,
            isLoading = editLoading,
            onSave = { text, closeAfter -> saveEditor(text, closeAfter) },
            onDismiss = { editingFile = null; editingContent = "" },
            rootFile = editingFile
        )
    }
}

@Composable
private fun ChangeCard(
    c: AiChatManager.FileChange,
    onClick: () -> Unit,
    /** 红框高亮强度（0=不显示）。由上层在离开子视图后 1.5 秒把它降到 0，实现自动消退 */
    highlightAlpha: Float = 0f
) {
    val isDelete = c.op == "deleted" || c.op == "deleted_folder"
    val accent = if (isDelete) Color(0xFFDC2626) else Color(0xFF1E40AF)
    // 目录级/下载类操作没有文本 diff:不做无谓的 LCS,直接展示 children 内容统计(含 N 项/总大小)
    val isDirOp = isDirChangeOp(c.op)
    // 行数统计在后台线程算(diffStats 对超大文件仍有 LCS/集合差开销,避免列表组合期卡顿)
    val stats by produceState(initialValue = Triple(0, 0, 0), c.op, c.oldText, c.newText) {
        value = if (isDirChangeOp(c.op)) Triple(0, 0, 0) else withContext(Dispatchers.Default) { diffStats(c.oldText, c.newText) }
    }
    val childrenInfo = if (isDirOp) remember(c.children) { parseChildren(c.children) } else null
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Column(
        Modifier
            .fillMaxWidth()
            // 删除类卡片底色与普通修改卡片一致(纯白);删除语义由红色徽标/图标/统计承载
            .clip(RoundedCornerShape(12.dp)).background(Color.White)
            // 外部定位(删除类产物点击跳转):目标卡片红框高亮,一眼定位被删除文件
            .then(
                if (highlightAlpha > 0f) Modifier.border(2.dp, Color(0xFFDC2626).copy(alpha = highlightAlpha), RoundedCornerShape(12.dp))
                else Modifier
            )
            // 点卡片进全屏 diff 视图;indication = null 关闭水波纹
            .clickable(interactionSource = noRipple, indication = null, onClick = onClick)
            .padding(12.dp)
    ) {
        // 头部:类型徽标(删除类红色「删除」标签) + 路径 + 增删统计
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(opIcon(c.op), contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                opLabel(c.op),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                c.path.substringAfterLast('/'),
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = Color(0xFF1F2937), modifier = Modifier.weight(1f), maxLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            c.path + (if (c.fromPath.isNotEmpty()) "   ← 原路径:${c.fromPath}" else ""),
            fontSize = 11.sp, color = Color(0xFF9CA3AF), maxLines = 2
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (childrenInfo != null && childrenInfo.items.isNotEmpty()) {
                Text(
                    "含 ${childrenInfo.items.size} 项 · 共 ${fmtSize(childrenInfo.totalSize)}${if (childrenInfo.truncated) " · 已截断" else ""}",
                    fontSize = 11.sp, color = Color(0xFF6B7280), fontWeight = FontWeight.Medium, maxLines = 1
                )
            } else if (isDirOp) {
                Text("目录操作,无文本变化", fontSize = 11.sp, color = Color(0xFF9CA3AF))
            } else {
                if (stats.second > 0) Text("+${stats.second} 行", fontSize = 11.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Medium)
                if (stats.second > 0 && stats.third > 0) Spacer(Modifier.width(8.dp))
                if (stats.third > 0) Text("-${stats.third} 行", fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium)
                if (stats.second == 0 && stats.third == 0) {
                    // 二进制/超大文件删除:无文本可 diff,直接展示文件大小(删除语义由类型徽标承载),不再笼统显示「无文本变化」
                    if (isDelete && c.size > 0L) Text(fmtSize(c.size), fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium)
                    else Text("无文本变化", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(if (isDirOp && childrenInfo?.items?.isNotEmpty() == true) "查看内容" else "查看详情", fontSize = 11.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
            Text(" ›", fontSize = 13.sp, color = Color(0xFF1E40AF))
        }
    }
}

/**
 * 全屏文件夹内容视图:删除文件夹/解压/压缩/移动目录等目录级操作不再只显示「无文本变化」,
 * 而是展示操作涉及的完整内容清单(路径 + 大小 + 文件/文件夹图标 + 层级缩进)。
 * 子项文件真实存在时可点击打开编辑器(deleted_folder 等已不存在的仅展示记录)。
 */
@Composable
private fun FolderContentScreen(
    change: AiChatManager.FileChange,
    onBack: () -> Unit,
    onOpenChildRel: (String) -> Unit,
    onPreviewChild: (String) -> Unit,
    onRevertWhole: () -> Unit,
    onRevertChild: (String) -> Unit,
    reverted: Boolean,
) {
    val info = remember(change.children) { parseChildren(change.children) }
    val isDelete = change.op == "deleted_folder"
    val accent = if (isDelete) Color(0xFFDC2626) else Color(0xFF1E40AF)
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val ctx = LocalContext.current
    // 删除文件夹且有备份 → 提供颗粒化退回(整文件夹退回 + 单文件退回);无备份则仅展示记录
    val canRevert = isDelete && !reverted && change.backup.isNotBlank()

    // 条目相对 change.path 的层级深度(children 收集时通常带完整相对路径,去掉操作前缀后按目录层级缩进)
    fun displayPath(p: String): String {
        val base = change.path.trim().replace('\\', '/').trim('/')
        var rel = p.trim().replace('\\', '/').trim('/')
        if (base.isNotEmpty() && rel.startsWith(base + "/")) rel = rel.removePrefix(base + "/")
        else if (base.isNotEmpty() && rel == base) rel = "(操作目录)"
        return rel
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            .clickable(interactionSource = noRipple, indication = null) {}
    ) {
        // ── 顶栏 ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    change.path.substringAfterLast('/'),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${opLabel(change.op)} · ${change.path}",
                    fontSize = 10.sp, color = Color(0xFF9CA3AF), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            // 统计徽标:共 N 项 · 总大小
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${info.items.size} 项 · ${fmtSize(info.totalSize)}",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent
                )
            }
            // 删除文件夹:整文件夹一次性退回(恢复备份到原路径)
            if (canRevert) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFDC2626).copy(alpha = 0.12f))
                        .clickable(interactionSource = noRipple, indication = null, onClick = onRevertWhole)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Undo, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("退回文件夹", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                    }
                }
            }
        }

        if (info.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (isDelete) "该文件夹删除时内容未被记录" else "无内容记录",
                    fontSize = 13.sp, color = Color(0xFF9CA3AF)
                )
            }
            return@Column
        }

        // ── 内容清单 ──
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(info.items.size) { i ->
                val it = info.items[i]
                val rel = displayPath(it.path)
                val depth = (rel.count { ch -> ch == '/' }).coerceAtMost(8)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)).background(Color.White)
                        .clickable(interactionSource = noRipple, indication = null) {
                            // 删除文件夹:子文件点按从备份只读预览(文本→CodeEditor/二进制→元信息页),文件夹仍仅提示;
                            // 非删除目录类操作(deleted_folder 之外)子文件真实存在时打开编辑器
                            when {
                                isDelete && it.isDir -> Toast.makeText(ctx, "文件夹,点击文件查看内容", Toast.LENGTH_SHORT).show()
                                isDelete -> onPreviewChild(it.path)
                                it.isDir -> Toast.makeText(ctx, "文件夹,点击文件查看内容", Toast.LENGTH_SHORT).show()
                                else -> onOpenChildRel(it.path)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.width((depth * 8).dp))
                    Icon(
                        if (it.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = if (it.isDir) Color(0xFFD97706) else if (isDelete) Color(0xFFDC2626) else Color(0xFF1E40AF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            rel.substringAfterLast('/'),
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = Color(0xFF1F2937), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (rel.contains('/')) {
                            Text(
                                rel.substringBeforeLast('/'),
                                fontSize = 10.sp, color = Color(0xFF9CA3AF), maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (it.isDir) {
                        Text("文件夹", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                    } else {
                        Text(fmtSize(it.size), fontSize = 11.sp, color = Color(0xFF6B7280), fontWeight = FontWeight.Medium)
                    }
                    // 删除文件夹内容清单:不再额外标注「已删除」文字,删除语义由红色图标承载;单文件颗粒化退回保留
                    if (isDelete && canRevert) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFFDC2626).copy(alpha = 0.12f))
                                .clickable(interactionSource = noRipple, indication = null) { onRevertChild(it.path) }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("退回", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                        }
                    }
                }
            }
            if (info.truncated) {
                item {
                    Text(
                        "内容过多,仅记录前 ${info.items.size} 项(完整内容可在工作区查看)",
                        fontSize = 11.sp, color = Color(0xFF92400E),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFEF3C7))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 删除文件夹子文件元信息页:二进制/超大文件无法预览正文时展示文件名 + 大小 + 原因,
 * 并提示可退回恢复(退回按钮仍在文件夹内容视图,本页仅说明)。
 */
@Composable
private fun PreviewMetaScreen(
    fileName: String,
    reason: String,
    size: Long,
    onBack: () -> Unit
) {
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            .clickable(interactionSource = noRipple, indication = null) {}
    ) {
        // ── 顶栏 ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937))
            }
            Text(
                fileName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(
                    Icons.Filled.InsertDriveFile, contentDescription = null, tint = Color(0xFF9CA3AF),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    fileName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937),
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(reason, fontSize = 13.sp, color = Color(0xFF6B7280), textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("大小:${fmtSize(size)}", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                Spacer(Modifier.height(16.dp))
                Text(
                    "该文件已从工作区删除,如需恢复请返回点「退回」",
                    fontSize = 12.sp, color = Color(0xFFDC2626), textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 全屏只读 diff 视图:删除行红底、新增行绿底、双列行号、相同内容折叠。
 * LazyColumn 懒加载渲染(不一次性铺全量),避免大文件卡顿;
 * 超过 LCS 上限时降级为粗粒度对比(公共前缀/后缀折叠 + 中间整段 -/+),而非简单"内容过长"。
 */
@Composable
private fun AiDiffScreen(
    change: AiChatManager.FileChange,
    onBack: () -> Unit,
    onOpenOriginal: () -> Unit,
    onReverted: () -> Unit = {}
) {
    val isDelete = change.op == "deleted" || change.op == "deleted_folder"
    val accent = if (isDelete) Color(0xFFDC2626) else Color(0xFF1E40AF)
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // 双指缩放系数(参考 CodeEditor.kt 实现):0.5x~3x,驱动字号/行高/行号列宽联动
    val zoomLevel = remember { mutableFloatStateOf(1f) }
    // 整表共享横向滚动状态:行号+文本一体横滚,不存在逐行独立滑动
    val hScroll = rememberScrollState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 退回确认弹窗 + 执行中标记 + 已退回标记(初始值来自持久化的 change.reverted,重进界面仍保持已退回)
    var showRevertConfirm by remember { mutableStateOf(false) }
    var reverting by remember { mutableStateOf(false) }
    var reverted by remember(change.path) { mutableStateOf(change.reverted) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    /** 退回当前单个文件的 AI 操作(只作用于 change.path,不动其它文件) */
    fun revertChange() {
        reverting = true
        scope.launch(Dispatchers.IO) {
            // 与「全部退回」共用同一实现（唯一事实源）——以前这里还抄了一份 when 分支，两处容易走偏
            val result = revertOneChange(ctx, change)
            withContext(Dispatchers.Main) {
                reverting = false
                showRevertConfirm = false
                if (result.first) {
                    reverted = true
                    onReverted()   // 通知上层持久化 reverted 标记
                }
                toast(result.second)
            }
        }
    }

    // 精细 diff(LCS,超限返回 null) → 降级粗粒度布局。
    // 全部在后台线程计算(LCS DP / 逐行拆分对超大文件很重,放组合期会卡死 UI),完成前显示「正在计算」。
    var fine by remember(change.oldText, change.newText) { mutableStateOf<List<Any>?>(null) }
    var coarseLayout by remember(change.oldText, change.newText) { mutableStateOf<CoarseLayout?>(null) }
    var computing by remember { mutableStateOf(true) }
    LaunchedEffect(change.oldText, change.newText) {
        computing = true
        val pair: Pair<List<Any>?, CoarseLayout?> = withContext(Dispatchers.Default) {
            // 【P1-4·D2】复用缓存:列表摘要已算过的 LCS 结果此处直接命中,不再重算
            val f0 = cachedUnifiedDiff(change.oldText, change.newText)
            if (f0 != null) f0 to null else null to buildCoarseLayout(change.oldText, change.newText)
        }
        fine = pair.first
        coarseLayout = pair.second
        computing = false
    }
    val useCoarse = fine == null
    // 精细 diff 走全量行列表;粗粒度不建 List,渲染时按 index 即时生成(流式分页)
    val rows = fine ?: emptyList()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            .clickable(interactionSource = noRipple, indication = null) {}
    ) {
        // ── 顶栏:返回 + 文件名 + 「打开原文件」 ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    change.path.substringAfterLast('/'),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${opLabel(change.op)} · ${change.path}",
                    fontSize = 10.sp, color = Color(0xFF9CA3AF), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            // ── 「退回」:样式同「打开原文件」,点击弹窗确认后仅退回当前这一个文件的 AI 操作;退回成功后置灰禁用,防止二次退回覆盖当前文件 ──
            val revertColor = if (reverted) Color(0xFF9CA3AF) else Color(0xFFDC2626)
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(revertColor.copy(alpha = 0.12f))
                    .clickable(
                        enabled = !reverting && !reverted,
                        interactionSource = noRipple, indication = null
                    ) { showRevertConfirm = true }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Undo, contentDescription = null, tint = revertColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (reverted) "已退回" else if (reverting) "退回中…" else "退回",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = revertColor
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .clickable(interactionSource = noRipple, indication = null, onClick = onOpenOriginal)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("打开原文件", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
                }
            }
        }

        // ── 降级提示 ──
        if (useCoarse) {
            Text(
                "文件较大,已切换为粗粒度对比(仅折叠首尾相同内容)",
                fontSize = 11.sp, color = Color(0xFF92400E),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFEF3C7))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // ── diff 内容:LazyColumn 懒加载纵向滚动(只组合可见行);每行共享同一 hScroll 横向滚动状态,行号+文本一体横滚;双指缩放手势在最外层,不参与任何滚动竞争 ──
        val coarse = coarseLayout
        if (computing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在计算差异…", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
        } else if (useCoarse && (coarse?.totalRowCount ?: 0) > 0) {
            // 粗粒度:流式分页——不建全量行列表,rowAt 按 index 即时生成,滑到哪算到哪;
            // LazyColumn 只组合/布局可见行,离开视口立即回收,超大文件也不占内存
            val c = coarse!!
            Box(
                Modifier
                    .fillMaxSize()
                    // 双指缩放:手势放在所有滚动容器最外层,单指滑动交给内部整表滚动,互不干扰
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    if (zoomChange != 1f) {
                                        zoomLevel.floatValue = (zoomLevel.floatValue * zoomChange).coerceIn(0.5f, 3f)
                                        event.changes.forEach { if (it.pressed) it.consume() }
                                    }
                                }
                                if (!event.changes.any { it.pressed }) break
                            }
                        }
                    }
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(c.totalRowCount) { i ->
                        DiffTableRow(c.rowAt(i), zoomLevel.floatValue, hScroll)
                    }
                }
            }
        } else if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val isEmptyDelete = change.op == "deleted" && change.oldText.isEmpty() && change.newText.isEmpty()
                Text(
                    when {
                        // 二进制/超大文件删除:无文本可 diff,展示大小与退回提示,不再笼统显示「无文本变化」
                        isEmptyDelete && change.size > 0L -> "已删除的文件(二进制/过大),无文本可对比\n原文件大小:${fmtSize(change.size)}\n如需恢复请点击右上角「退回」"
                        // 目录级操作(删除文件夹/解压/压缩/移动目录)本身无文本可 diff,提示用户回列表查看内容清单
                        isDirChangeOp(change.op) -> "目录操作,无文本变化\n请返回查看内容清单"
                        else -> "无文本变化"
                    },
                    fontSize = 12.sp, color = Color(0xFF9CA3AF), textAlign = TextAlign.Center
                )
            }
        } else {
            // 精细 diff:LCS 全量行列表,交给 LazyColumn 懒加载渲染
            Box(
                Modifier
                    .fillMaxSize()
                    // 双指缩放:手势放在所有滚动容器最外层,单指滑动交给内部整表滚动,互不干扰
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    if (zoomChange != 1f) {
                                        zoomLevel.floatValue = (zoomLevel.floatValue * zoomChange).coerceIn(0.5f, 3f)
                                        event.changes.forEach { if (it.pressed) it.consume() }
                                    }
                                }
                                if (!event.changes.any { it.pressed }) break
                            }
                        }
                    }
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows.size) { i ->
                        DiffTableRow(rows[i], zoomLevel.floatValue, hScroll)
                    }
                }
            }
        }
    }

    // ── 退回确认弹窗:确认后仅退回当前这一个文件的 AI 操作 ──
    if (showRevertConfirm) {
        AlertDialog(
            onDismissRequest = { showRevertConfirm = false },
            title = { Text("退回此操作", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = { Text("是否退回此操作?将撤销对「${change.path}」的 AI 改动,仅作用于该文件。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { revertChange() }, enabled = !reverting) {
                    Text(if (reverting) "退回中…" else "确定", fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevertConfirm = false }, enabled = !reverting) {
                    Text("取消", color = Color(0xFF6B7280))
                }
            }
        )
    }
}

/** 渲染单行 diff:行号列紧凑贴左 + 符号 + 文本同行显示;FoldInfo 折叠提示条。zoom 驱动字号/行高/列宽;文本一行到底不换行,横向滚动由共享 hScroll 统一驱动(行号跟着一起滚),行内不挂任何独立滚动。 */
@Composable
private fun DiffTableRow(row: Any, zoom: Float, hScroll: ScrollState) {
    // 字号/行高随 zoom 缩放(参考 CodeEditor:基准 10sp / 14sp,限制 0.5x~3x)
    val baseFontSize = (10 * zoom).coerceIn(5f, 30f)
    val baseLineHeight = (14 * zoom).coerceIn(7f, 42f)
    // 行号列宽/符号列宽随 zoom 联动:紧凑贴左,不预留大空白
    val numW = (20 * zoom).dp.coerceIn(12.dp, 42.dp)
    val symW = (12 * zoom).dp.coerceIn(8.dp, 26.dp)
    when (row) {
        is FoldInfo -> Text(
            "⋯  相同内容已省略 ${row.count} 行 ⋯",
            fontSize = baseFontSize.sp, fontFamily = FontFamily.Monospace,
            lineHeight = baseLineHeight.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFEDF1F5))
                .padding(vertical = 1.dp, horizontal = 4.dp)
        )
        is DiffRow -> {
            val (bg, fg, sym) = when (row.type) {
                '+' -> Triple(Color(0xFFECFDF5), Color(0xFF15803D), "+")
                '-' -> Triple(Color(0xFFFEF2F2), Color(0xFFB91C1C), "-")
                else -> Triple(Color.Transparent, Color(0xFF6B7280), " ")
            }
            // 整行共享 hScroll 横向滚动:行号贴左与文本一体移动,多行共用同一 ScrollState 实现整表同步横滚
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hScroll)
                    .background(bg)
            ) {
                // 旧行号(右对齐,无则留空)
                Text(
                    if (row.oldNo > 0) row.oldNo.toString() else "",
                    fontSize = baseFontSize.sp, fontFamily = FontFamily.Monospace,
                    lineHeight = baseLineHeight.sp,
                    color = Color(0xFFB0B7C3), textAlign = TextAlign.End,
                    modifier = Modifier.width(numW)
                )
                // 新行号(右对齐,无则留空)
                Text(
                    if (row.newNo > 0) row.newNo.toString() else "",
                    fontSize = baseFontSize.sp, fontFamily = FontFamily.Monospace,
                    lineHeight = baseLineHeight.sp,
                    color = Color(0xFFB0B7C3), textAlign = TextAlign.End,
                    modifier = Modifier.width(numW)
                )
                // 增删符号
                Text(
                    sym,
                    fontSize = baseFontSize.sp, fontFamily = FontFamily.Monospace,
                    lineHeight = baseLineHeight.sp,
                    color = fg, modifier = Modifier.width(symW)
                )
                // 文本:一行就是一行,不换行;过长由共享 hScroll 横滚带动(行号跟着一起滚)
                // 【P1-4·D1】叠加轻量语法高亮(注释/字符串),让改动更易读
                Text(
                    highlightCodeLine(row.text, fg),
                    fontSize = baseFontSize.sp, fontFamily = FontFamily.Monospace,
                    lineHeight = baseLineHeight.sp, color = fg,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
        }
    }
}

/** LCS 逐行 diff 单侧行数上限,超过则放弃逐行(降级为粗粒度对比) */
private const val DIFF_LCS_LIMIT = 1200
/** 变化块上下文行数:只展示变化点前后各 N 行,中间大段相同内容折叠——一眼定位改的是哪里 */
private const val DIFF_CONTEXT = 3

/**
 * 【体验优化 P1-4·D2】buildUnifiedDiff 结果缓存：列表摘要(diffStats)与详情视图(AiDiffScreen)
 * 原先各自独立跑一遍完整 LCS；改为共用同一份带缓存的结果，同一文件只算一次。
 * 线程安全(LinkedHashMap 包 synchronized，超出上限自动淘汰最久未用)，key 用尺寸+双文本内容 hash，
 * 极罕见碰撞只会多算一次，不影响正确性。
 */
private val diffUnifiedCache = object : LinkedHashMap<String, List<Any>?>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Any>?>): Boolean = size > 96
}
private fun cachedUnifiedDiff(oldText: String, newText: String): List<Any>? = synchronized(diffUnifiedCache) {
    val key = "${oldText.length}:${newText.length}:${oldText.hashCode().toString(36)}:${newText.hashCode().toString(36)}"
    diffUnifiedCache.getOrPut(key) { buildUnifiedDiff(oldText, newText) }
}

/**
 * 【体验优化 P1-4·D1】单行代码轻量语法高亮：注释(// #)整行淡化、引号字符串着色。
 * 在 diff 行(+/−)上叠加于原有行类型色之上，不影响行语义；解析异常则原样返回，绝不崩。
 */
private fun highlightCodeLine(text: String, strColor: Color): AnnotatedString = runCatching {
    buildAnnotatedString {
        append(text)
        // 行注释：// 或 # 到行尾
        val ci = minOf(
            text.indexOf("//").let { if (it >= 0) it else Int.MAX_VALUE },
            text.indexOf("#").let { if (it >= 0 && text.take(it).count { c -> c == '"' } % 2 == 0) it else Int.MAX_VALUE }
        )
        if (ci in 0 until text.length) {
            addStyle(SpanStyle(color = Color(0xFF94A3B8)), ci, text.length)
        }
        // 字符串："..." 或 '...'（非转义简单匹配）
        var k = 0
        while (k < text.length) {
            val q = text[k]
            if (q == '"' || q == '\'') {
                val end = text.indexOf(q, k + 1)
                if (end > k) {
                    addStyle(SpanStyle(color = strColor), k, end + 1)
                    k = end + 1
                    continue
                }
            }
            k++
        }
    }
}.getOrDefault(AnnotatedString(text))

/** unified diff 单行:类型 ' '/'+'/'-',内容,旧文件行号/新文件行号(0=该侧无此行) */
private data class DiffRow(val type: Char, val text: String, val oldNo: Int, val newNo: Int)

/** 折叠标记:连续相同内容省略段(省略 count 行) */
private data class FoldInfo(val count: Int)

/**
 * 构造 unified 视图:行号 + 变化点±[DIFF_CONTEXT] 行上下文 + 大段相同内容折叠。
 * 返回 DiffRow/FoldInfo 混合列表;null = 超限无法逐行(由粗粒度降级);空列表 = 无文本差异。
 */
private fun buildUnifiedDiff(oldText: String, newText: String): List<Any>? {
    val a = oldText.lines(); val b = newText.lines()
    if (a.size > DIFF_LCS_LIMIT || b.size > DIFF_LCS_LIMIT) return null
    val n = a.size; val m = b.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
        dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
    val rows = mutableListOf<DiffRow>()
    var i = 0; var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { rows.add(DiffRow(' ', a[i], i + 1, j + 1)); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { rows.add(DiffRow('-', a[i], i + 1, 0)); i++ }
            else -> { rows.add(DiffRow('+', b[j], 0, j + 1)); j++ }
        }
    }
    while (i < n) { rows.add(DiffRow('-', a[i], i + 1, 0)); i++ }
    while (j < m) { rows.add(DiffRow('+', b[j], 0, j + 1)); j++ }
    if (rows.none { it.type != ' ' }) return emptyList()
    // 变化块 ±DIFF_CONTEXT 行上下文;相邻块间隔足够近则合并为一个 hunk
    val hunks = mutableListOf<IntRange>()
    rows.indices.filter { rows[it].type != ' ' }.forEach { ci ->
        val s = (ci - DIFF_CONTEXT).coerceAtLeast(0)
        val e = (ci + DIFF_CONTEXT).coerceAtMost(rows.size - 1)
        val last = hunks.lastOrNull()
        if (last != null && s <= last.last + 1) hunks[hunks.size - 1] = last.first..e
        else hunks.add(s..e)
    }
    val out = mutableListOf<Any>()
    var cursor = 0
    for (h in hunks) {
        if (cursor < h.first) out.add(FoldInfo(h.first - cursor))
        for (idx in h) out.add(rows[idx])
        cursor = h.last + 1
    }
    if (cursor < rows.size) out.add(FoldInfo(rows.size - cursor))
    return out
}

/**
 * 粗粒度 diff(超 LCS 上限时的降级):只记录公共前缀/后缀长度,不建全量行列表。
 * 线性复杂度,大文件也能秒出;渲染时按 index 即时生成行(流式分页),滑到哪算到哪。
 */
private data class CoarseLayout(
    val a: List<String>,
    val b: List<String>,
    val p: Int,
    val delEnd: Int,
    val addEnd: Int,
    val s: Int
) {
    /** 总行数:公共前缀折叠条(可选) + 删除段 + 新增段 + 公共后缀折叠条(可选) */
    val totalRowCount: Int
        get() = (if (p > 0) 1 else 0) + (delEnd - p) + (addEnd - p) + (if (s > 0) 1 else 0)

    /** 按序号即时生成行:O(1),不持有全量行对象,大文件只占两段文本本身的内存 */
    fun rowAt(index: Int): Any {
        var i = index
        if (p > 0) {
            if (i == 0) return FoldInfo(p)
            i--
        }
        val delLen = delEnd - p
        if (i < delLen) return DiffRow('-', a[p + i], p + i + 1, 0)
        i -= delLen
        val addLen = addEnd - p
        if (i < addLen) return DiffRow('+', b[p + i], 0, p + i + 1)
        i -= addLen
        return FoldInfo(s)
    }
}

private fun buildCoarseLayout(oldText: String, newText: String): CoarseLayout {
    val a = oldText.lines(); val b = newText.lines()
    var p = 0
    while (p < a.size && p < b.size && a[p] == b[p]) p++
    var s = 0
    while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
    return CoarseLayout(a, b, p, a.size - s, b.size - s, s)
}

/**
 * 退回**单条**改动的实际动作（唯一事实源）：已上提到 [com.aurora.chat.ui.chat.revertOneChange]
 * （位于 AIChangeLedger.kt，与「退回本轮对话」共用，避免两份逻辑走偏）。本文件内的调用
 * 直接解析到同包级的 revertOneChange(ctx, change, baselineText=null)。
 */

/** op → 中文标签 */
private fun opLabel(op: String): String = when (op) {
    "created" -> "新建"
    "modified" -> "修改"
    "deleted" -> "删除"
    "deleted_folder" -> "删除文件夹"
    "renamed" -> "重命名"
    "copied" -> "复制"
    "download" -> "下载"
    "extract" -> "解压"
    "compress" -> "压缩"
    "moved_dir" -> "移动目录"
    else -> "改动"
}

/** op → 图标 */
private fun opIcon(op: String) = when (op) {
    "created" -> Icons.Filled.NoteAdd
    "modified" -> Icons.Filled.Edit
    "deleted" -> Icons.Filled.Delete
    "deleted_folder" -> Icons.Filled.FolderDelete
    "renamed" -> Icons.Filled.DriveFileRenameOutline
    "copied" -> Icons.Filled.Description
    "download" -> Icons.Filled.Download
    "extract" -> Icons.Filled.Unarchive
    "compress" -> Icons.Filled.Archive
    "moved_dir" -> Icons.Filled.DriveFileMove
    else -> Icons.Filled.Description
}

/** 目录级/下载类操作:没有文本 diff,展示 children 内容清单 */
private fun isDirChangeOp(op: String) =
    op == "deleted_folder" || op == "extract" || op == "compress" || op == "moved_dir" || op == "download"

/** children JSON 单条:相对路径 + 大小 + 是否文件夹 */
private data class ChildrenItem(val path: String, val size: Long, val isDir: Boolean)

/** children JSON 解析结果:条目列表 + 总大小 + 是否被截断 */
private data class ChildrenInfo(val items: List<ChildrenItem>, val totalSize: Long, val truncated: Boolean)

/**
 * 解析 children JSON([{path,size,isDir},...])。
 * 兼容旧数据:空/非法返回空列表;条目带 truncated 标记时置 truncated=true 并在末尾补提示。
 */
private fun parseChildren(childrenJson: String): ChildrenInfo {
    if (childrenJson.isBlank()) return ChildrenInfo(emptyList(), 0L, false)
    val items = mutableListOf<ChildrenItem>()
    var total = 0L
    var truncated = false
    try {
        val arr = JSONArray(childrenJson)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path", "")
            if (path.isBlank()) continue
            val size = o.optLong("size", 0L)
            val isDir = o.optBoolean("isDir", false)
            total += size
            items.add(ChildrenItem(path, size, isDir))
        }
        truncated = arr.length() >= ChildrenInfoTruncateAt
    } catch (_: Exception) {
        return ChildrenInfo(emptyList(), 0L, false)
    }
    return ChildrenInfo(items, total, truncated)
}

/** children 收集上限(与 AiChatManager 一致),超过视为截断 */
private const val ChildrenInfoTruncateAt = 200

/** 删除文件夹子文件预览:文本内容截断上限(字符),超出仅预览开头并附注 */
private const val PREVIEW_TEXT_CAP = 100_000

/** 删除文件夹子文件预览:文件大小上限(字节),超过仅展示元信息页 */
private const val PREVIEW_MAX_BYTES = 2_000_000L

/** 大小格式化:B/KB/MB/GB */
private fun fmtSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
}

/** diff 统计:(总变化行, 新增行, 删除行)。【P1-4·D2】复用 cachedUnifiedDiff 的 LCS 结果，避免与详情视图各算一遍。 */
private fun diffStats(oldText: String, newText: String): Triple<Int, Int, Int> {
    val a = oldText.lines(); val b = newText.lines()
    val unified = cachedUnifiedDiff(oldText, newText)
    val added: Int; val removed: Int
    if (unified == null) {
        // 超限:朴素集合差近似,仅作摘要
        val bagA = a.groupingBy { it }.eachCount().toMutableMap()
        added = b.count { l -> (bagA[l] ?: 0).let { if (it > 0) { bagA[l] = it - 1; false } else true } }
        removed = (a.size - (b.size - added)).coerceAtLeast(0)
    } else {
        added = unified.count { it is DiffRow && it.type == '+' }
        removed = unified.count { it is DiffRow && it.type == '-' }
    }
    return Triple(added + removed, added, removed)
}


