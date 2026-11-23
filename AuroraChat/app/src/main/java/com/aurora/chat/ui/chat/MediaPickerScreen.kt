package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.aurora.chat.data.local.VideoThumbCache
import com.aurora.chat.ui.common.createExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashSet

/**
 * 应用内相册选择器（自绘，替代系统 PickMultipleVisualMedia）。
 *
 * 为什么自绘：系统相册选择器无法做「按相册筛选 / 视频图片混选筛选 / 原图开关」，
 * 样式也和 App 完全不搭。
 *
 * 布局（自上而下）：
 *  · 顶栏：关闭 × ｜ 中间筛选下拉（图片和视频 / 图片 / 视频 / 各相册）｜右侧留白对齐
 *  · 网格：4 列，视频右下角显示时长，右上角是圆形勾选（选中 = 品牌深蓝 + 序号）
 *  · 底栏：原图勾选（勾选后在下方显示「已勾选 · 共 xx MB」）+ 右下角深蓝色发送按钮
 *
 * 颜色约束：整体白底（不搬系统/微信的深灰皮肤），强调色统一用品牌深蓝 [PickerBrandBlue]。
 */

/** 品牌深蓝：与 App 其它主按钮（发送、转账等）保持一致 */
private val PickerBrandBlue = Color(0xFF1E40AF)
private val PickerDivider get() = com.aurora.chat.ui.theme.appDivider()
private val PickerTextPrimary = com.aurora.chat.ui.theme.appTextPrimary()
private val PickerTextSecondary = com.aurora.chat.ui.theme.appTextSecondary()

/** 一条相册媒体（图片或视频） */
data class PickerMedia(
    val id: Long,
    val uri: Uri,
    val albumId: Long,
    val albumName: String,
    val isVideo: Boolean,
    val durationMs: Long,
    val size: Long
)

/** 顶栏筛选：全部 / 只看图片 / 只看视频 */
private enum class MediaTypeFilter(val label: String) {
    All("图片和视频"), Image("图片"), Video("视频")
}

/** 当前是否已拿到读取相册的权限（Android 13+ 拆成图片/视频两个权限） */
private fun hasMediaPermission(ctx: Context): Boolean {
    val perms = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    // 部分授权（Android 14 的「仅选择部分照片」）也算可用：MediaStore 会只返回授权过的那部分
    return perms.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
}

private fun mediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** 读取设备相册：图片 + 视频，按时间倒序，最多 [limit] 条。 */
private fun loadPickerMedia(ctx: Context, limit: Int = 3000): List<PickerMedia> {
    val result = ArrayList<PickerMedia>()
    val collection = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Files.getContentUri("external")
    }
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.MIME_TYPE,
        "bucket_id",
        "bucket_display_name",
        "duration"
    )
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)"
    val args = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
    )
    runCatching {
        ctx.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val typeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val bucketIdIdx = c.getColumnIndex("bucket_id")
            val bucketNameIdx = c.getColumnIndex("bucket_display_name")
            val durationIdx = c.getColumnIndex("duration")
            while (c.moveToNext() && result.size < limit) {
                val id = c.getLong(idIdx)
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                // 0 字节的坏条目直接跳过（点开是黑图）
                if (size <= 0L) continue
                val isVideo = c.getInt(typeIdx) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val albumId = if (bucketIdIdx >= 0) c.getLong(bucketIdIdx) else 0L
                val albumName = if (bucketNameIdx >= 0) c.getString(bucketNameIdx) else null
                result.add(
                    PickerMedia(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        albumId = albumId,
                        albumName = albumName?.takeIf { it.isNotBlank() } ?: "其他",
                        isVideo = isVideo,
                        durationMs = if (isVideo && durationIdx >= 0) c.getLong(durationIdx) else 0L,
                        size = size
                    )
                )
            }
        }
    }
    return result
}

/** 毫秒 → m:ss（视频角标） */
private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

/** 字节 → 可读大小（原图总大小提示） */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 全屏相册选择器。
 *
 * @param maxCount 最多可选数量（剩余可发数量）
 * @param onDismiss 关闭（× / 返回）
 * @param onConfirm 发送：返回选中的 Uri 列表 + 是否勾选「原图」（原图 = 不压缩直传）
 */
