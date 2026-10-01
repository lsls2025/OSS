package com.aurora.chat.ui.chat

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderDelete
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
fun AiChangesScreen(changesJson: String, onBack: () -> Unit, onChangesUpdated: (String) -> Unit = {}) {
    // 可变改动列表:退回成功后更新对应项的 reverted 标记并回传新 JSON,由上层写回消息持久化
    var changes by remember(changesJson) { mutableStateOf(AiChatManager.parseFileChanges(changesJson)) }
    val ctx = LocalContext.current
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val scope = rememberCoroutineScope()

    // ── 全屏 diff 视图:点卡片进入,覆盖改动列表 ──
    var viewing by remember { mutableStateOf<AiChatManager.FileChange?>(null) }
    // ── 「打开原文件」真实文件编辑器(与 WorkspaceFileManagerScreen 的 editingFile + CodeEditor 同一套用法) ──
    var editingFile by remember { mutableStateOf<File?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var editLoading by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    /** FileChange.path 是工作区相对路径:拼工作区根定位真实文件;校验不逃出沙盒,非法/越界返回 null */
    fun resolveChangeFile(c: AiChatManager.FileChange): File? {
        val rel = c.path.trim().replace('\\', '/').trim('/')
        if (rel.isBlank() || rel.split('/').any { it == ".." }) { toast("路径非法,无法定位原文件"); return null }
        val root = AiChatManager.agentRoot(ctx)
        val f = File(root, rel)
        val rootAbs = root.absolutePath.trimEnd('/', '\\') + File.separator
        if (!f.absolutePath.startsWith(rootAbs)) { toast("路径越界,已拦截"); return null }
        return f
    }

    /** 打开真实文件编辑器:大小护栏 + IO 读取,加载完成后叠加 CodeEditor(参考 WorkspaceFileManagerScreen.openEditor) */
    fun openOriginal(c: AiChatManager.FileChange) {
        val f = resolveChangeFile(c) ?: return
        if (!f.exists()) { toast("原文件不存在(可能已删除)"); return }
        if (f.length() > 2_000_000L) { toast("文件过大(超过 2MB),不支持编辑"); return }
        editingFile = f
        editLoading = true
        scope.launch(Dispatchers.IO) {
            val content = runCatching { f.readText() }.getOrElse { "（无法读取:${it.message ?: "未知错误"}）" }
            withContext(Dispatchers.Main) { editingContent = content; editLoading = false }
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
                Text(
                    "共 ${changes.size} 处文件改动",
                    fontSize = 11.sp, color = Color(0xFF9CA3AF)
                )
            }
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

        if (changes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有文件改动记录", fontSize = 13.sp, color = Color(0xFF9CA3AF))
            }
            return@Column
        }

        // ── 改动列表:点卡片进全屏 diff 视图 ──
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(changes.size) { i ->
                ChangeCard(changes[i], onClick = { viewing = changes[i] })
            }
        }
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

    // ── 「打开原文件」:CodeEditor 全屏编辑器 ──
    if (editingFile != null) {
        CodeEditor(
            title = editingFile?.name ?: "",
            subtitle = "工作区文件",
            initialContent = editingContent,
            isLoading = editLoading,
            onSave = { text, closeAfter -> saveEditor(text, closeAfter) },
            onDismiss = { editingFile = null; editingContent = "" },
            luaRootFile = editingFile
        )
    }
}

