package com.aurora.chat.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.theme.AuroraPrimary
import com.aurora.chat.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「文件分发」全屏页：后台中心板块一。
 *
 * 板块状布局（与用户资料 / 群资料同一套：灰色 0xFFF3F4F6 圆角块、深灰文字），
 * 无 M3 彩色按钮、无描述性提示文字。
 *
 * 上传状态固定显示「上传中 + 百分比」，不显示文件名；
 * 链接操作文案：「复制访问链接」（浏览器打开）/「复制下载链接」（直接下载）。
 *
 * 固定链接：给文件绑定一个别名后，对外链接固定为 /fd/<别名>，删除重传或原地替换内容都不变；
 * 访问次数（打开访问链接）与下载次数（访问链接带 ?dl=1）分开统计。
 */
@Composable
fun FileDistributionScreen(
    onClose: () -> Unit
) {
    val appColors = LocalAppColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(JSONArray()) }
    var usedBytes by remember { mutableLongStateOf(0L) }
    var maxSpeed by remember { mutableDoubleStateOf(0.0) }
    var capValue by remember { mutableDoubleStateOf(0.0) }
    var capUnit by remember { mutableStateOf("GB") }
    var uploading by remember { mutableStateOf(false) }
    var uploadPercent by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<JSONObject?>(null) }
    // 固定链接弹窗（入口在顶部第三块卡片）
    var showAliasDialog by remember { mutableStateOf(false) }
    var aliasFileId by remember { mutableLongStateOf(0L) }
    var aliasInput by remember { mutableStateOf("") }
    var aliasSaving by remember { mutableStateOf(false) }
    // 「替换内容」：选好文件后按已有别名上传，服务端原地替换，链接不变
    var replaceAlias by remember { mutableStateOf<String?>(null) }

    fun copyText(label: String, value: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        android.widget.Toast.makeText(ctx, "已复制$label", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun loadList() {
        loading = true
        scope.launch {
            try {
                val r = AuroraApi.fdList()
                if (r.success && r.data != null) {
                    val d = r.data!!
                    files = d.optJSONArray("files") ?: JSONArray()
                    usedBytes = d.optLong("used", 0L)
                    maxSpeed = d.optDouble("max_speed", 0.0)
                    capValue = d.optDouble("capacity_value", 0.0)
                    capUnit = d.optString("capacity_unit", "GB")
                    errorMsg = null
                } else {
                    errorMsg = r.message
                }
            } catch (e: Exception) {
                errorMsg = e.message
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { loadList() }

    // 系统文件选择器（多选），逐个上传，界面固定显示「上传中 + 百分比」
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty() || uploading) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            for (uri in uris) {
                try {
                    val (name, size) = withContext(Dispatchers.IO) { queryFileInfo(ctx, uri) }
                    if (size > 2L * 1024 * 1024 * 1024) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "$name 超过单文件 2GB 上限", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        continue
                    }
                    uploadPercent = 0
                    val bytes = withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } ?: continue
                    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                    val r = AuroraApi.fdUploadWithProgress(bytes, name, mime) { sent, total ->
                        val p = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
                        android.os.Handler(android.os.Looper.getMainLooper()).post { uploadPercent = p }
                    }
                    if (!(r.success && r.data != null)) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "上传失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            uploading = false
            uploadPercent = 0
            loadList()
        }
    }

    // 「替换内容」选择器：按已有固定链接别名上传，服务端原地替换，对外链接不变
    val replaceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val alias = replaceAlias
        replaceAlias = null
        if (uri == null || alias.isNullOrEmpty() || uploading) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            uploadPercent = 0
            try {
                val (name, size) = withContext(Dispatchers.IO) { queryFileInfo(ctx, uri) }
                if (size > 2L * 1024 * 1024 * 1024) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "$name 超过单文件 2GB 上限", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: return@launch
                val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                val r = AuroraApi.fdUploadWithProgress(bytes, name, mime, alias) { sent, total ->
                    val p = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
                    android.os.Handler(android.os.Looper.getMainLooper()).post { uploadPercent = p }
                }
                withContext(Dispatchers.Main) {
                    val msg = if (r.success && r.data != null) "已替换内容" else (r.message ?: "替换失败")
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "替换失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } finally {
                uploading = false
                uploadPercent = 0
                loadList()
            }
        }
    }

    // 保存固定链接（空串 = 取消固定链接，退回 token 链接）
    fun saveAlias() {
        if (aliasSaving) return
        if (aliasFileId <= 0) {
            android.widget.Toast.makeText(ctx, "请先选择一个文件", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val name = aliasInput.trim()
        if (name.isEmpty()) {
            android.widget.Toast.makeText(ctx, "请输入链接名", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (name.length < 2) {
            android.widget.Toast.makeText(ctx, "链接名至少 2 个字符", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        aliasSaving = true
        scope.launch {
            try {
                val r = AuroraApi.fdSetAlias(aliasFileId, name)
                if (r.success) {
                    showAliasDialog = false
                    android.widget.Toast.makeText(ctx, "已添加", android.widget.Toast.LENGTH_SHORT).show()
                    loadList()
                } else {
                    android.widget.Toast.makeText(ctx, r.message ?: "保存失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            aliasSaving = false
        }
    }

    var totalDrag by remember { mutableStateOf(0f) }
    Column(
        Modifier
            .fillMaxSize()
            .background(appColors.surface)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                    onDragEnd = { if (totalDrag < -80f) onClose() }
                )
            }
    ) {
        // 顶部栏：标题 + 返回（避让状态栏）
        Box(
            Modifier
                .fillMaxWidth()
                .background(appColors.surface)
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
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text("文件分发", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // 用量板块（灰色板块状，无副标题提示）
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                StatBlock(
                    Modifier.weight(1f),
                    title = "已用容量",
                    value = if (capValue > 0) "${humanSize(usedBytes)} / ${trimNum(capValue)} $capUnit" else humanSize(usedBytes)
                )
                StatBlock(
                    Modifier.weight(1f),
                    title = "下载速度",
                    value = if (maxSpeed > 0) "${trimNum(maxSpeed)} Mbps" else "不限"
                )
                StatBlock(
                    Modifier.weight(1f),
                    title = "固定链接",
                    value = "添加",
                    onClick = {
                        // 每次打开都是「新增」：不预选文件、不带上一次的别名
                        aliasFileId = 0L
                        aliasInput = ""
                        showAliasDialog = true
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            // 上传入口（灰色板块；上传中固定显示「上传中 + 百分比」）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp))
                    .clickable(enabled = !uploading) { pickLauncher.launch(arrayOf("*/*")) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (uploading) "上传中 $uploadPercent%" else "上传文件",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (uploading) com.aurora.chat.ui.theme.appTextSecondary() else com.aurora.chat.ui.theme.appTextPrimary()
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("我的文件 (${files.length()})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)

            if (errorMsg != null) {
                Spacer(Modifier.height(8.dp))
                Text("加载失败: $errorMsg", fontSize = 12.sp, color = Color(0xFFDC2626))
            }

            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                // 有固定链接就用别名链接（删除重传/替换内容都不变），否则退回 token 链接
                val alias = f.optString("alias", "")
                val link = if (alias.isNotEmpty()) AuroraApi.serverUrl + "/fd/" + alias
                           else AuroraApi.serverUrl + f.optString("url", "")
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(f.optString("file_name", ""), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${humanSize(f.optLong("file_size", 0))} 访问 ${f.optLong("accesses", 0)} 次 · " +
                            "下载 ${f.optLong("downloads", 0)} 次 · " +
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.optLong("created_at", 0) * 1000)),
                        fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        link, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = com.aurora.chat.ui.theme.appTextSecondary(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { copyText("访问链接", link) }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("复制访问链接", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary(),
                            modifier = Modifier.clickable { copyText("访问链接", link) })
                        Spacer(Modifier.width(14.dp))
                        Text("复制下载链接", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary(),
                            modifier = Modifier.clickable { copyText("下载链接", link + "?dl=1") })
                        if (alias.isNotEmpty()) {
                            Spacer(Modifier.width(14.dp))
                            // 只有已绑定固定链接的文件才需要「替换文件」：换内容不换链接
                            Text("替换文件", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AuroraPrimary,
                                modifier = Modifier.clickable {
                                    replaceAlias = alias
                                    replaceLauncher.launch(arrayOf("*/*"))
                                })
                        }
                        Spacer(Modifier.width(14.dp))
                        Text("删除", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFDC2626),
                            modifier = Modifier.clickable { deleteTarget = f })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // 删除确认弹窗
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("删除文件", fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()) },
            text = { Text("确定删除「${target.optString("file_name", "")}」吗？删除后链接立即失效。", color = com.aurora.chat.ui.theme.appTextSecondary()) },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.optLong("id", 0)
                    deleteTarget = null
                    scope.launch {
                        try {
                            val r = AuroraApi.fdDelete(id)
                            android.widget.Toast.makeText(ctx, if (r.success) "已删除" else "删除失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                            loadList()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(ctx, "删除失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("删除", color = Color(0xFFDC2626)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) } }
        )
    }

    // 添加固定链接弹窗：白底 + 灰字标题 + 文字按钮（与项目内其它弹窗同一套），不使用 MD3 配色
    if (showAliasDialog) {
        AlertDialog(
            onDismissRequest = { if (!aliasSaving) showAliasDialog = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("添加固定链接", fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()) },
            text = {
                Column {
                    // 选文件（每次打开都不预选，直接选一个新的来绑定）
                    Column(
                        Modifier
                            .heightIn(max = 176.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        for (i in 0 until files.length()) {
                            val o = files.optJSONObject(i) ?: continue
                            val id = o.optLong("id", 0L)
                            val on = id == aliasFileId
                            val a = o.optString("alias", "")
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(
                                        if (on) com.aurora.chat.ui.theme.appCardBg() else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        aliasFileId = id
                                        aliasInput = a
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    o.optString("file_name", ""), fontSize = 13.sp,
                                    color = if (on) com.aurora.chat.ui.theme.appTextPrimary() else com.aurora.chat.ui.theme.appTextSecondary(),
                                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (a.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("/fd/$a", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = aliasInput,
                        onValueChange = { input ->
                            aliasInput = input.filter {
                                (it in 'a'..'z') || (it in 'A'..'Z') || it.isDigit() ||
                                    it == '.' || it == '-' || it == '_'
                            }.take(64)
                        },
                        singleLine = true,
                        placeholder = { Text("data.json", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AuroraPrimary,
                            unfocusedBorderColor = Color(0xFFD1D5DB),
                            cursorColor = AuroraPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (aliasInput.isNotBlank()) AuroraApi.serverUrl + "/fd/" + aliasInput.lowercase() else " ",
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = com.aurora.chat.ui.theme.appTextHint(), maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { saveAlias() }, enabled = !aliasSaving) {
                    Text(
                        if (aliasSaving) "保存中..." else "保存",
                        color = if (aliasSaving) com.aurora.chat.ui.theme.appTextHint() else AuroraPrimary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!aliasSaving) showAliasDialog = false }) {
                    Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        )
    }
}

/** 灰色板块统计块（无副标题、无彩色强调）；传 onClick 即成为同形状的按钮块 */
@Composable
private fun StatBlock(modifier: Modifier, title: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        modifier
            .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(title, fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun queryFileInfo(ctx: Context, uri: Uri): Pair<String, Long> {
    var name = "file"
    var size = 0L
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val si = c.getColumnIndex(OpenableColumns.SIZE)
        if (c.moveToFirst()) {
            if (ni >= 0) c.getString(ni)?.let { name = it }
            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
        }
    }
    return name to size
}

private fun humanSize(n: Long): String {
    val f = n.toDouble()
    return when {
        f >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", f / 1024 / 1024 / 1024)
        f >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", f / 1024 / 1024)
        f >= 1024 -> String.format(Locale.US, "%.2f KB", f / 1024)
        else -> "${n} B"
    }
}

private fun trimNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(Locale.US, "%.1f", d)
