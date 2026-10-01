package com.aurora.chat.ui.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全屏文本文件预览（TXT / MD / LOG / PY / JSON / LUA / YAML …… 所有文本类）。
 * 统一支持三种来源:
 *   1. 本地绝对路径  例如 "/data/user/0/xxx/files/ai_files/notes.txt"
 *   2. file:// Uri    例如 "file:///data/xxx/files/downloads/notes.txt"
 *   3. content:// Uri  系统授权的 content resolver 路径
 *   4. http/https URL  远程文件（社区帖子文件等）
 *
 * 为什么之前打不开: 旧版只实现了 OkHttp 拉 http(s), 本地 file:///content:// 全部丢给 OkHttp → 直接失败。
 * 顶部栏加 statusBarsPadding, 避免贴到状态栏/挖孔屏导致难按。
 */
@Composable
fun FileTextPreview(
    url: String,
    fileName: String,
    onClose: () -> Unit,
    onDownload: (url: String, fileName: String) -> Unit
) {
    val ctx = LocalContext.current
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(url) {
        loading = true
        error = ""
        val result = withContext(Dispatchers.IO) {
            try {
                val text = loadTextBySource(ctx, url)
                if (text.isEmpty()) Result.failure<String>(Exception("文件内容为空或不是 UTF-8 文本"))
                else Result.success(text)
            } catch (e: Exception) {
                Result.failure<String>(e)
            }
        }
        loading = false
        result.fold(
            onSuccess = { content = it },
            onFailure = { error = it.message ?: "加载失败" }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶部栏: 加 statusBarsPadding 避开状态栏/挖孔屏, 按钮更好按
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937))
                }
                Text(
                    text = fileName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF111827),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onDownload(url, fileName) }) {
                    Icon(Icons.Outlined.Download, contentDescription = "下载", tint = Color(0xFF1E40AF))
                }
            }
            // 分割线
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
            // 正文
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                when {
                    loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = Color(0xFF1E40AF), strokeWidth = 2.5.dp) }
                    error.isNotEmpty() -> Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("😵", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(error, fontSize = 14.sp, color = Color(0xFFDC2626))
                    }
                    else -> SelectionContainer {
                        Text(
                            text = content ?: "",
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = Color(0xFF1F2937),
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
            }
        }
    }
}

/**
 * 统一的文本读取: 按 url 前缀分流。
 * - file:// 或绝对路径 → new File(path).readText()
 * - content:// → contentResolver.openInputStream
 * - http/https → OkHttp
 */
private suspend fun loadTextBySource(ctx: android.content.Context, url: String): String {
    return withContext(Dispatchers.IO) {
        when {
            url.startsWith("content://") -> {
                ctx.contentResolver.openInputStream(Uri.parse(url))?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("无法打开 content:// Uri")
            }
            url.startsWith("file://") -> {
                val path = url.removePrefix("file://")
                File(path).readText(Charsets.UTF_8)
            }
            url.startsWith("http://") || url.startsWith("https://") -> {
                val req = okhttp3.Request.Builder().url(url).header("Connection", "close").build()
                com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw java.io.IOException("响应为空")
                    body.byteStream().bufferedReader().use { it.readText() }
                }
            }
            else -> {
                // 兜底: 当作本地绝对路径处理
                File(url).readText(Charsets.UTF_8)
            }
        }
    }
}
