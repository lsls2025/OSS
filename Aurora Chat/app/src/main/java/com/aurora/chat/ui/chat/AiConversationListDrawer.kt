package com.aurora.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI 对话列表抽屉（极简实现，带轻量过渡动画）。
 *
 * 动画：遮罩淡入淡出，面板轻微水平滑入/滑出（tween 180~260ms），
 * 两者使用相互独立的 AnimatedVisibility，避免整块滑块感。
 * 遮罩点击关闭；面板靠右定位（宽度 264dp），左侧露出主聊天区。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AiConversationListDrawer(
    visible: Boolean,
    onClose: () -> Unit,
    aiConvs: List<AiConvMeta>,
    activeAiConvId: Long,
    currentUserId: Long,
    onNewConversation: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onRenameConversation: (Long, String) -> Unit,
    onBatchDeleteConversations: (List<Long>) -> Unit
) {
    // 长按菜单：当前打开菜单的会话 id
    var menuForId by remember { mutableStateOf<Long?>(null) }
    // 重命名对话框
    var renameForId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    // 删除确认对话框
    var deleteForId by remember { mutableStateOf<Long?>(null) }
    // 批量删除：选择模式、已选 id、确认弹窗
    var batchSelecting by remember { mutableStateOf(false) }
    var batchSelected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var batchDeleteConfirm by remember { mutableStateOf(false) }
    // 搜索会话：按标题过滤
    var searchText by remember { mutableStateOf("") }

    val hasBatchSelection = batchSelecting && batchSelected.isNotEmpty()

    // 关闭抽屉：先清理面板内部状态，再通知上层
    val closeDrawer = {
        menuForId = null
        renameForId = null
        renameText = ""
        deleteForId = null
        batchSelecting = false
        batchSelected = emptySet()
        batchDeleteConfirm = false
        searchText = ""
        onClose()
    }

    Box(Modifier.fillMaxSize()) {
        // 半透明遮罩：淡入淡出，点击关闭
        AnimatedVisibility(
            modifier = Modifier.fillMaxSize(),
            visible = visible,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(180))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable { closeDrawer() }
            )
        }

        // 右侧面板：轻微水平滑入/滑出 + 淡入淡出，靠右、宽度 264dp
        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(264.dp),
            visible = visible,
            enter = slideInHorizontally(
                animationSpec = tween(260),
                initialOffsetX = { 80 }
            ) + fadeIn(animationSpec = tween(260)),
            exit = slideOutHorizontally(
                animationSpec = tween(180),
                targetOffsetX = { 80 }
            ) + fadeOut(animationSpec = tween(180))
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    // 拦截点击，防止事件穿透到遮罩误触发关闭
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {},
                color = Color(0xFFFAFBFC),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部：标题 + 批量删除 + 新建会话（批量删除放在新建会话左边）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AI 对话",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937),
                        modifier = Modifier.weight(1f)
                    )
                    // 单个按钮：无选中时是「批量删除」，一旦至少选中 1 个就变成「删除」
                    TextButton(onClick = {
                        when {
                            hasBatchSelection -> batchDeleteConfirm = true      // 有选中 → 弹确认
                            batchSelecting -> {                                // 选择模式且未选中 → 退出选择
                                batchSelecting = false
                                batchSelected = emptySet()
                            }
                            else -> {                                          // 默认 → 进入选择模式
                                batchSelecting = true
                                batchSelected = emptySet()
                            }
                        }
                    }) {
                        Text(
                            if (hasBatchSelection) "删除" else "批量删除",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (hasBatchSelection) Color(0xFFDC2626) else Color(0xFF1E40AF)
                        )
                    }
                    TextButton(onClick = onNewConversation) {
                        Text("新建会话", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                    }
                }
                HorizontalDivider(color = Color(0xFFE5EAF0), thickness = 1.dp)

                // 分割线下方：搜索输入框
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    singleLine = true,
                    placeholder = { Text("搜索会话", fontSize = 14.sp, color = Color(0xFF9CA3AF)) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )

                val filtered = if (searchText.isBlank()) aiConvs
                    else aiConvs.filter { it.title.contains(searchText.trim(), ignoreCase = true) }

                // 每个会话的预览 = 该会话自己的最后一条消息（按会话 id 隔离，绝不串到其它会话）。
                // 数据来自 saveLocalChatMessages 落盘的「每条会话最后一条消息」缓存（getAiConvPreview）。
                // 依赖 LocalChatRefreshTick：每次消息落盘（含 AI 流式回复写入）都会 bump，预览随之刷新，
                // 保证发送/接收后重开抽屉时显示的是最新最后一条，而非过期缓存。
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val refreshTick by com.aurora.chat.ui.chat.LocalChatRefreshTick.flow.collectAsState()
                val previews = remember(filtered, activeAiConvId, refreshTick) {
                    filtered.associate { it.id to com.aurora.chat.ui.chat.getAiConvPreview(ctx, currentUserId, it.id) }
                }

                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (searchText.isBlank()) "暂无会话" else "未找到相关会话", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { meta ->
                            AiConvListItem(
                                meta = meta,
                                active = meta.id == activeAiConvId,
                                preview = previews[meta.id] ?: "",
                                menuOpen = menuForId == meta.id,
                                selecting = batchSelecting,
                                selected = batchSelected.contains(meta.id),
                                onClick = {
                                    if (batchSelecting) {
                                        // 选择模式下：单点切换勾选
                                        batchSelected = if (batchSelected.contains(meta.id))
                                            batchSelected - meta.id
                                        else batchSelected + meta.id
                                    } else {
                                        onOpenConversation(meta.id)
                                    }
                                },
                                onMenuOpen = { menuForId = meta.id },
                                onMenuDismiss = { menuForId = null },
                                onRename = {
                                    menuForId = null
                                    renameText = meta.title
                                    renameForId = meta.id
                                },
                                onDelete = {
                                    menuForId = null
                                    deleteForId = meta.id
                                }
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // 重命名对话框（输入新名称）
    renameForId?.let { id ->
        AlertDialog(
            onDismissRequest = { renameForId = null },
            title = { Text("重命名对话", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    placeholder = { Text("输入新名称") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameConversation(id, renameText)
                    renameForId = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameForId = null }) { Text("取消") }
            }
        )
    }

    // 删除确认对话框
    deleteForId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteForId = null },
            title = { Text("删除对话", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "确定删除「${aiConvs.firstOrNull { it.id == id }?.title ?: ""}」吗？此操作不可恢复。",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteConversation(id)
                    deleteForId = null
                }) { Text("删除", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteForId = null }) { Text("取消") }
            }
        )
    }

    // 批量删除确认对话框
    if (batchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { batchDeleteConfirm = false },
            title = { Text("删除对话", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "确定删除选中的 ${batchSelected.size} 个对话吗？此操作不可恢复。",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onBatchDeleteConversations(batchSelected.toList())
                    batchSelected = emptySet()
                    batchSelecting = false
                    batchDeleteConfirm = false
                }) { Text("删除", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { batchDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 单个会话项：单击切换，长按弹出操作菜单（重命名/删除）；选择模式下单击改为勾选，不弹菜单，左侧显示勾选指示。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiConvListItem(
    meta: AiConvMeta,
    active: Boolean,
    preview: String = "",
    menuOpen: Boolean,
    selecting: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    when {
                        selected -> Color(0xFFDCEEFF)
                        active -> Color(0xFFEAF1FF)
                        else -> Color.Transparent
                    }
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (selecting) null else onMenuOpen
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 选择模式：左侧显示勾选框
            if (selecting) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(if (selected) Color(0xFF1E40AF) else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (selected) Color(0xFF1E40AF) else Color(0xFFB8C1CE),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = meta.title,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (active) Color(0xFF1E40AF) else Color(0xFF1F2937),
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
                // 预览：该会话自己的最后一条消息；无内容时回退为「暂无消息」
                Text(
                    text = if (preview.isNotBlank()) preview else "暂无消息",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        if (!selecting) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
            DropdownMenuItem(
                text = { Text("重命名", fontSize = 14.sp) },
                onClick = onRename
            )
            DropdownMenuItem(
                text = { Text("删除", fontSize = 14.sp, color = Color(0xFFDC2626)) },
                onClick = onDelete
            )
        }
    }
    }
}
