package com.pm.manager.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pm.manager.core.AppSettings
import com.pm.manager.core.SortField
import com.pm.manager.core.joinPath
import com.pm.manager.core.parsePath
import com.pm.manager.model.SiteFile
import com.pm.manager.ui.components.ChoiceDialog
import com.pm.manager.ui.components.ChoiceItem
import com.pm.manager.ui.components.clickableNoRipple
import com.pm.manager.ui.components.fileKindIcon
import com.pm.manager.ui.components.fileKindTint
import androidx.compose.material.icons.rounded.*
import com.pm.manager.ui.theme.LocalAppColors

/** 主界面顶栏：标题 + 连接状态 + 搜索/书签/排序/视图/更多。路径由下方面包屑展示，避免顶栏被挤压。 */
@Composable
fun FileManagerTopBar(
    settings: AppSettings,
    pushStatus: String,
    currentPath: List<String>,
    searchActive: Boolean,
    searchQuery: String,
    onSearchActiveChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onMenu: () -> Unit,
    onSort: () -> Unit,
    onToggleView: () -> Unit,
    onBookmarks: () -> Unit
) {
    val c = LocalAppColors.current
    Column(Modifier.background(c.cardBg).fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "文件管理",
                color = c.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val connected = pushStatus == "已连接"
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (connected) c.success else if (pushStatus.isEmpty()) c.textTertiary else c.warning)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                if (searchActive) {
                    onSearchActiveChange(false)
                    onSearchQueryChange("")
                } else {
                    onSearchActiveChange(true)
                }
            }) {
                Icon(
                    androidx.compose.material.icons.Icons.Rounded.Search,
                    contentDescription = "搜索",
                    tint = if (searchActive) c.teal else c.textPrimary
                )
            }
            IconButton(onClick = onBookmarks) {
                Icon(androidx.compose.material.icons.Icons.Rounded.BookmarkBorder, contentDescription = "书签", tint = c.textPrimary)
            }
            IconButton(onClick = onSort) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Sort, contentDescription = "排序", tint = c.textPrimary)
            }
            IconButton(onClick = onToggleView) {
                Icon(
                    if (settings.viewMode == com.pm.manager.core.ViewMode.LIST) androidx.compose.material.icons.Icons.Rounded.GridView else androidx.compose.material.icons.Icons.Rounded.ViewList,
                    contentDescription = "切换视图", tint = c.textPrimary
                )
            }
            IconButton(onClick = onMenu) {
                Icon(androidx.compose.material.icons.Icons.Rounded.MoreVert, contentDescription = "更多", tint = c.textPrimary)
            }
        }
        AnimatedVisibility(
            visible = searchActive,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("搜索当前目录…", color = c.textTertiary, fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = c.appBg, unfocusedContainerColor = c.appBg,
                        focusedBorderColor = c.teal, unfocusedBorderColor = c.divider
                    )
                )
            }
        }
    }
}

/** 路径面包屑：可点击回退到任意层级。 */
@Composable
fun BreadcrumbBar(currentPath: List<String>, settings: AppSettings, onNavigate: (Int) -> Unit) {
    val c = LocalAppColors.current
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).background(c.cardBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BreadcrumbChip("根目录", true) { onNavigate(-1) }
        currentPath.forEachIndexed { i, seg ->
            Text(" / ", color = c.textTertiary, fontSize = 13.sp)
            BreadcrumbChip(seg, i == currentPath.lastIndex) { onNavigate(i) }
        }
    }
}

@Composable
private fun BreadcrumbChip(label: String, isLast: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Text(
        label,
        color = if (isLast) c.teal else c.textSecondary,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickableNoRipple(onClick).padding(vertical = 2.dp)
    )
}

