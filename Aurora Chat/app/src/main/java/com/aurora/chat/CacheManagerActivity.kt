package com.aurora.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.theme.AuroraChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 存储管理（系统「设置→应用→Aurora Chat→存储→管理空间」入口）。
 * 统一为 Compose 声明式 UI（原实现依赖已不存在的 DirSizeUtil 与 XML layout）。
 */
class CacheManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AuroraChatTheme {
                CacheManagerScreen(onBack = { finish() })
            }
        }
    }

    private fun cacheSizeBytes(): Long = dirSize(cacheDir)

    private fun otherSizeBytes(): Long {
        var total = dirSize(filesDir)
        getExternalFilesDir(null)?.let { total += dirSize(it) }
        getExternalCacheDir()?.let { total += dirSize(it) }
        return total
    }

    private fun clearCache() {
        runCatching { cacheDir.deleteRecursively() }
    }

    private fun clearOther() {
        runCatching { filesDir.deleteRecursively() }
        runCatching { getExternalFilesDir(null)?.deleteRecursively() }
        runCatching { getExternalCacheDir()?.deleteRecursively() }
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach { f ->
            total += if (f.isDirectory) dirSize(f) else runCatching { f.length() }.getOrDefault(0L)
        }
        return total
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        return when {
            kb < 1024 -> "%.2f KB".format(kb)
            kb < 1024 * 1024 -> "%.2f MB".format(kb / 1024)
            else -> "%.2f GB".format(kb / 1024 / 1024)
        }
    }

    @Composable
    private fun CacheManagerScreen(onBack: () -> Unit) {
        val context = LocalContext.current
        var cacheSizeText by remember { mutableStateOf("计算中...") }
        var otherSizeText by remember { mutableStateOf("计算中...") }
        var pendingClear by remember { mutableStateOf<Int?>(null) }
        val scope = rememberCoroutineScope()

        fun refresh() {
            scope.launch {
                val c = withContext(Dispatchers.IO) { cacheSizeBytes() }
                val o = withContext(Dispatchers.IO) { otherSizeBytes() }
                cacheSizeText = formatSize(c)
                otherSizeText = formatSize(o)
            }
        }
        LaunchedEffect(Unit) { refresh() }

        Column(Modifier.fillMaxSize().background(Color(0xFFF2F2F7))) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF1E40AF))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "←",
                    color = Color.White,
                    fontSize = 22.sp,
                    modifier = Modifier.clickable { onBack() }
                )
                Spacer(Modifier.width(12.dp))
                Text("存储管理", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("缓存", fontSize = 14.sp, color = Color(0xFF6B7280))
                        Spacer(Modifier.height(4.dp))
                        Text(cacheSizeText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { pendingClear = 0 },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("清理缓存", color = Color.White) }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("其他数据", fontSize = 14.sp, color = Color(0xFF6B7280))
                        Spacer(Modifier.height(4.dp))
                        Text(otherSizeText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { pendingClear = 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("清理其他数据", color = Color.White) }
                    }
                }
            }
        }

        if (pendingClear != null) {
            val isCache = pendingClear == 0
            AlertDialog(
                onDismissRequest = { pendingClear = null },
                title = { Text(if (isCache) "清理缓存" else "清理其他数据") },
                text = { Text("确定要清理${if (isCache) "缓存" else "其他数据"}吗？此操作不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            if (isCache) clearCache() else clearOther()
                            withContext(Dispatchers.Main) {
                                refresh()
                                pendingClear = null
                            }
                        }
                    }) { Text("确认清理") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingClear = null }) { Text("取消") }
                }
            )
        }
    }
}