@Composable
fun MediaPickerScreen(
    maxCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (List<Uri>, Boolean) -> Unit
) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaPermission(ctx)) }
    var media by remember { mutableStateOf<List<PickerMedia>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var typeFilter by remember { mutableStateOf(MediaTypeFilter.All) }
    var albumId by remember { mutableStateOf<Long?>(null) }
    var dropdownOpen by remember { mutableStateOf(false) }
    // LinkedHashSet：保留勾选顺序，序号才和画面上一致
    var selected by remember { mutableStateOf(LinkedHashSet<Long>()) }
    var original by remember { mutableStateOf(false) }
    // 预览已选中：null = 未打开；否则为 HorizontalPager 的起始下标
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    // 预览数据源：点「预览」= 已勾选的列表；点某张图 = 当前筛选下的全部媒体（可左右翻着看）
    var previewItems by remember { mutableStateOf<List<PickerMedia>>(emptyList()) }

    // 对话框铺到状态栏后面时：状态栏完全透明、图标颜色跟随页面
    // （相册白底 = 深色图标；预览黑底 = 白色图标），否则系统会给状态栏刷一层灰
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.WHITE))
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = previewIndex == null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasMediaPermission(ctx) }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(mediaPermissions())
    }
    LaunchedEffect(granted) {
        if (granted) {
            loading = true
            media = withContext(Dispatchers.IO) { loadPickerMedia(ctx) }
            loading = false
        }
    }

    val albums = remember(media) {
        media.groupBy { it.albumId }.map { (id, list) ->
            PickerAlbum(id, list.first().albumName, list.size, list.first().uri)
        }.sortedByDescending { it.count }
    }
    val shown = remember(media, typeFilter, albumId) {
        media.filter { m ->
            (albumId == null || m.albumId == albumId) && when (typeFilter) {
                MediaTypeFilter.All -> true
                MediaTypeFilter.Image -> !m.isVideo
                MediaTypeFilter.Video -> m.isVideo
            }
        }
    }
    val selectedMedia = remember(media, selected) { media.filter { it.id in selected } }
    val selectedBytes = remember(selectedMedia) { selectedMedia.sumOf { it.size } }
    val currentAlbumName = albums.firstOrNull { it.id == albumId }?.name

    // ── 长按拖动多选（对齐微信：按住一格后拖动，划过的格子连续勾选/取消） ──
    // 已组合格子的窗口坐标：拖动时按手指位置反查命中哪一格
    val cellBounds = remember { mutableMapOf<Long, Rect>() }
    // 本次拖动是「加选」还是「取消」：由长按落点那一格当时是否已选决定，整段拖动保持不变
    var dragAddMode by remember { mutableStateOf<Boolean?>(null) }
    // 本次拖动已处理过的格子，避免手指在同一格抖动时反复切换
    val dragVisited = remember { mutableSetOf<Long>() }

    fun applyDragAt(pos: Offset) {
        val add = dragAddMode ?: return
        val id = cellBounds.entries.firstOrNull { it.value.contains(pos) }?.key ?: return
        if (!dragVisited.add(id)) return
        if (add) {
            if (id !in selected && selected.size < maxCount) {
                selected = LinkedHashSet(selected).also { it.add(id) }
            }
        } else {
            selected = LinkedHashSet(selected).also { it.remove(id) }
        }
    }

    Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ── 顶栏：关闭 + 筛选下拉 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = PickerTextPrimary)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(com.aurora.chat.ui.theme.appCardBg())
                            .clickable { dropdownOpen = !dropdownOpen }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            currentAlbumName ?: typeFilter.label,
                            fontSize = 14.sp,
                            color = PickerTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val arrowAngle by animateFloatAsState(
                            targetValue = if (dropdownOpen) 180f else 0f,
                            animationSpec = tween(180),
                            label = "filterArrow"
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "筛选",
                            tint = PickerTextSecondary,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(arrowAngle)
                        )
                    }
                }
                Spacer(Modifier.width(48.dp))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(PickerDivider))

            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    !granted -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("需要相册权限才能查看图片和视频", fontSize = 14.sp, color = PickerTextSecondary)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "去授权",
                            fontSize = 14.sp,
                            color = PickerBrandBlue,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(com.aurora.chat.ui.theme.appSelectedBg())
                                .clickable { permissionLauncher.launch(mediaPermissions()) }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PickerBrandBlue, strokeWidth = 2.5.dp)
                    }
                    shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有可发送的图片或视频", fontSize = 14.sp, color = PickerTextSecondary)
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 1.dp, end = 1.dp, top = 1.dp, bottom = 76.dp)
                    ) {
                        items(shown, key = { it.id }) { item ->
                            val order = selected.indexOf(item.id)
                            PickerCell(
                                item = item,
                                selectedOrder = if (order >= 0) order + 1 else 0,
                                onClick = {
                                    // 点图片本身 = 打开查看（不必先勾选）
                                    val idx = shown.indexOfFirst { it.id == item.id }
                                    if (idx >= 0) {
                                        previewItems = shown
                                        previewIndex = idx
                                    }
                                },
                                onCircleClick = {
                                    if (selected.contains(item.id)) {
                                        selected = LinkedHashSet(selected).also { it.remove(item.id) }
                                    } else if (selected.size >= maxCount) {
                                        android.widget.Toast.makeText(
                                            ctx, "最多同时发送 $maxCount 个", android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        selected = LinkedHashSet(selected).also { it.add(item.id) }
                                    }
                                },
                                onBounds = { id, rect -> cellBounds[id] = rect },
                                onCellGone = { id -> cellBounds.remove(id) },
                                onDragStart = { id, pos ->
                                    dragVisited.clear()
                                    // 起点已选 → 整段改成"取消"；起点未选 → 整段"加选"
                                    dragAddMode = id !in selected
                                    applyDragAt(pos)
                                },
                                onDragMove = { pos -> applyDragAt(pos) },
                                onDragEnd = {
                                    dragAddMode = null
                                    dragVisited.clear()
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── 底栏：原图 + 发送 ──
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(PickerDivider))
            // 用 Box 三向对齐：左=预览、中=原图（真正居中）、右=发送
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .navigationBarsPadding()
                    // 高度随内容长（勾了原图且选中后多一行大小提示），底部留够 12dp 余量，
                    // 不让文字被导航栏压住
                    .heightIn(min = 60.dp)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
            ) {
                // 左：预览已选中的图片/视频
                val canPreview = selected.isNotEmpty()
                Text(
                    "预览",
                    fontSize = 15.sp,
                    color = if (canPreview) PickerTextPrimary else com.aurora.chat.ui.theme.appTextHint(),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = canPreview) {
                            previewItems = selectedMedia
                            previewIndex = 0
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                // 中：原图勾选（勾上后下方显示已勾选状态 + 选中文件总大小）
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { original = !original }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (original) PickerBrandBlue else com.aurora.chat.ui.theme.appCardBg())
                                .border(
                                    1.5.dp,
                                    if (original) PickerBrandBlue else Color(0xFFD1D5DB),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (original) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("原图", fontSize = 14.sp, color = PickerTextPrimary)
                    }
                    // 只有「勾了原图且确实选中了东西」时才多这一行，此时整条栏才向上长高；
                    // 什么都没选不占位，免得凭空把「原图」顶上去
                    if (original && selected.isNotEmpty()) {
                        Text(
                            text = "共 ${formatBytes(selectedBytes)}",
                            fontSize = 10.sp,
                            color = PickerBrandBlue,
                            maxLines = 1
                        )
                    }
                }
                // 右：发送
                val canSend = selected.isNotEmpty()
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .height(40.dp)
                        .widthIn(min = 84.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (canSend) PickerBrandBlue else com.aurora.chat.ui.theme.appDivider())
                        .clickable(enabled = canSend) {
                            val uris = selectedMedia.map { it.uri }
                            onConfirm(uris, original)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (canSend) "发送(${selected.size})" else "发送",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canSend) Color.White else com.aurora.chat.ui.theme.appTextHint()
                    )
                }
            }
        }

        // ── 筛选下拉：铺满顶栏以下的整屏（白底一直到底），并带展开/收起动画 ──
        // 关键：AnimatedVisibility 不能包在 if (dropdownOpen) 里 —— 首次组合时 visible 直接为 true，
        // 会被当成"一开始就可见"，所以之前完全没动画；同时面板必须 fillMaxSize，
        // 否则只有内容那么高，上下会露出后面的网格（看起来像穿透）。
        BackHandler(enabled = dropdownOpen) { dropdownOpen = false }
        AnimatedVisibility(
            visible = dropdownOpen,
            enter = fadeIn(tween(140)) + expandVertically(
                animationSpec = tween(200), expandFrom = Alignment.Top
            ),
            exit = fadeOut(tween(110)) + shrinkVertically(
                animationSpec = tween(170), shrinkTowards = Alignment.Top
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 52.dp)
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .verticalScroll(rememberScrollState())
            ) {
                    MediaTypeFilter.entries.forEach { f ->
                        PickerDropdownRow(
                            cover = null,
                            title = f.label,
                            count = when (f) {
                                MediaTypeFilter.All -> media.size
                                MediaTypeFilter.Image -> media.count { !it.isVideo }
                                MediaTypeFilter.Video -> media.count { it.isVideo }
                            },
                            selected = albumId == null && typeFilter == f,
                            onClick = {
                                typeFilter = f
                                albumId = null
                                dropdownOpen = false
                            }
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(PickerDivider))
                    albums.forEach { album ->
                        PickerDropdownRow(
                            cover = album.cover,
                            title = album.name,
                            count = album.count,
                            selected = albumId == album.id,
                            onClick = {
                                albumId = album.id
                                typeFilter = MediaTypeFilter.All
                                dropdownOpen = false
                            }
                        )
                    }
            }
        }

        // ── 预览已选中：全屏黑底翻页，点右上角 × / 返回键关闭 ──
        // 不能包在 if (previewIndex != null) 里：首次组合 visible 直接是 true → 没有动画；
        // 收起时也需要内容还在，才能把淡出/缩放播完。
        AnimatedVisibility(
            visible = previewIndex != null && previewItems.isNotEmpty(),
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220)),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.96f, animationSpec = tween(180)),
            modifier = Modifier.matchParentSize()
        ) {
            if (previewItems.isNotEmpty()) {
            val pagerState = rememberPagerState(
                initialPage = (previewIndex ?: 0).coerceIn(0, previewItems.lastIndex)
            ) { previewItems.size }
            BackHandler { previewIndex = null }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val item = previewItems[page]
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (item.isVideo) {
                            // 本地视频直接播（content:// 由 DefaultDataSource 自动分流）
                            PickerVideoPlayer(uri = item.uri)
                        } else {
                            AsyncImage(
                                model = item.uri,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { previewIndex = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭预览", tint = Color.White)
                }
                Text(
                    "${pagerState.currentPage + 1}/${previewItems.size}",
                    fontSize = 13.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp)
                )
            }
            }
        }
    }
}

