@file:OptIn(ExperimentalFoundationApi::class)

package com.pm.manager.ui
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pm.manager.core.ImageLoader
import com.pm.manager.core.joinPath
import com.pm.manager.model.SiteFile
import com.pm.manager.ui.components.combinedClickableNoRipple
import com.pm.manager.ui.components.fileKindIcon
import com.pm.manager.ui.components.fileKindTint
import com.pm.manager.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件列表 / 网格双视图。
 * - 列表：经典行布局，信息密度高；
 * - 网格：每格缩略图（图片走采样缓存，避免大图 OOM），适合浏览图片资源。
 */
@Composable
fun FileList(
    files: List<SiteFile>,
    currentPath: List<String>,
    viewMode: com.pm.manager.core.ViewMode,
    selected: Set<String>,
    selectMode: Boolean,
    showThumbnails: Boolean,
    onOpen: (SiteFile) -> Unit,
    onToggleSelect: (SiteFile) -> Unit,
    onLongPress: (SiteFile) -> Unit,
    onItemMenu: (SiteFile) -> Unit
) {
    if (files.isEmpty()) {
        com.pm.manager.ui.components.EmptyState("此目录为空", androidx.compose.material.icons.Icons.Rounded.FolderOpen)
        return
    }
    AnimatedContent(
        targetState = viewMode,
        transitionSpec = {
            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(160))
        },
        label = "viewMode"
    ) { mode ->
        when (mode) {
            com.pm.manager.core.ViewMode.LIST -> ListRows(
                files, currentPath, selected, selectMode, onOpen, onToggleSelect, onLongPress, onItemMenu
            )
            com.pm.manager.core.ViewMode.GRID -> GridCells(
                files, currentPath, selected, selectMode, showThumbnails, onOpen, onToggleSelect, onLongPress, onItemMenu
            )
        }
    }
}

@Composable
private fun ListRows(
    files: List<SiteFile>,
    currentPath: List<String>,
    selected: Set<String>,
    selectMode: Boolean,
    onOpen: (SiteFile) -> Unit,
    onToggleSelect: (SiteFile) -> Unit,
    onLongPress: (SiteFile) -> Unit,
    onItemMenu: (SiteFile) -> Unit
) {
    val c = LocalAppColors.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        items(files, key = { it.name }) { f ->
            val isSel = selected.contains(f.name)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) c.tealSoft else c.cardBg)
                    .combinedClickableNoRipple(
                        onClick = { if (selectMode) onToggleSelect(f) else onOpen(f) },
                        onLongClick = { onLongPress(f) }
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (f.isDir) {
                    Icon(fileKindIcon(f.kind), contentDescription = null, tint = fileKindTint(f.kind), modifier = Modifier.size(24.dp))
                } else {
                    ThumbOrIcon(currentPath + f.name, f, showThumbnails = false)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = c.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(f.subtitle(), color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onItemMenu(f) }) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.MoreVert, contentDescription = "更多", tint = c.textTertiary)
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun GridCells(
    files: List<SiteFile>,
    currentPath: List<String>,
    selected: Set<String>,
    selectMode: Boolean,
    showThumbnails: Boolean,
    onOpen: (SiteFile) -> Unit,
    onToggleSelect: (SiteFile) -> Unit,
    onLongPress: (SiteFile) -> Unit,
    onItemMenu: (SiteFile) -> Unit
) {
    val c = LocalAppColors.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
    ) {
        items(files, key = { it.name }) { f ->
            val isSel = selected.contains(f.name)
            Column(
                Modifier
                    .padding(4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSel) c.tealSoft else c.cardBg)
                    .combinedClickableNoRipple(
                        onClick = { if (selectMode) onToggleSelect(f) else onOpen(f) },
                        onLongClick = { onLongPress(f) }
                    )
                    .then(if (isSel) Modifier.border(2.dp, c.teal, RoundedCornerShape(12.dp)) else Modifier)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(c.appBg),
                    contentAlignment = Alignment.Center
                ) {
                    if (f.isDir) {
                        Icon(fileKindIcon(f.kind), contentDescription = null, tint = fileKindTint(f.kind), modifier = Modifier.size(40.dp))
                    } else {
                        ThumbOrIcon(currentPath + f.name, f, showThumbnails)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    f.name,
                    color = c.textPrimary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 列表行或网格格里的图标：目录/非图片直接画图标；图片在网格+开启缩略图时异步加载缩略图。 */
@Composable
private fun ThumbOrIcon(relPath: List<String>, f: SiteFile, showThumbnails: Boolean) {
    val c = LocalAppColors.current
    if (showThumbnails && f.kind == com.pm.manager.model.FileKind.IMAGE) {
        val ctx = LocalContext.current
        val bmp by produceState<android.graphics.Bitmap?>(null, relPath) {
            value = withContext(Dispatchers.IO) {
                ImageLoader.load(ctx, joinPath(relPath), relPath, maxPx = 360).getOrNull()
            }
        }
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp!!.asImageBitmap(),
                contentDescription = f.name,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = c.teal, modifier = Modifier.size(18.dp))
            }
        }
    } else {
        Icon(fileKindIcon(f.kind), contentDescription = null, tint = fileKindTint(f.kind), modifier = Modifier.size(24.dp))
    }
}
