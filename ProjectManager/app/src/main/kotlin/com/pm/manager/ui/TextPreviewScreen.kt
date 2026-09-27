package com.pm.manager.ui
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pm.manager.core.AppPrefs
import com.pm.manager.core.ToastBus
import com.pm.manager.core.joinPath
import com.pm.manager.core.parsePath
import com.pm.manager.terminal.SiteFiles
import com.pm.manager.ui.components.ConfirmDialog
import com.pm.manager.ui.components.TopBar
import androidx.compose.material.icons.rounded.*
import com.pm.manager.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

@Composable
fun TextPreviewScreen(content: String, path: List<String>, readOnly: Boolean, onClose: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    val s by AppPrefs.settings.collectAsStateWithLifecycle()

    var value by remember { mutableStateOf(TextFieldValue(content)) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var showUnsaved by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val hscroll = rememberScrollState()
    val listState = rememberLazyListState()
    val lines = remember(content) { content.split("\n") }

    fun doClose() {
        if (dirty && !readOnly) showUnsaved = true else onClose()
    }
    fun save() {
        saving = true
        scope.launch {
            runCatching { SiteFiles.save(path, value.text) }
                .onSuccess { dirty = false; onSave(value.text); ToastBus.show(context, "已保存") }
                .onFailure { ToastBus.show(context, it.message ?: "保存失败") }
            saving = false
        }
    }
    fun findNext() {
        if (findText.isBlank()) return
        val from = if (value.selection.start >= value.text.length) 0 else value.selection.start + 1
        val idx = value.text.indexOf(findText, from, ignoreCase = true)
        val start = if (idx < 0) value.text.indexOf(findText, ignoreCase = true) else idx
        if (start >= 0) value = value.copy(selection = TextRange(start, start + findText.length))
    }

    Column(Modifier.fillMaxSize().background(c.editorBg)) {
        TopBar(
            title = path.lastOrNull() ?: "预览",
            subtitle = joinPath(path),
            onBack = { doClose() },
            actions = {
                if (!readOnly) {
                    IconButton(onClick = { findOpen = !findOpen }) { Icon(androidx.compose.material.icons.Icons.Rounded.Search, contentDescription = "查找", tint = Color.White) }
                    IconButton(onClick = { save() }, enabled = dirty && !saving) { Icon(androidx.compose.material.icons.Icons.Rounded.Save, contentDescription = "保存", tint = if (dirty) c.teal else c.textTertiary) }
                }
            }
        )
        if (readOnly) {
            Box(Modifier.fillMaxWidth().background(c.editorSurface).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("文件较大（>2MB），仅提供只读预览。", color = c.terminalError, fontSize = 12.sp)
            }
        }
        if (findOpen && !readOnly) {
            Row(Modifier.fillMaxWidth().background(c.editorSurface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = findText, onValueChange = { findText = it },
                    modifier = Modifier.weight(1f).background(c.editorBg, RoundedCornerShape(8.dp)).padding(8.dp),
                    textStyle = TextStyle(color = c.editorText, fontSize = 13.sp),
                    cursorBrush = SolidColor(c.editorText),
                    decorationBox = { inner -> inner() }
                )
                Spacer(Modifier.width(6.dp))
                Text("下一个", color = c.teal, fontSize = 12.sp, modifier = Modifier.clickable { findNext() }.padding(6.dp))
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = replaceText, onValueChange = { replaceText = it },
                    modifier = Modifier.weight(1f).background(c.editorBg, RoundedCornerShape(8.dp)).padding(8.dp),
                    textStyle = TextStyle(color = c.editorText, fontSize = 13.sp),
                    cursorBrush = SolidColor(c.editorText),
                    decorationBox = { inner -> inner() }
                )
                Spacer(Modifier.width(6.dp))
                Text("替换全部", color = c.teal, fontSize = 12.sp, modifier = Modifier.clickable {
                    if (findText.isNotBlank()) { value = value.copy(text = value.text.replace(findText, replaceText, ignoreCase = true)); dirty = true }
                }.padding(6.dp))
            }
        }

        if (readOnly) {
            // 只读：带行号的 LazyColumn，大文件也能流畅预览
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                itemsIndexed(lines) { i, line ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${i + 1}", color = c.editorGutter, fontFamily = FontFamily.Monospace, fontSize = (s.editorFontSize - 2).sp, modifier = Modifier.width(48.dp).padding(end = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        SelectionContainer {
                            Text(line.ifEmpty { " " }, color = c.editorText, fontFamily = FontFamily.Monospace, fontSize = s.editorFontSize.sp, lineHeight = (s.editorFontSize + 4).sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            val wrap = s.editorWordWrap
            BasicTextField(
                value = value,
                onValueChange = { value = it; dirty = true },
                modifier = Modifier.fillMaxSize().padding(12.dp)
                    .then(if (wrap) Modifier.verticalScroll(scroll) else Modifier.horizontalScroll(hscroll).verticalScroll(scroll)),
                textStyle = TextStyle(color = c.editorText, fontFamily = FontFamily.Monospace, fontSize = s.editorFontSize.sp, lineHeight = (s.editorFontSize + 4).sp),
                cursorBrush = SolidColor(c.teal),
                readOnly = false,
                decorationBox = { inner -> inner() }
            )
        }
    }

    if (showUnsaved) {
        ConfirmDialog(
            title = "未保存的修改", confirmText = "不保存并离开",
            message = "当前文件有未保存的修改，离开将丢失这些改动。",
            onConfirm = { showUnsaved = false; onClose() },
            onDismiss = { showUnsaved = false }
        )
    }
}