@Composable
private fun ChangeCard(c: AiChatManager.FileChange, onClick: () -> Unit) {
    val isDelete = c.op == "deleted" || c.op == "deleted_folder"
    val accent = if (isDelete) Color(0xFFDC2626) else Color(0xFF1E40AF)
    val stats = remember(c.oldText, c.newText) { diffStats(c.oldText, c.newText) }
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            // 点卡片进全屏 diff 视图;indication = null 关闭水波纹
            .clickable(interactionSource = noRipple, indication = null, onClick = onClick)
            .padding(12.dp)
    ) {
        // 头部:类型徽标 + 路径 + 增删统计
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
            if (stats.second > 0) Text("+${stats.second} 行", fontSize = 11.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Medium)
            if (stats.second > 0 && stats.third > 0) Spacer(Modifier.width(8.dp))
            if (stats.third > 0) Text("-${stats.third} 行", fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium)
            if (stats.second == 0 && stats.third == 0) Text("无文本变化", fontSize = 11.sp, color = Color(0xFF9CA3AF))
            Spacer(Modifier.weight(1f))
            Text("查看详情", fontSize = 11.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
            Text(" ›", fontSize = 13.sp, color = Color(0xFF1E40AF))
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
            val result = runCatching {
                val root = AiChatManager.agentRoot(ctx)
                val rel = change.path.trim().replace('\\', '/').trim('/')
                if (rel.isBlank() || rel.split('/').any { it == ".." }) throw IllegalStateException("路径非法")
                val f = File(root, rel)
                val rootAbs = root.absolutePath.trimEnd('/', '\\') + File.separator
                if (!f.absolutePath.startsWith(rootAbs)) throw IllegalStateException("路径越界")
                when (change.op) {
                    "created", "copied" -> {
                        if (f.exists()) f.delete()
                        Pair(true, "已退回:删除 AI 新建的文件 ${change.path}")
                    }
                    "modified" -> {
                        f.parentFile?.mkdirs()
                        f.writeText(change.oldText, Charsets.UTF_8)
                        Pair(true, "已退回:恢复修改前内容 ${change.path}")
                    }
                    "deleted" -> {
                        f.parentFile?.mkdirs()
                        f.writeText(change.oldText, Charsets.UTF_8)
                        Pair(true, "已退回:恢复被删除的文件 ${change.path}")
                    }
                    "renamed" -> {
                        // path 为重命名后的新路径,fromPath 为原路径 → 移回原名
                        val oldRel = change.fromPath.trim().replace('\\', '/').trim('/')
                        if (oldRel.isBlank() || oldRel.split('/').any { it == ".." }) throw IllegalStateException("原路径非法")
                        val oldF = File(root, oldRel)
                        if (!oldF.absolutePath.startsWith(rootAbs)) throw IllegalStateException("原路径越界")
                        if (oldF.exists()) throw IllegalStateException("原路径已存在,无法退回重命名")
                        oldF.parentFile?.mkdirs()
                        if (f.exists()) f.renameTo(oldF) else oldF.writeText(change.oldText, Charsets.UTF_8)
                        Pair(true, "已退回:恢复原文件名 $oldRel")
                    }
                    "deleted_folder" -> Pair(false, "文件夹删除无法自动恢复,请手动重建")
                    else -> Pair(false, "未知操作类型,无法退回")
                }
            }.getOrElse { Pair(false, "退回失败:${it.message}") }
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

    // 精细 diff(LCS,超限返回 null) → 降级粗粒度对比
    val fine = remember(change.oldText, change.newText) { buildUnifiedDiff(change.oldText, change.newText) }
    val coarse = remember(change.oldText, change.newText) { buildCoarseDiff(change.oldText, change.newText) }
    val useCoarse = fine == null
    val rows = fine ?: coarse
    // 极端护栏:粗粒度下中间段也可能上万行,只渲染前后各半,中间折叠提示
    val renderRows: List<Any>
    val omittedHint: String?
    if (rows.size > DIFF_RENDER_CAP) {
        val half = DIFF_RENDER_CAP / 2
        renderRows = rows.take(half) + FoldInfo(rows.size - DIFF_RENDER_CAP) + rows.takeLast(half)
        omittedHint = "内容过大,中间 ${rows.size - DIFF_RENDER_CAP} 行已省略,建议打开原文件查看"
    } else {
        renderRows = rows
        omittedHint = null
    }

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
        if (omittedHint != null) {
            Text(
                omittedHint,
                fontSize = 11.sp, color = Color(0xFF92400E),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFEF3C7))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // ── diff 内容:LazyColumn 懒加载纵向滚动(只组合可见行,几万行也不卡);每行共享同一 hScroll 横向滚动状态,行号+文本一体横滚,无逐行独立滑动;双指缩放手势在最外层,不参与任何滚动竞争 ──
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无文本变化", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
        } else {
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
                // 纵向: LazyColumn 懒加载,只组合/布局可见行,大文件不再一次性铺全量
                LazyColumn(Modifier.fillMaxSize()) {
                    items(renderRows.size) { i ->
                        DiffTableRow(renderRows[i], zoomLevel.floatValue, hScroll)
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
                Text(
                    row.text,
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
/** 全屏 diff 单次渲染行数上限(极端护栏):超过只渲染前后各半并折叠提示 */
private const val DIFF_RENDER_CAP = 3000
/** 变化块上下文行数:只展示变化点前后各 N 行,中间大段相同内容折叠——一眼定位改的是哪里 */
private const val DIFF_CONTEXT = 3

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
 * 粗粒度 diff(超 LCS 上限时的降级):公共前缀/后缀折叠为 FoldInfo,中间整段 -/+ 全量展示。
 * 线性复杂度,大文件也能秒出;不追求逐行对齐,保证"删了什么/加了什么"完整可见。
 */
private fun buildCoarseDiff(oldText: String, newText: String): List<Any> {
    val a = oldText.lines(); val b = newText.lines()
    if (a.isEmpty() && b.isEmpty()) return emptyList()
    var p = 0
    while (p < a.size && p < b.size && a[p] == b[p]) p++
    var s = 0
    while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
    val out = mutableListOf<Any>()
    if (p > 0) out.add(FoldInfo(p))
    for (i in p until a.size - s) out.add(DiffRow('-', a[i], i + 1, 0))
    for (j in p until b.size - s) out.add(DiffRow('+', b[j], 0, j + 1))
    if (s > 0) out.add(FoldInfo(s))
    return out
}

/** op → 中文标签 */
private fun opLabel(op: String): String = when (op) {
    "created" -> "新建"
    "modified" -> "修改"
    "deleted" -> "删除"
    "deleted_folder" -> "删除文件夹"
    "renamed" -> "重命名"
    "copied" -> "复制"
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
    else -> Icons.Filled.Description
}

/**
 * 逐行 diff(LCS)。返回 (类型, 行内容) 列表:类型 '+'新增 / '-'删除 / ' '保留。
 * 单侧超过 [DIFF_LCS_LIMIT] 行时返回 null(界面降级为粗粒度对比)。
 */
private fun lineDiffLines(oldText: String, newText: String): List<Pair<Char, String>>? {
    val a = oldText.lines()
    val b = newText.lines()
    if (a.size > DIFF_LCS_LIMIT || b.size > DIFF_LCS_LIMIT) return null
    val n = a.size; val m = b.size
    // LCS DP(滚动数组会丢路径,这里直接全量,1200x1200 ≈ 1.44M int,可接受)
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                       else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val out = mutableListOf<Pair<Char, String>>()
    var i = 0; var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { out.add(' ' to a[i]); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { out.add('-' to a[i]); i++ }
            else -> { out.add('+' to b[j]); j++ }
        }
    }
    while (i < n) { out.add('-' to a[i]); i++ }
    while (j < m) { out.add('+' to b[j]); j++ }
    return out
}

/** diff 统计:(总变化行, 新增行, 删除行)。超限文件用朴素集合差近似,仅作摘要。 */
private fun diffStats(oldText: String, newText: String): Triple<Int, Int, Int> {
    val a = oldText.lines(); val b = newText.lines()
    val added: Int; val removed: Int
    if (a.size > DIFF_LCS_LIMIT || b.size > DIFF_LCS_LIMIT) {
        val bagA = a.groupingBy { it }.eachCount().toMutableMap()
        added = b.count { l -> (bagA[l] ?: 0).let { if (it > 0) { bagA[l] = it - 1; false } else true } }
        removed = (a.size - (b.size - added)).coerceAtLeast(0)
    } else {
        val d = lineDiffLines(oldText, newText) ?: return Triple(0, 0, 0)
        added = d.count { it.first == '+' }
        removed = d.count { it.first == '-' }
    }
    return Triple(added + removed, added, removed)
}
