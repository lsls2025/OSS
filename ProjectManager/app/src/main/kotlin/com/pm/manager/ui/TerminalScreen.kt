package com.pm.manager.ui
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pm.manager.core.ToastBus
import com.pm.manager.core.joinPath
import com.pm.manager.terminal.TerminalClient
import com.pm.manager.ui.components.TopBar
import androidx.compose.material.icons.rounded.*
import com.pm.manager.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

private const val MAX_LINES = 500

@Composable
fun TerminalScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    val client = remember { TerminalClient() }

    var lines = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var cwd by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var histIdx by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val chipsScroll = rememberScrollState()

    fun append(text: String) {
        if (text.isEmpty()) return
        val split = text.split("\n")
        repeat(split.size) { lines.add(split[it]) }
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    fun run(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        history.add(trimmed)
        histIdx = history.size
        append("$ ${trimmed}")
        if (trimmed == "clear") { lines.clear(); input = TextFieldValue(""); return }
        scope.launch {
            val res = runCatching { client.execute(trimmed, cwd) }.getOrDefault(com.pm.manager.terminal.TermResult("执行异常", cwd))
            cwd = res.cwd
            append(res.output)
        }
    }

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }

    Column(Modifier.fillMaxSize().background(c.terminalBg)) {
        TopBar(
            title = "终端",
            subtitle = if (cwd.isBlank()) "站点根目录" else cwd,
            onBack = onClose,
            actions = {
                IconButton(onClick = { lines.clear() }) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.DeleteSweep, contentDescription = "清屏", tint = c.terminalText)
                }
            }
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(10.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            items(lines) { line ->
                Text(line, color = c.terminalText, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
        // 常用命令快捷条
        Row(Modifier.fillMaxWidth().horizontalScroll(chipsScroll).background(c.terminalBar).padding(horizontal = 8.dp, vertical = 6.dp)) {
            listOf("ls -la", "pwd", "df -h", "free -h", "top -n1", "ps aux", "cat", "cd ..", "clear").forEach { cmd ->
                Box(
                    Modifier.padding(end = 6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(c.terminalBg).clickable { run(cmd); input = TextFieldValue("") }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(cmd, color = c.terminalText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
        // 输入区
        Row(
            Modifier.fillMaxWidth().background(c.terminalBar).padding(horizontal = 10.dp, vertical = 8.dp).imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$ ", color = c.terminalText, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).onKeyEvent { ev ->
                    when (ev.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> { if (histIdx > 0) { histIdx--; input = TextFieldValue(history[histIdx]) }; true }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { if (histIdx < history.size - 1) { histIdx++; input = TextFieldValue(history[histIdx]) } else { histIdx = history.size; input = TextFieldValue("") }; true }
                        else -> false
                    }
                },
                textStyle = TextStyle(color = c.terminalText, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.terminalText),
                decorationBox = { inner -> inner() }
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { run(input.text); input = TextFieldValue("") }) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Send, contentDescription = "执行", tint = c.terminalText)
            }
        }
    }
}