/** 小文字按钮（剪贴板条用）。 */
@Composable
fun TextButton2(text: String, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Text(
        text,
        color = c.teal,
        fontSize = 13.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

/** 文件详情弹窗。 */
@Composable
fun FileDetailsDialog(f: SiteFile, currentPath: List<String>, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    com.pm.manager.ui.components.PureWhiteDialog(onDismiss) {
        val c = LocalAppColors.current
        Column(Modifier.padding(20.dp).width(320.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(fileKindIcon(f.kind), contentDescription = null, tint = fileKindTint(f.kind), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(f.name, color = c.textPrimary, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            DetailRow("类型", if (f.isDir) "文件夹" else (f.ext.ifEmpty { "文件" }))
            DetailRow("大小", if (f.isDir) "—" else com.pm.manager.core.formatBytes(f.size))
            DetailRow("修改时间", com.pm.manager.core.formatFullTime(f.modifiedMillis))
            DetailRow("路径", joinPath(currentPath + f.name))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("重命名", modifier = Modifier.weight(1f), onClick = { onAction("rename") })
                ActionChip(if (f.isDir) "打开" else "详情", modifier = Modifier.weight(1f), onClick = { onAction("details") })
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("复制", modifier = Modifier.weight(1f), onClick = { onAction("copy") })
                ActionChip("剪切", modifier = Modifier.weight(1f), onClick = { onAction("move") })
                if (!f.isDir) ActionChip("下载", modifier = Modifier.weight(1f), onClick = { onAction("download") })
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                ActionChip("删除", danger = true, modifier = Modifier.weight(1f), onClick = { onAction("delete") })
            }
        }
    }
}

@Composable
private fun DetailRow(k: String, v: String) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(k, color = c.textTertiary, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(v, color = c.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionChip(text: String, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (danger) c.danger.copy(alpha = 0.1f) else androidx.compose.ui.graphics.Color(0xFFF4F6F8))
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (danger) c.danger else c.textPrimary, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

/** 书签目录弹窗。 */
@Composable
fun BookmarksDialog(
    bookmarks: List<String>,
    currentDir: String,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit
) {
    com.pm.manager.ui.components.PureWhiteDialog(onClose) {
        val c = LocalAppColors.current
        Column(Modifier.padding(20.dp).width(320.dp)) {
            Text("书签目录", color = c.textPrimary, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (bookmarks.isEmpty()) {
                Text("还没有书签，进入目录后点击「更多 → 添加书签」。", color = c.textSecondary, fontSize = 13.sp)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    bookmarks.forEach { rel ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(rel) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.Bookmark, contentDescription = null, tint = c.teal, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(if (rel.isEmpty()) "根目录" else rel, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { onRemove(rel) }) {
                                Icon(androidx.compose.material.icons.Icons.Rounded.Delete, contentDescription = "移除", tint = c.textTertiary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(androidx.compose.ui.graphics.Color(0xFFF4F6F8)).clickable(onClick = onAdd).padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("添加当前目录", color = c.textPrimary, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
    }
}

/** 列表项右键菜单选项。 */
fun buildItemMenu(f: SiteFile, isSelected: Boolean, onAction: (String) -> Unit): List<ChoiceItem> = buildList {
    if (!isSelected) {
        if (f.ext == "html" || f.ext == "htm") {
            add(ChoiceItem("编辑", androidx.compose.material.icons.Icons.Rounded.Edit) { onAction("open") })
            add(ChoiceItem("预览", androidx.compose.material.icons.Icons.Rounded.Visibility) { onAction("preview-html") })
        } else {
            add(ChoiceItem(if (f.isDir) "打开" else "预览", androidx.compose.material.icons.Icons.Rounded.OpenInNew) { onAction("open") })
        }
    }
    add(ChoiceItem(if (isSelected) "取消选择" else "选择", if (isSelected) androidx.compose.material.icons.Icons.Rounded.CheckCircle else androidx.compose.material.icons.Icons.Rounded.CheckBox) { onAction("select") })
    add(ChoiceItem("重命名", androidx.compose.material.icons.Icons.Rounded.Edit) { onAction("rename") })
    add(ChoiceItem("复制到剪贴板", androidx.compose.material.icons.Icons.Rounded.ContentCopy) { onAction("copy") })
    add(ChoiceItem("剪切到剪贴板", androidx.compose.material.icons.Icons.Rounded.ContentCut) { onAction("move") })
    if (!f.isDir) add(ChoiceItem("下载", androidx.compose.material.icons.Icons.Rounded.Download) { onAction("download") })
    if (!f.isDir && f.ext == "zip") add(ChoiceItem("解压到当前目录", androidx.compose.material.icons.Icons.Rounded.FolderZip) { onAction("unzip") })
    add(ChoiceItem("详情", androidx.compose.material.icons.Icons.Rounded.Info) { onAction("details") })
    add(ChoiceItem("删除", androidx.compose.material.icons.Icons.Rounded.Delete, danger = true) { onAction("delete") })
}

/** 排序比较器。 */
fun buildSorter(s: AppSettings): java.util.Comparator<SiteFile> {
    val dirFirst = if (s.dirsFirst) compareBy<SiteFile> { !it.isDir } else compareBy<SiteFile> { 0 }
    val byField = when (s.sortField) {
        SortField.NAME -> compareBy<SiteFile> { it.name.lowercase() }
        SortField.SIZE -> compareBy<SiteFile> { it.size }
        SortField.TIME -> compareBy<SiteFile> { it.modifiedMillis }
        SortField.TYPE -> compareBy<SiteFile> { it.ext }
    }
    return dirFirst.then(if (s.sortAsc) byField else byField.reversed())
}