/** 相册（下拉里的一个目录） */
private data class PickerAlbum(val id: Long, val name: String, val count: Int, val cover: Uri)

/** 网格单元格：缩略图 + 视频时长 + 右上角勾选圆 */
@Composable
private fun PickerCell(
    item: PickerMedia,
    selectedOrder: Int,
    onClick: () -> Unit,
    onCircleClick: () -> Unit,
    onBounds: (Long, Rect) -> Unit,
    onCellGone: (Long) -> Unit,
    onDragStart: (Long, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val selected = selectedOrder > 0
    val haptics = LocalHapticFeedback.current
    // 用数组持有布局坐标：避免每次布局都触发重组
    val coordsHolder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // 滑出屏幕后清掉坐标，防止拖动时命中已被回收格子留下的旧矩形
    DisposableEffect(item.id) {
        onDispose { onCellGone(item.id) }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .background(com.aurora.chat.ui.theme.appCardBg())
            .onGloballyPositioned {
                coordsHolder[0] = it
                onBounds(item.id, it.boundsInWindow())
            }
            // 长按后拖动：手指会一直被这一格捕获，按窗口坐标反查划过的格子。
            // 「点击 / 滚动 / 长按拖动」三种意图在这里自己判定：
            //  · 500ms 内抬手 → 点击
            //  · 500ms 内移动超过 slop → 交给列表滚动，什么都不做
            //  · 挺过 500ms → 进入拖动多选，之后的移动全部消费，避免列表跟着滚
            // 注意不能配合 combinedClickable：它长按后会 consumeUntilUp() 吃掉所有移动事件，
            // 表现就是"拖不出这一格"。
            .pointerInput(item.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var movedAway = false
                    var liftedEarly = false
                    var upChange: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    // 注意：break 不能写在非 inline 的 suspend 闭包里（编译不过），一律用标志位退出循环
                    val endedBeforeLongPress =
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var running = true
                            while (running) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || change.changedToUpIgnoreConsumed()) {
                                    liftedEarly = true
                                    upChange = change
                                    running = false
                                } else if (change.isConsumed) {
                                    movedAway = true
                                    running = false
                                } else if ((change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                                ) {
                                    movedAway = true
                                    running = false
                                }
                            }
                            true
                        } != null

                    if (endedBeforeLongPress) {
                        // 轻点：右上角圆圈 = 勾选/取消；图片本体 = 打开查看。
                        // 若是滑动列表则什么都不做。
                        // 抬手事件在上面已经收到了，不能再 waitForUpOrCancellation()，否则会一直等下去。
                        if (liftedEarly && !movedAway) {
                            upChange?.consume()
                            // 圆圈只有 22dp，命中区放宽到 40dp 见方，手指才好点
                            val hit = 40.dp.toPx()
                            val onCircle =
                                down.position.x >= size.width - hit && down.position.y <= hit
                            if (onCircle) onCircleClick() else onClick()
                        }
                        return@awaitEachGesture
                    }

                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDragStart(
                        item.id,
                        coordsHolder[0]?.localToWindow(down.position) ?: down.position
                    )
                    var dragging = true
                    while (dragging) {
                        // 逐帧取位置（不用 awaitDragOrCancellation：它要单帧位移超过 slop 才返回，
                        // 慢慢拖时每帧位移很小，会收不到回调 → 表现就是"拖不动"）
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.changedToUpIgnoreConsumed()) {
                            dragging = false
                        } else {
                            change.consume()
                            onDragMove(
                                coordsHolder[0]?.localToWindow(change.position) ?: change.position
                            )
                        }
                    }
                    onDragEnd()
                }
            }
    ) {
        PickerThumb(item)
        if (item.isVideo) {
            Text(
                formatDuration(item.durationMs),
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color(0x80000000), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) PickerBrandBlue else Color(0xCCFFFFFF))
                .border(
                    1.dp,
                    if (selected) PickerBrandBlue else Color(0xFFD1D5DB),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Text(
                    selectedOrder.toString(),
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * 网格缩略图：图片走 Coil；视频用 MediaMetadataRetriever 取首帧
 * （Coil 默认解不了视频，直接塞 content:// 视频 Uri 会一直是灰块）。
 * 首帧按 Uri 落盘缓存，滚动来回不再重复解码。
 */
@Composable
private fun PickerThumb(item: PickerMedia) {
    if (!item.isVideo) {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) { loadLocalVideoFrame(ctx, item.uri) }
    }
    val frame = thumb
    if (frame != null) {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color(0x66000000),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** 取本地视频首帧（带旋转修正 + 降采样 + 磁盘缓存）。 */
private fun loadLocalVideoFrame(ctx: Context, uri: Uri, maxDim: Int = 480): Bitmap? =
    VideoThumbCache.localFrame(ctx, uri.toString(), maxDim)

/** 预览里的本地视频播放器（ExoPlayer + TextureView，content:// 直接可播） */
@Composable
private fun PickerVideoPlayer(uri: Uri) {
    // 用数组持有 player：避免创建时触发重组
    val holder = remember(uri) { arrayOfNulls<ExoPlayer>(1) }
    AndroidView(
        factory = { c ->
            TextureView(c).also { tv ->
                val p = createExoPlayer(c, uri.toString(), true)
                p.setVideoTextureView(tv)
                holder[0] = p
            }
        },
        modifier = Modifier.fillMaxSize()
    )
    DisposableEffect(uri) {
        onDispose {
            holder[0]?.release()
            holder[0] = null
        }
    }
}

/** 下拉里的一行：可选相册封面 + 名称 + 数量 */
@Composable
private fun PickerDropdownRow(
    cover: Uri?,
    title: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(com.aurora.chat.ui.theme.appCardBg())
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            fontSize = 16.sp,
            color = if (selected) PickerBrandBlue else PickerTextPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text("($count)", fontSize = 14.sp, color = PickerTextSecondary)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(PickerDivider))
}
