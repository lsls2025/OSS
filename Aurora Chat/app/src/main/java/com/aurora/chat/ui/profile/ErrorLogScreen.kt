package com.aurora.chat.ui.profile

import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ErrorReporter
import com.aurora.chat.LogLevel
import java.io.File

/**
 * 错误日志浏览页面 —— 按级别清晰分类，无错乱。
 */
@Composable
fun ErrorLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    val allFiles = remember(refreshKey) { ErrorReporter.getAllFiles() }
    val fatalFiles = remember(allFiles) { allFiles.filter { it.name.startsWith("FATAL") } }
    val errorFiles = remember(allFiles) { allFiles.filter { it.name.startsWith("ERROR") } }

    // 进入时标记已读
    LaunchedEffect(Unit) { ErrorReporter.markAllRead() }

    var selectedFile by remember { mutableStateOf<File?>(null) }
    var filterLevel by remember { mutableStateOf("all") }

    val displayFiles = remember(allFiles, filterLevel) {
        when (filterLevel) {
            "FATAL" -> allFiles.filter { it.name.startsWith("FATAL") }
            "ERROR" -> allFiles.filter { it.name.startsWith("ERROR") || it.name.startsWith("ERROR") }
            else -> allFiles
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        if (selectedFile != null) {
            LogDetailView(
                file = selectedFile!!,
                onBack = { selectedFile = null }
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 顶部栏 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth().background(Color.White)
                        .statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF3F4F6)).clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) { Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)) }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("错误日志", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                        val parts = mutableListOf<String>()
                        if (fatalFiles.isNotEmpty()) parts.add("闪退 ${fatalFiles.size} 次")
                        if (errorFiles.isNotEmpty()) parts.add("错误 ${errorFiles.size} 个")
                        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), fontSize = 11.sp, color = Color(0xFF9CA3AF))
                    }
                    if (allFiles.isNotEmpty()) {
                        Text("清空", fontSize = 13.sp, color = Color(0xFFDC2626),
                            modifier = Modifier.clickable {
                                ErrorReporter.clearAll(); refreshKey++
                                Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                            }.padding(horizontal = 10.dp, vertical = 6.dp))
                        Text("发送日志", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                            modifier = Modifier.clickable { shareAll(context) }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

                // ── 筛选行 ──
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val total = allFiles.size
                    val crashN = fatalFiles.size
                    val errorN = errorFiles.size
                    listOf("all" to "全部 ($total)", "FATAL" to "闪退 ($crashN)", "ERROR" to "错误 ($errorN)")
                        .forEach { (key, label) ->
                            val sel = filterLevel == key
                            Box(modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Color(0xFF1E40AF) else Color(0xFFF3F4F6))
                                .clickable { filterLevel = key }
                                .padding(horizontal = 10.dp, vertical = 5.dp)) {
                                Text(label, fontSize = 11.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (sel) Color.White else Color(0xFF4B5563))
                            }
                        }
                }

                if (displayFiles.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("一切正常", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color(0xFF16A34A))
                            Spacer(Modifier.height(4.dp))
                            Text("没有记录到任何错误", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                        }
                    }
                } else {
                    val listState = rememberLazyListState()
                    var displayCount by remember { mutableStateOf(100.coerceAtMost(displayFiles.size)) }
                    LaunchedEffect(displayFiles) { displayCount = 100.coerceAtMost(displayFiles.size) }
                    LaunchedEffect(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index) {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        if (last >= displayCount - 5 && displayCount < displayFiles.size)
                            displayCount = (displayCount + 100).coerceAtMost(displayFiles.size)
                    }
                    val visible = remember(displayFiles, displayCount) { displayFiles.take(displayCount) }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(visible, key = { it.absolutePath }) { file ->
                            LogCard(file = file, onClick = { selectedFile = file }, onShare = { shareOne(context, file) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogCard(file: File, onClick: () -> Unit, onShare: () -> Unit) {
    val level = ErrorReporter.getLevel(file)
    val isFatal = level == LogLevel.FATAL
    val isWarn = level == LogLevel.WARNING
    val niceTime = ErrorReporter.friendlyTime(file)
    val logTag = ErrorReporter.getTag(file)
    // 大日志文件读盘移 IO，避免组合期阻塞主线程
    val content by produceState("", file) {
        withContext(Dispatchers.IO) { value = ErrorReporter.readFile(file) }
    }
    val hasStack = ErrorReporter.hasStack(file)

    val accentColor = when { isFatal -> Color(0xFFDC2626); isWarn -> Color(0xFFB8860B); else -> Color(0xFFE65100) }
    val bgColor = when { isFatal -> Color(0xFFFEF2F2); isWarn -> Color(0xFFFFF8E1); else -> Color(0xFFFFF8E1) }
    val levelText = when { isFatal -> "致命"; isWarn -> "警告"; else -> "错误" }

    // 标签中文化
    val tagCN = mapOf("Network" to "网络", "Download" to "下载", "API" to "接口",
        "SSL" to "安全", "逻辑" to "逻辑", "数据" to "数据").let { it[logTag] ?: logTag }

    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(bgColor).clickable { onClick() }.padding(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(accentColor.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(levelText, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accentColor)
            }
            Spacer(Modifier.width(4.dp))
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(accentColor.copy(alpha = 0.1f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                Text(tagCN, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = accentColor.copy(alpha = 0.8f))
            }
            Spacer(Modifier.width(6.dp))
            Text(niceTime, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accentColor)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        // 消息行
        val msgLine = content.lines().find { it.startsWith("消息: ") }?.removePrefix("消息: ") ?: ""
        if (msgLine.isNotEmpty()) {
            Text(msgLine, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = Color(0xFF374151), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
        }
        // 堆栈预览
        val preview = content.lines().firstOrNull { it.trimStart().startsWith("at ") }
            ?: content.lines().firstOrNull { it.startsWith("---") }
        if (preview != null) {
            Text(preview.take(100), fontSize = 10.sp, color = Color(0xFF6B7280),
                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("分享此条", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                modifier = Modifier.clickable { onShare() }.padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.width(8.dp))
            Text("查看详情 ›", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                modifier = Modifier.clickable { onClick() }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun LogDetailView(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    // 大日志文件读盘移 IO，避免组合期阻塞主线程
    val content by produceState("", file) {
        withContext(Dispatchers.IO) { value = ErrorReporter.readFile(file) }
    }
    val level = ErrorReporter.getLevel(file)
    val isFatal = level == LogLevel.FATAL
    val niceTime = ErrorReporter.friendlyTime(file)
    val tagColor = if (isFatal) Color(0xFFDC2626) else Color(0xFFE65100)
    // ★★ 修复关键 Bug：只有 FATAL 才显示"闪退"，其余全部显示对应级别 ★★★
    val levelDisplay = when (level) {
        LogLevel.FATAL -> "闪退"
        LogLevel.ERROR -> "错误"
        LogLevel.WARNING -> "警告"
        LogLevel.DEBUG -> "调试"
        null -> "未知"
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Row(modifier = Modifier.fillMaxWidth().background(Color.White)
            .statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFF3F4F6)).clickable { onBack() },
                contentAlignment = Alignment.Center) {
                Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
            }
            Spacer(Modifier.width(8.dp))
            Text("日志详情", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937),
                modifier = Modifier.weight(1f))
            Text("分享", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                modifier = Modifier.clickable { shareOne(context, file) }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(tagColor.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(levelDisplay, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tagColor)
                }
                Spacer(Modifier.width(8.dp))
                Text(niceTime, fontSize = 13.sp, color = Color(0xFF6B7280))
            }
            Spacer(Modifier.height(12.dp))
            Text(text = content, fontSize = 10.sp, color = Color(0xFF374151),
                lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun shareAll(context: Context) {
    try {
        context.startActivity(Intent.createChooser(ErrorReporter.createShareIntent(context), "发送错误报告"))
    } catch (e: Exception) { Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show() }
}

private fun shareOne(context: Context, file: File) {
    val content = ErrorReporter.readFile(file)
    try {
        val report = File(context.cacheDir, "single_error.log")
        report.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", report)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "*/*"; putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Aurora Chat 错误日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享此条日志"))
    } catch (_: Exception) {
        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Aurora Chat 错误日志")
            putExtra(Intent.EXTRA_TEXT, content)
        }, "分享此条日志")) } catch (_: Exception) { Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show() }
    }
}
