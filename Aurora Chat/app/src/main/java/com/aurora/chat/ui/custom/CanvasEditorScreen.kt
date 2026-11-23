package com.aurora.chat.ui.custom

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min

/**
 * 可视化拖拽编辑器。
 *
 * 布局：顶栏 → 固定快捷工具条（撤销/重做/网格/吸附/预览）→ 画布（占满中间）
 * → 底部标签栏（添加/图层/属性/模板/设置）+ 一块**可关闭的独立面板**。
 * 同一时刻只显示一个面板，工具与属性不再互相挤在画布下面。
 *
 * 画布是固定设计尺寸（默认 360×640 dp），等比缩放后居中；保存后由 [ExtensionCanvas]
 * 原样渲染，所见即所得。
 *
 * 键盘：MainActivity 在 Manifest 是 adjustNothing（全局不抬窗，改了会破坏聊天输入），
 * 这里用窗口可见区域差算出键盘高度上抬本页，并在 IME 出现时收窄画布、把面板滚到顶部。
 */
private enum class EditorTab(val label: String) {
    ADD("添加"),
    LAYERS("图层"),
    PROPS("属性"),
    TEMPLATES("模板"),
    SETTINGS("设置")
}

/** 动作选择器当前在改谁：某个元素，还是点击画布空白处的整体动作。 */
private sealed interface ActionTarget {
    /** slot：0=单击 1=长按 2=双击 3=三击 */
    data class Element(val id: String, val slot: Int = 0) : ActionTarget
    data object Canvas : ActionTarget
}

@Composable
fun CanvasEditorScreen(
    initial: HostExtension,
    onClose: () -> Unit,
    onSaved: (HostExtension) -> Unit
) {
    val ctx = LocalContext.current
    var ext by remember { mutableStateOf(initial) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    // 编辑区常驻，不再「没选中就消失」；默认停在添加页。
    var activeTab by remember { mutableStateOf(EditorTab.ADD) }
    var showPreview by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(false) }
    var snapEnabled by remember { mutableStateOf(true) }
    var keepRatio by remember { mutableStateOf(false) }
    // 面板高度可拖分隔条调整；嫌滚动麻烦就把面板拉大
    var panelHeightDp by remember { mutableStateOf(272f) }
    var actionPicker by remember { mutableStateOf<ActionTarget?>(null) }
    var guideX by remember { mutableStateOf<Float?>(null) }
    var guideY by remember { mutableStateOf<Float?>(null) }
    var undoStack by remember { mutableStateOf(emptyList<HostExtension>()) }
    var redoStack by remember { mutableStateOf(emptyList<HostExtension>()) }
    var pendingImageId by remember { mutableStateOf<String?>(null) }

    fun updateCanvas(fn: (CanvasSpec) -> CanvasSpec) {
        ext = ext.copy(canvas = fn(ext.canvas))
    }

    fun updateEl(id: String, fn: (CanvasElement) -> CanvasElement) {
        updateCanvas { c -> c.copy(elements = c.elements.map { if (it.id == id) fn(it) else it }) }
    }

    fun pushHistory() {
        undoStack = (undoStack + ext).takeLast(40)
        redoStack = emptyList()
    }

    fun commit(block: () -> Unit) {
        pushHistory()
        block()
    }

    /** 撤销：回到上一次改动之前；重做：撤销之后把那次改动找回来。 */
    fun undo() {
        val prev = undoStack.lastOrNull() ?: return
        undoStack = undoStack.dropLast(1)
        redoStack = redoStack + ext
        ext = prev
        selectedId = null
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        redoStack = redoStack.dropLast(1)
        undoStack = undoStack + ext
        ext = next
        selectedId = null
    }

    fun addElement(kind: CanvasElementKind) {
        val c = ext.canvas
        val el = when (kind) {
            CanvasElementKind.TEXT -> CanvasElement(
                kind = kind, xDp = 60f, yDp = c.heightDp / 2f - 24f,
                widthDp = 240f, heightDp = 48f,
                text = "文字", fontSizeSp = 18f, textColor = 0xFF1F2937
            )
            CanvasElementKind.BUTTON -> CanvasElement(
                kind = kind, xDp = (c.widthDp - 120f) / 2f, yDp = c.heightDp / 2f - 22f,
                widthDp = 120f, heightDp = 44f,
                text = "按钮", fontSizeSp = 15f, textColor = 0xFFFFFFFF,
                bgColor = 0xFF1E40AF, cornerRadiusDp = 10f
            )
            CanvasElementKind.SHAPE -> CanvasElement(
                kind = kind, xDp = (c.widthDp - 160f) / 2f, yDp = c.heightDp / 2f - 60f,
                widthDp = 160f, heightDp = 120f,
                bgColor = 0xFFDBEAFE, cornerRadiusDp = 12f
            )
            CanvasElementKind.IMAGE -> CanvasElement(
                kind = kind, xDp = (c.widthDp - 120f) / 2f, yDp = c.heightDp / 2f - 60f,
                widthDp = 120f, heightDp = 120f, cornerRadiusDp = 12f
            )
            CanvasElementKind.ICON -> CanvasElement(
                kind = kind, xDp = (c.widthDp - 48f) / 2f, yDp = c.heightDp / 2f - 24f,
                widthDp = 48f, heightDp = 48f, cornerRadiusDp = 10f
            )
        }
        commit { updateCanvas { it.copy(elements = it.elements + el) } }
        selectedId = el.id
        activeTab = EditorTab.PROPS
        if (kind == CanvasElementKind.IMAGE || kind == CanvasElementKind.ICON) {
            pendingImageId = el.id
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val id = pendingImageId ?: return@rememberLauncherForActivityResult
        pendingImageId = null
        if (uri == null) return@rememberLauncherForActivityResult
        ExtensionSafety.guardRun(ctx, "导入图片") {
            val path = copyImageToInternal(ctx, uri)
            if (path != null) commit { updateEl(id) { it.copy(assetPath = path) } }
        }
    }

    fun addImage(kind: CanvasElementKind) {
        addElement(kind)
        ExtensionSafety.guardRun(ctx, "打开图片选择器") { imagePicker.launch("image/*") }
    }

    fun finish() {
        ExtensionSafety.guardRun(ctx, "保存画布") {
            val saved = ext.copy(updatedAt = System.currentTimeMillis())
            HostExtensionStore.upsert(ctx, saved)
            onSaved(saved)
            onClose()
        }
    }

    BackHandler(enabled = showPreview || selectedId != null) {
        when {
            showPreview -> showPreview = false
            else -> selectedId = null
        }
    }

    // adjustNothing 下 WindowInsets.ime 可能为 0，用可见区域差直接算键盘高度。
    val keyboardDp = rememberKeyboardHeightDp()
    val panelScroll = rememberScrollState()
    LaunchedEffect(activeTab) { panelScroll.animateScrollTo(0) }
    LaunchedEffect(keyboardDp > 0f) {
        if (keyboardDp > 0f) panelScroll.animateScrollTo(0)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CustomUi.PageBg)
            .padding(bottom = keyboardDp.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            CustomTopBar(
                title = "编辑画布",
                onBack = { finish() },
                right = { TopAction("保存", primary = true) { finish() } }
            )

            // ── 固定快捷工具条：撤销/重做在栈空时置灰，不会「点了没反应」 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CustomUi.CardBg)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallAction(
                    "↶ 撤销" + if (undoStack.isNotEmpty()) " ${undoStack.size}" else "",
                    enabled = undoStack.isNotEmpty()
                ) { undo() }
                Spacer(Modifier.width(6.dp))
                SmallAction(
                    "↷ 重做" + if (redoStack.isNotEmpty()) " ${redoStack.size}" else "",
                    enabled = redoStack.isNotEmpty()
                ) { redo() }
                Spacer(Modifier.width(6.dp))
                SmallAction("网格", active = showGrid) { showGrid = !showGrid }
                Spacer(Modifier.width(6.dp))
                SmallAction("吸附", active = snapEnabled) { snapEnabled = !snapEnabled }
                Spacer(Modifier.weight(1f))
                SmallAction("预览") { showPreview = true }
            }
            CustomDivider()

            // ── 画布区 ──
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        // 键盘弹出时暂时收起画布，保证常驻属性面板完整可见
                        if (keyboardDp > 0f) Modifier.height(12.dp) else Modifier.weight(1f)
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                val t = computeCanvasTransform(ext.canvas, maxWidth.value, maxHeight.value)
                val density = LocalDensity.current.density
                val pxPerDesignDp = (t.scale * density).coerceAtLeast(0.01f)
                val canvasW = (ext.canvas.widthDp * t.scale).dp
                val canvasH = (ext.canvas.heightDp * t.scale).dp
                // 手势协程不重启也要读到最新配置，避免改了画布尺寸/开关后还用旧比例
                val livePxPerDp = rememberUpdatedState(pxPerDesignDp)
                val liveKeepRatio = rememberUpdatedState(keepRatio)
                val liveSnap = rememberUpdatedState(snapEnabled)
                val liveCanvasW = rememberUpdatedState(ext.canvas.widthDp)
                val liveCanvasH = rememberUpdatedState(ext.canvas.heightDp)

                // 手势统一挂在这一层：节点位置在拖动全程固定，且不受圆角裁剪，
                // 所以手柄露出画布边角也能按到，坐标也不会跟着元素尺寸来回跳。
                Box(
                    modifier = Modifier
                        .offset(x = t.offsetX.dp, y = t.offsetY.dp)
                        .size(width = canvasW, height = canvasH)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val px = livePxPerDp.value.coerceAtLeast(0.01f)
                                val els = ext.canvas.elements
                                val sel = els.firstOrNull { it.id == selectedId }
                                val handleRadius = 20f * density
                                val onHandle = sel != null && !sel.locked &&
                                    abs(down.position.x - (sel.xDp + sel.widthDp) * px) <= handleRadius &&
                                    abs(down.position.y - (sel.yDp + sel.heightDp) * px) <= handleRadius
                                // 从最上层往下找：后添加的元素盖在前面
                                val hit = els.reversed().firstOrNull { el ->
                                    down.position.x >= el.xDp * px &&
                                        down.position.x <= (el.xDp + el.widthDp) * px &&
                                        down.position.y >= el.yDp * px &&
                                        down.position.y <= (el.yDp + el.heightDp) * px
                                }
                                when {
                                    onHandle -> {
                                        down.consume()
                                        // 用「按下时的宽高 + 绝对位移」算尺寸，不做增量累加，
                                        // 手柄跟着动也不会形成正反馈，图片拉伸不会抽搐。
                                        val w0 = sel.widthDp
                                        val h0 = sel.heightDp
                                        val ratio = h0 / w0.coerceAtLeast(1f)
                                        val start = down.position
                                        var pushed = false
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!c.pressed) break
                                            val dx = (c.position.x - start.x) / px
                                            val dy = (c.position.y - start.y) / px
                                            if (abs(dx) > 0.5f || abs(dy) > 0.5f || pushed) {
                                                if (!pushed) {
                                                    pushHistory()
                                                    pushed = true
                                                }
                                                val nw = (w0 + dx).coerceIn(24f, 720f)
                                                val nh = if (liveKeepRatio.value) {
                                                    (nw * ratio).coerceIn(24f, 1280f)
                                                } else (h0 + dy).coerceIn(24f, 1280f)
                                                updateEl(sel.id) { it.copy(widthDp = nw, heightDp = nh) }
                                            }
                                            c.consume()
                                        }
                                    }

                                    hit != null -> {
                                        down.consume()
                                        selectedId = hit.id
                                        activeTab = EditorTab.PROPS
                                        if (!hit.locked) {
                                            val x0 = hit.xDp
                                            val y0 = hit.yDp
                                            val start = down.position
                                            var pushed = false
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!c.pressed) break
                                                val dx = (c.position.x - start.x) / px
                                                val dy = (c.position.y - start.y) / px
                                                if (abs(dx) > 0.5f || abs(dy) > 0.5f || pushed) {
                                                    if (!pushed) {
                                                        pushHistory()
                                                        pushed = true
                                                    }
                                                    var nx = x0 + dx
                                                    var ny = y0 + dy
                                                    if (liveSnap.value) {
                                                        val midX = liveCanvasW.value / 2f
                                                        val midY = liveCanvasH.value / 2f
                                                        if (abs(nx + hit.widthDp / 2f - midX) < 6f) {
                                                            nx = midX - hit.widthDp / 2f
                                                            guideX = midX
                                                        } else guideX = null
                                                        if (abs(ny + hit.heightDp / 2f - midY) < 6f) {
                                                            ny = midY - hit.heightDp / 2f
                                                            guideY = midY
                                                        } else guideY = null
                                                    }
                                                    updateEl(hit.id) {
                                                        it.copy(
                                                            xDp = nx.coerceIn(-it.widthDp, liveCanvasW.value),
                                                            yDp = ny.coerceIn(-it.heightDp, liveCanvasH.value)
                                                        )
                                                    }
                                                }
                                                c.consume()
                                            }
                                            guideX = null
                                            guideY = null
                                        }
                                    }

                                    else -> selectedId = null
                                }
                            }
                        }
                ) {
                    // 视觉层单独裁剪，手势层不裁剪，二者坐标原点一致
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                            .background(canvasBrush(ext.canvas))
                            .border(0.5.dp, CustomUi.Border, RoundedCornerShape(6.dp))
                    ) {
                    if (showGrid) {
                        Canvas(Modifier.fillMaxSize()) {
                            val step = (20f * t.scale * density).coerceAtLeast(8f)
                            var gx = step
                            while (gx < size.width) {
                                drawLine(Color(0x22000000), Offset(gx, 0f), Offset(gx, size.height), 1f)
                                gx += step
                            }
                            var gy = step
                            while (gy < size.height) {
                                drawLine(Color(0x22000000), Offset(0f, gy), Offset(size.width, gy), 1f)
                                gy += step
                            }
                        }
                    }

                    ext.canvas.elements.forEach { el ->
                        val selected = el.id == selectedId
                        // 元素本身不再挂手势：之前 IMAGE / BUTTON 这类带圆角与填充内容的
                        // 节点会被裁剪和父层 tap 抢事件，导致按上去拖不动。现在全部由画布层统一分发。
                        Box(
                            modifier = Modifier
                                .canvasElementPlacement(el, t.scale)
                                .then(
                                    if (selected) Modifier.border(
                                        1.5.dp,
                                        if (el.locked) CustomUi.Hint else CustomUi.Primary,
                                        RoundedCornerShape(2.dp)
                                    ) else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            CanvasElementContent(el, t.scale)
                        }
                    }

                    guideX?.let { gx ->
                        Box(
                            Modifier
                                .offset(x = (gx * t.scale).dp)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(CustomUi.Primary)
                        )
                    }
                    guideY?.let { gy ->
                        Box(
                            Modifier
                                .offset(y = (gy * t.scale).dp)
                                .height(1.dp)
                                .fillMaxWidth()
                                .background(CustomUi.Primary)
                        )
                    }
                    } // 视觉层
                } // 手势层

                // 缩放手柄只负责显示：命中判定与拖动都在画布手势层里，
                // 手柄自己跟着尺寸移动也不会造成坐标系漂移。
                val handleEl = ext.canvas.elements.firstOrNull { it.id == selectedId }
                if (handleEl != null && !handleEl.locked) {
                    val hx = t.offsetX + (handleEl.xDp + handleEl.widthDp) * t.scale
                    val hy = t.offsetY + (handleEl.yDp + handleEl.heightDp) * t.scale
                    Box(
                        modifier = Modifier
                            .offset(x = (hx - 16f).dp, y = (hy - 16f).dp)
                            .size(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(CustomUi.Primary)
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                    }
                }
            }

            // ── 分隔条：上下拖动调整下方面板高度 ──
            // 面板高度一变，分隔条自己也会移动；若直接用节点相对坐标会形成正反馈
            // （拖一点→条移动→位移再翻倍）导致画面抽搐。这里用「手指根坐标 =
            // 分隔条实时根坐标 + 相对坐标」还原绝对位移，彻底消掉反馈。
            if (keyboardDp <= 0f) {
                val livePanelH = rememberUpdatedState(panelHeightDp)
                var dividerRootY by remember { mutableStateOf(0f) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .onGloballyPositioned { dividerRootY = it.positionInRoot().y }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val fingerRootY = down.position.y + dividerRootY
                                val startH = livePanelH.value
                                var dragging = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    val dy = change.position.y + dividerRootY - fingerRootY
                                    if (!dragging && abs(dy) > 4f) dragging = true
                                    if (dragging) {
                                        panelHeightDp = (startH - dy / density).coerceIn(140f, 560f)
                                        change.consume()
                                    }
                                    if (!change.pressed) break
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { 
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(CustomUi.Border)
                    )
                }
            }

            // ── 底部：常驻编辑面板 + 标签栏（白底铺到导航栏下面） ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(CustomUi.CardBg)
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    val title = when (activeTab) {
                        EditorTab.ADD -> "添加组件"
                        EditorTab.LAYERS -> "图层（${ext.canvas.elements.size}）"
                        EditorTab.PROPS -> {
                            val el = ext.canvas.elements.firstOrNull { it.id == selectedId }
                            if (el != null) "${el.kind.label}属性" else "元素属性"
                        }
                        EditorTab.TEMPLATES -> "套用模板"
                        EditorTab.SETTINGS -> "画布设置"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CustomUi.Text)
                        Spacer(Modifier.weight(1f))
                        Text("拖动元素可调整位置", fontSize = 11.sp, color = CustomUi.Hint)
                    }
                    CustomDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (keyboardDp > 0f) 130.dp else panelHeightDp.dp)
                            .verticalScroll(panelScroll)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        // 面板内容常驻，切换标签时淡入 + 轻微上滑，不再硬切
                        AnimatedContent(
                            targetState = activeTab,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(180)) +
                                    slideInVertically(animationSpec = tween(180)) { it / 8 }) togetherWith
                                    (fadeOut(animationSpec = tween(140)) +
                                        slideOutVertically(animationSpec = tween(140)) { -it / 8 })
                            },
                            label = "editorPanel"
                        ) { tab ->
                            Column(Modifier.fillMaxWidth()) {
                                when (tab) {
                                    EditorTab.ADD -> AddPanel { kind ->
                                        if (kind == CanvasElementKind.IMAGE || kind == CanvasElementKind.ICON) {
                                            addImage(kind)
                                        } else addElement(kind)
                                    }
                                    EditorTab.LAYERS -> LayersPanel(
                                        elements = ext.canvas.elements,
                                        selectedId = selectedId,
                                        onSelect = { id ->
                                            selectedId = id
                                            activeTab = EditorTab.PROPS
                                        },
                                        onMove = { index, up ->
                                            commit {
                                                val list = ext.canvas.elements.toMutableList()
                                                val target = index + if (up) 1 else -1
                                                if (target in list.indices) {
                                                    java.util.Collections.swap(list, index, target)
                                                    updateCanvas { it.copy(elements = list) }
                                                }
                                            }
                                        },
                                        onDuplicate = { el ->
                                            commit {
                                                val copy = el.copy(
                                                    id = "el_" + System.nanoTime().toString(),
                                                    xDp = el.xDp + 12f, yDp = el.yDp + 12f
                                                )
                                                updateCanvas { it.copy(elements = it.elements + copy) }
                                                selectedId = copy.id
                                            }
                                        },
                                        onDelete = { id ->
                                            commit {
                                                updateCanvas { c -> c.copy(elements = c.elements.filterNot { it.id == id }) }
                                            }
                                            if (selectedId == id) selectedId = null
                                        }
                                    )
                                    EditorTab.PROPS -> {
                                        val el = ext.canvas.elements.firstOrNull { it.id == selectedId }
                                        if (el != null) ElementPanel(
                                            el = el,
                                            index = ext.canvas.elements.indexOf(el),
                                            count = ext.canvas.elements.size,
                                            onCommit = { fn -> commit { updateEl(el.id, fn) } },
                                            onChange = { fn -> updateEl(el.id, fn) },
                                            onMoveToTop = {
                                                commit {
                                                    val rest = ext.canvas.elements.filterNot { it.id == el.id }
                                                    updateCanvas { it.copy(elements = rest + el) }
                                                }
                                            },
                                            onMoveToBottom = {
                                                commit {
                                                    val rest = ext.canvas.elements.filterNot { it.id == el.id }
                                                    updateCanvas { it.copy(elements = listOf(el) + rest) }
                                                }
                                            },
                                            onDuplicate = {
                                                commit {
                                                    val copy = el.copy(
                                                        id = "el_" + System.nanoTime().toString(),
                                                        xDp = el.xDp + 12f, yDp = el.yDp + 12f
                                                    )
                                                    updateCanvas { it.copy(elements = it.elements + copy) }
                                                    selectedId = copy.id
                                                }
                                            },
                                            onDelete = {
                                                commit {
                                                    updateCanvas { c -> c.copy(elements = c.elements.filterNot { it.id == el.id }) }
                                                }
                                                selectedId = null
                                                activeTab = EditorTab.ADD
                                            },
                                            onPickImage = {
                                                pendingImageId = el.id
                                                imagePicker.launch("image/*")
                                            },
                                            keepRatio = keepRatio,
                                            onKeepRatioChange = { keepRatio = it },
                                            onOpenPicker = { slot ->
                                                actionPicker = ActionTarget.Element(el.id, slot)
                                            },
                                            onHistory = { pushHistory() }
                                        ) else Text(
                                            "在画布上点选一个元素即可编辑",
                                            fontSize = 13.sp,
                                            color = CustomUi.Hint
                                        )
                                    }
                                    EditorTab.TEMPLATES -> TemplatesPanel(ext.kind) { tpl ->
                                        commit { ext = HostExtensionTemplates.apply(ext, tpl) }
                                        selectedId = null
                                        activeTab = EditorTab.ADD
                                    }
                                    EditorTab.SETTINGS -> SettingsPanel(
                                        ext = ext,
                                        onCommit = { fn -> commit { ext = fn(ext) } },
                                        onDirectChange = { fn -> ext = fn(ext) },
                                        onDirectName = { v -> ext = ext.copy(name = v) },
                                        onClear = {
                                            commit { updateCanvas { it.copy(elements = emptyList()) } }
                                            selectedId = null
                                        },
                                        onOpenActionPicker = { actionPicker = ActionTarget.Canvas },
                                        onHistory = { pushHistory() }
                                    )
                                }
                            }
                        }
                    }
                    CustomDivider()

                    // 常驻标签栏：等宽 5 项，切换时指示条与文字颜色带过渡
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .padding(horizontal = 6.dp)
                    ) {
                        EditorTab.entries.forEach { tab ->
                            DockTab(
                                label = tab.label,
                                selected = activeTab == tab,
                                modifier = Modifier.weight(1f)
                            ) { activeTab = tab }
                        }
                    }
                }
            }
        }

        // 全屏预览：与运行时同一个渲染器，覆盖到屏幕顶部。
        // 预览不是摆设：绑了事件的元素在这里点了/长按了也会真的执行。
        if (showPreview) {
            Box(Modifier.fillMaxSize()) {
                ExtensionCanvas(
                    spec = ext.canvas,
                    modifier = Modifier.fillMaxSize(),
                    onElementAction = { el, action, value ->
                        if (action != ElementAction.NONE) {
                            ExtensionActionRunner.run(ctx, action, value)
                        } else if (ext.action != ElementAction.NONE) {
                            // 元素没配单击时回落到画布整体动作，与运行时一致
                            ExtensionActionRunner.run(ctx, ext.action, ext.actionValue)
                        }
                    },
                    onCanvasAction = {
                        if (ext.action != ElementAction.NONE) {
                            ExtensionActionRunner.run(ctx, ext.action, ext.actionValue)
                        }
                    }
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showPreview = false },
                    contentAlignment = Alignment.Center
                ) { Text("×", fontSize = 20.sp, color = Color.White) }
            }
        }

        // 点击动作：分组网格选择器，覆盖整个编辑页
        actionPicker?.let { target ->
            val isCanvas = target is ActionTarget.Canvas
            val slot = (target as? ActionTarget.Element)?.slot ?: 0
            val el = (target as? ActionTarget.Element)?.let { t ->
                ext.canvas.elements.firstOrNull { it.id == t.id }
            }
            fun slotAction(e: CanvasElement): ElementAction = when (slot) {
                1 -> e.longClickAction
                2 -> e.doubleClickAction
                3 -> e.tripleClickAction
                else -> e.clickAction
            }
            fun slotValue(e: CanvasElement): String = when (slot) {
                1 -> e.longClickValue
                2 -> e.doubleClickValue
                3 -> e.tripleClickValue
                else -> e.clickValue
            }
            ActionPicker(
                current = if (isCanvas) ext.action else el?.let { slotAction(it) } ?: ElementAction.NONE,
                value = if (isCanvas) ext.actionValue else el?.let { slotValue(it) }.orEmpty(),
                isCanvas = isCanvas,
                onPick = { a ->
                    if (isCanvas) {
                        commit { ext = ext.copy(action = a) }
                    } else el?.let { e ->
                        commit {
                            updateEl(e.id) { cur ->
                                when (slot) {
                                    1 -> cur.copy(longClickAction = a)
                                    2 -> cur.copy(doubleClickAction = a)
                                    3 -> cur.copy(tripleClickAction = a)
                                    else -> cur.copy(clickAction = a)
                                }
                            }
                        }
                    }
                },
                onValueChange = { v ->
                    if (isCanvas) {
                        ext = ext.copy(actionValue = v)
                    } else el?.let { e ->
                        updateEl(e.id) { cur ->
                            when (slot) {
                                1 -> cur.copy(longClickValue = v)
                                2 -> cur.copy(doubleClickValue = v)
                                3 -> cur.copy(tripleClickValue = v)
                                else -> cur.copy(clickValue = v)
                            }
                        }
                    }
                },
                onClose = { actionPicker = null }
            )
        }
    }
}

@Composable
private fun DockTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val textColor by animateColorAsState(
        targetValue = if (selected) CustomUi.Primary else CustomUi.Sub,
        animationSpec = tween(180), label = "dockTabText"
    )
    val barColor by animateColorAsState(
        targetValue = if (selected) CustomUi.Primary else Color.Transparent,
        animationSpec = tween(180), label = "dockTabBar"
    )
    val barWidth by animateDpAsState(
        targetValue = if (selected) 20.dp else 0.dp,
        animationSpec = tween(180), label = "dockTabBarWidth"
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .width(barWidth)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(barColor)
            )
            Spacer(Modifier.height(5.dp))
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor
            )
        }
    }
}

@Composable
private fun AddPanel(onAdd: (CanvasElementKind) -> Unit) {
    val items = listOf(
        "文字" to CanvasElementKind.TEXT,
        "图片" to CanvasElementKind.IMAGE,
        "色块" to CanvasElementKind.SHAPE,
        "按钮" to CanvasElementKind.BUTTON,
        "图标" to CanvasElementKind.ICON
    )
    items.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { (label, kind) ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CustomUi.FieldBg)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onAdd(kind) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("＋ $label", fontSize = 13.sp, color = CustomUi.Text, fontWeight = FontWeight.Medium)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f).padding(end = 8.dp)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LayersPanel(
    elements: List<CanvasElement>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onMove: (Int, Boolean) -> Unit,
    onDuplicate: (CanvasElement) -> Unit,
    onDelete: (String) -> Unit
) {
    if (elements.isEmpty()) {
        Text("画布上还没有元素", fontSize = 13.sp, color = CustomUi.Hint)
        return
    }
    Text("数字越大越靠上，↑↓ 调整遮挡顺序", fontSize = 11.sp, color = CustomUi.Hint)
    Spacer(Modifier.height(6.dp))
    elements.forEachIndexed { index, el ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (el.id == selectedId) Color(0xFFE8EEFB) else CustomUi.FieldBg)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onSelect(el.id) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${index + 1}", fontSize = 12.sp, color = CustomUi.Primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text(
                if (el.locked) "${el.kind.label} 已锁定" else el.kind.label,
                fontSize = 13.sp,
                color = CustomUi.Text,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            MiniButton("↑") { onMove(index, true) }
            MiniButton("↓") { onMove(index, false) }
            MiniButton("⧉") { onDuplicate(el) }
            MiniButton("×", danger = true) { onDelete(el.id) }
        }
    }
}

@Composable
private fun MiniButton(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(26.dp)
            .clip(CircleShape)
            .background(if (danger) Color(0xFFFEE2E2) else com.aurora.chat.ui.theme.appCardBg())
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (danger) CustomUi.Danger else CustomUi.Sub
        )
    }
}

/** 模板面板：按扩展类型给对应的一套模板，两列网格铺满面板，卡片带等比缩略预览。 */
@Composable
private fun TemplatesPanel(kind: ExtensionKind, onPick: (CanvasTemplate) -> Unit) {
    HostExtensionTemplates.forKind(kind).chunked(2).forEachIndexed { index, row ->
        if (index > 0) Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            row.forEachIndexed { col, tpl ->
                if (col > 0) Spacer(Modifier.width(8.dp))
                TemplateCard(tpl, Modifier.weight(1f)) { onPick(tpl) }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TemplateCard(
    tpl: CanvasTemplate,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val spec = remember(tpl) {
        CanvasSpec(
            widthDp = tpl.widthDp,
            heightDp = tpl.heightDp,
            background = tpl.background,
            backgroundEnd = tpl.backgroundEnd,
            elements = tpl.elements
        )
    }
    Column(modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(shape)
                .border(1.dp, CustomUi.Border, shape)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onClick() }
        ) {
            // 不按缩略图尺寸重新排版，而是按模板原尺寸布局后整体等比缩放居中：
            // 整页 / 横条 / 方形比例不同也不会被挤压或溢出。
            val s = min(
                maxWidth.value / tpl.widthDp.coerceAtLeast(1f),
                maxHeight.value / tpl.heightDp.coerceAtLeast(1f)
            )
            val offX = (maxWidth.value - tpl.widthDp * s) / 2f
            val offY = (maxHeight.value - tpl.heightDp * s) / 2f
            Box(
                Modifier
                    .offset(x = offX.dp, y = offY.dp)
                    .size(width = tpl.widthDp.dp, height = tpl.heightDp.dp)
                    .graphicsLayer {
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .background(canvasBrush(spec))
            ) {
                tpl.elements.forEach { el ->
                    Box(
                        modifier = Modifier.canvasElementPlacement(el, 1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CanvasElementContent(el, 1f)
                    }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            tpl.name,
            fontSize = 12.sp,
            color = CustomUi.Text,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingsPanel(
    ext: HostExtension,
    onCommit: ((HostExtension) -> HostExtension) -> Unit,
    onDirectChange: ((HostExtension) -> HostExtension) -> Unit,
    onDirectName: (String) -> Unit,
    onClear: () -> Unit,
    onOpenActionPicker: () -> Unit,
    onHistory: () -> Unit
) {
    CustomField(value = ext.name, onValueChange = onDirectName, hint = "名称")
    Spacer(Modifier.height(10.dp))
    // 启动画面最关心的就是显示多久，放到设置页最顶部
    if (ext.kind == ExtensionKind.LAUNCH_SCREEN) {
        Stepper(
            label = "时长",
            value = "${ext.durationMs}ms",
            onMinus = { onCommit { it.copy(durationMs = (it.durationMs - 500L).coerceAtLeast(0L)) } },
            onPlus = { onCommit { it.copy(durationMs = (it.durationMs + 500L).coerceAtMost(10_000L)) } }
        )
        Spacer(Modifier.height(10.dp))
    }
    Text("画布尺寸", fontSize = 12.sp, color = CustomUi.Sub)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        HostExtensionTemplates.sizes.forEach { (label, w, h) ->
            val picked = ext.canvas.widthDp == w && ext.canvas.heightDp == h
            SmallAction(label, active = picked) {
                onCommit { e -> e.copy(canvas = e.canvas.copy(widthDp = w, heightDp = h)) }
            }
            Spacer(Modifier.width(6.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    if (ext.kind == ExtensionKind.LAUNCH_SCREEN) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("点击跳过", fontSize = 12.sp, color = CustomUi.Sub, modifier = Modifier.weight(1f))
            CustomSwitch(
                checked = ext.tapToDismiss,
                onCheckedChange = { v -> onCommit { it.copy(tapToDismiss = v) } }
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    Text("画布底色", fontSize = 12.sp, color = CustomUi.Sub)
    Spacer(Modifier.height(6.dp))
    ColorSwatches(
        current = ext.canvas.background,
        onPick = { c -> onDirectChange { e -> e.copy(canvas = e.canvas.copy(background = c)) } },
        onGestureStart = onHistory
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("渐变终点色", fontSize = 12.sp, color = CustomUi.Sub, modifier = Modifier.weight(1f))
        SmallAction("关闭") { onCommit { e -> e.copy(canvas = e.canvas.copy(backgroundEnd = 0L)) } }
    }
    Spacer(Modifier.height(6.dp))
    ColorSwatches(
        current = ext.canvas.backgroundEnd,
        onPick = { c -> onDirectChange { e -> e.copy(canvas = e.canvas.copy(backgroundEnd = c)) } },
        onGestureStart = onHistory
    )

    if (ext.kind == ExtensionKind.FLOATING_WINDOW) {
        Spacer(Modifier.height(12.dp))
        CustomDivider()
        Spacer(Modifier.height(10.dp))
        Text("悬浮窗", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CustomUi.Text)
        Spacer(Modifier.height(10.dp))

        Text("窗口装饰", fontSize = 12.sp, color = CustomUi.Sub)
        Spacer(Modifier.height(6.dp))
        SwitchRow("标题栏", ext.floatingShowHeader) { v -> onCommit { it.copy(floatingShowHeader = v) } }
        if (ext.floatingShowHeader) {
            CustomField(
                value = ext.floatingHeaderTitle,
                onValueChange = { v -> onDirectChange { e -> e.copy(floatingHeaderTitle = v) } },
                hint = "标题文字，留空用名称"
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow("收起按钮", ext.floatingShowCollapse) { v -> onCommit { it.copy(floatingShowCollapse = v) } }
            SwitchRow("关闭按钮", ext.floatingShowClose) { v -> onCommit { it.copy(floatingShowClose = v) } }
            Stepper(
                label = "栏高", value = "${ext.floatingHeaderHeightDp.toInt()}dp",
                onMinus = { onCommit { it.copy(floatingHeaderHeightDp = (it.floatingHeaderHeightDp - 2f).coerceAtLeast(16f)) } },
                onPlus = { onCommit { it.copy(floatingHeaderHeightDp = (it.floatingHeaderHeightDp + 2f).coerceAtMost(56f)) } }
            )
            Spacer(Modifier.height(8.dp))
            Text("标题底色", fontSize = 12.sp, color = CustomUi.Sub)
            Spacer(Modifier.height(6.dp))
            ColorSwatches(
                current = ext.floatingHeaderBg,
                onPick = { c -> onDirectChange { e -> e.copy(floatingHeaderBg = c) } },
                onGestureStart = onHistory
            )
            Spacer(Modifier.height(8.dp))
            Text("标题文字色", fontSize = 12.sp, color = CustomUi.Sub)
            Spacer(Modifier.height(6.dp))
            ColorSwatches(
                current = ext.floatingHeaderTextColor,
                onPick = { c -> onDirectChange { e -> e.copy(floatingHeaderTextColor = c) } },
                onGestureStart = onHistory
            )
            Spacer(Modifier.height(8.dp))
        }
        SwitchRow("拖拽条", ext.floatingShowDragBar) { v -> onCommit { it.copy(floatingShowDragBar = v) } }
        SwitchRow("去掉外框", ext.floatingBareCanvas) { v -> onCommit { it.copy(floatingBareCanvas = v) } }
        Spacer(Modifier.height(10.dp))

        Text("形态", fontSize = 12.sp, color = CustomUi.Sub)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            FloatingStyle.entries.forEach { style ->
                SmallAction(style.label, active = ext.floatingStyle == style) {
                    onCommit { e -> e.copy(floatingStyle = style) }
                }
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        Stepper(
            label = "宽度", value = "${ext.floatingWidthDp.toInt()}dp",
            onMinus = { onCommit { it.copy(floatingWidthDp = (it.floatingWidthDp - 10f).coerceAtLeast(80f)) } },
            onPlus = { onCommit { it.copy(floatingWidthDp = (it.floatingWidthDp + 10f).coerceAtMost(340f)) } }
        )
        Spacer(Modifier.height(8.dp))
        Stepper(
            label = "高度", value = "${ext.floatingHeightDp.toInt()}dp",
            onMinus = { onCommit { it.copy(floatingHeightDp = (it.floatingHeightDp - 10f).coerceAtLeast(60f)) } },
            onPlus = { onCommit { it.copy(floatingHeightDp = (it.floatingHeightDp + 10f).coerceAtMost(480f)) } }
        )
        Spacer(Modifier.height(8.dp))
        Stepper(
            label = "圆角", value = "${ext.floatingRadiusDp.toInt()}dp",
            onMinus = { onCommit { it.copy(floatingRadiusDp = (it.floatingRadiusDp - 2f).coerceAtLeast(0f)) } },
            onPlus = { onCommit { it.copy(floatingRadiusDp = (it.floatingRadiusDp + 2f).coerceAtMost(40f)) } }
        )
        Spacer(Modifier.height(8.dp))
        Stepper(
            label = "透明", value = "${(ext.floatingAlpha * 100).toInt()}%",
            onMinus = { onCommit { it.copy(floatingAlpha = (it.floatingAlpha - 0.1f).coerceAtLeast(0.3f)) } },
            onPlus = { onCommit { it.copy(floatingAlpha = (it.floatingAlpha + 0.1f).coerceAtMost(1f)) } }
        )
        Spacer(Modifier.height(10.dp))

        SwitchRow("默认展开", ext.floatingAutoExpand) { v -> onCommit { it.copy(floatingAutoExpand = v) } }
        SwitchRow("松手贴边", ext.floatingSnapToSide) { v -> onCommit { it.copy(floatingSnapToSide = v) } }
        SwitchRow("锁定位置", ext.floatingLockPosition) { v -> onCommit { it.copy(floatingLockPosition = v) } }
        Spacer(Modifier.height(10.dp))

        Text("初始位置", fontSize = 12.sp, color = CustomUi.Sub)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            FloatingAnchor.entries.forEach { anchor ->
                SmallAction(anchor.label, active = ext.floatingAnchor == anchor) {
                    onCommit { e -> e.copy(floatingAnchor = anchor) }
                }
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        Text("折叠角标", fontSize = 12.sp, color = CustomUi.Sub)
        Spacer(Modifier.height(6.dp))
        CustomField(
            value = ext.floatingBadge,
            onValueChange = { v -> onDirectChange { e -> e.copy(floatingBadge = v) } },
            hint = "留空用默认符号"
        )
        Spacer(Modifier.height(8.dp))
        Text("把手底色", fontSize = 12.sp, color = CustomUi.Sub)
        Spacer(Modifier.height(6.dp))
        ColorSwatches(
            current = ext.floatingCollapsedBg,
            onPick = { c -> onDirectChange { e -> e.copy(floatingCollapsedBg = c) } },
            onGestureStart = onHistory
        )
        Spacer(Modifier.height(8.dp))
        Text("把手文字色", fontSize = 12.sp, color = CustomUi.Sub)
        Spacer(Modifier.height(6.dp))
        ColorSwatches(
            current = ext.floatingCollapsedTextColor,
            onPick = { c -> onDirectChange { e -> e.copy(floatingCollapsedTextColor = c) } },
            onGestureStart = onHistory
        )
        Spacer(Modifier.height(10.dp))

        val collapseSteps = listOf(0L, 3000L, 5000L, 10_000L, 30_000L)
        fun stepCollapse(cur: Long, up: Boolean): Long {
            val i = collapseSteps.indexOf(cur).let { if (it < 0) 0 else it }
            return collapseSteps[(i + if (up) 1 else -1).coerceIn(0, collapseSteps.lastIndex)]
        }
        Stepper(
            label = "自动收起",
            value = if (ext.floatingAutoCollapseMs == 0L) "关闭" else "${ext.floatingAutoCollapseMs / 1000}s",
            onMinus = { onCommit { it.copy(floatingAutoCollapseMs = stepCollapse(it.floatingAutoCollapseMs, false)) } },
            onPlus = { onCommit { it.copy(floatingAutoCollapseMs = stepCollapse(it.floatingAutoCollapseMs, true)) } }
        )
        Spacer(Modifier.height(10.dp))

        Text("点击画布空白处", fontSize = 12.sp, color = CustomUi.Sub)
        Spacer(Modifier.height(6.dp))
        ActionRow(label = "执行", action = ext.action, isCanvas = true) { onOpenActionPicker() }
        if (ext.action.needsValue) {
            Spacer(Modifier.height(8.dp))
            CustomField(
                value = ext.actionValue,
                onValueChange = { v -> onDirectChange { e -> e.copy(actionValue = v) } },
                hint = ext.action.hint
            )
        }
        Spacer(Modifier.height(10.dp))
    }

    Spacer(Modifier.height(4.dp))
    CustomDivider()
    Spacer(Modifier.height(10.dp))
    SmallAction("清空画布", danger = true) { onClear() }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = CustomUi.Sub, modifier = Modifier.weight(1f))
        CustomSwitch(checked = checked, onCheckedChange = onChange)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ElementPanel(
    el: CanvasElement,
    index: Int,
    count: Int,
    onCommit: ((CanvasElement) -> CanvasElement) -> Unit,
    onChange: ((CanvasElement) -> CanvasElement) -> Unit,
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onPickImage: () -> Unit,
    keepRatio: Boolean,
    onKeepRatioChange: (Boolean) -> Unit,
    onOpenPicker: (Int) -> Unit,
    onHistory: () -> Unit
) {
    Text(
        "${el.kind.label}  第 ${index + 1} 层 / 共 $count 层",
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CustomUi.Text
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        SmallAction("置顶层") { onMoveToTop() }
        Spacer(Modifier.width(6.dp))
        SmallAction("置底层") { onMoveToBottom() }
        Spacer(Modifier.width(6.dp))
        SmallAction("复制") { onDuplicate() }
        Spacer(Modifier.width(6.dp))
        SmallAction("锁定", active = el.locked) { onCommit { it.copy(locked = !it.locked) } }
        Spacer(Modifier.width(6.dp))
        if (el.kind == CanvasElementKind.IMAGE || el.kind == CanvasElementKind.ICON) {
            SmallAction("换图") { onPickImage() }
            Spacer(Modifier.width(6.dp))
        }
        SmallAction("删除", danger = true) { onDelete() }
    }
    Spacer(Modifier.height(6.dp))
    Text("层数只决定谁盖住谁，数字大的显示在上面", fontSize = 11.sp, color = CustomUi.Hint)
    Spacer(Modifier.height(10.dp))

    if (el.kind == CanvasElementKind.TEXT || el.kind == CanvasElementKind.BUTTON) {
        CustomField(value = el.text, onValueChange = { v -> onChange { it.copy(text = v) } }, hint = "文字内容")
        Spacer(Modifier.height(5.dp))
        Text(DynamicValues.hint, fontSize = 11.sp, color = CustomUi.Hint)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallAction("粗体", active = el.bold) { onCommit { it.copy(bold = !it.bold) } }
            Spacer(Modifier.width(6.dp))
            SmallAction("左", active = el.textAlign == 0) { onCommit { it.copy(textAlign = 0) } }
            Spacer(Modifier.width(6.dp))
            SmallAction("中", active = el.textAlign == 1) { onCommit { it.copy(textAlign = 1) } }
            Spacer(Modifier.width(6.dp))
            SmallAction("右", active = el.textAlign == 2) { onCommit { it.copy(textAlign = 2) } }
        }
        Spacer(Modifier.height(8.dp))
        Stepper(
            label = "字号",
            value = "${el.fontSizeSp.toInt()}sp",
            onMinus = { onCommit { it.copy(fontSizeSp = (it.fontSizeSp - 1f).coerceAtLeast(8f)) } },
            onPlus = { onCommit { it.copy(fontSizeSp = (it.fontSizeSp + 1f).coerceAtMost(72f)) } }
        )
        Spacer(Modifier.height(8.dp))
        Text("文字颜色", fontSize = 12.sp, color = CustomUi.Sub)
        Spacer(Modifier.height(6.dp))
        ColorSwatches(
            current = el.textColor,
            onPick = { c -> onChange { it.copy(textColor = c) } },
            onGestureStart = onHistory
        )
        Spacer(Modifier.height(10.dp))
    }

    Stepper(
        label = "圆角",
        value = "${el.cornerRadiusDp.toInt()}dp",
        onMinus = { onCommit { it.copy(cornerRadiusDp = (it.cornerRadiusDp - 2f).coerceAtLeast(0f)) } },
        onPlus = { onCommit { it.copy(cornerRadiusDp = (it.cornerRadiusDp + 2f).coerceAtMost(80f)) } }
    )
    Spacer(Modifier.height(8.dp))
    Stepper(
        label = "宽度",
        value = "${el.widthDp.toInt()}dp",
        onMinus = { onCommit { resizeElement(it, -8f, true, keepRatio) } },
        onPlus = { onCommit { resizeElement(it, 8f, true, keepRatio) } }
    )
    Spacer(Modifier.height(8.dp))
    Stepper(
        label = "高度",
        value = "${el.heightDp.toInt()}dp",
        onMinus = { onCommit { resizeElement(it, -8f, false, keepRatio) } },
        onPlus = { onCommit { resizeElement(it, 8f, false, keepRatio) } }
    )
    Spacer(Modifier.height(8.dp))
    SwitchRow("等比缩放（拖动右下角时）", keepRatio) { onKeepRatioChange(it) }
    Spacer(Modifier.height(4.dp))
    Stepper(
        label = "横向",
        value = "${el.xDp.toInt()}dp",
        onMinus = { onCommit { it.copy(xDp = it.xDp - 4f) } },
        onPlus = { onCommit { it.copy(xDp = it.xDp + 4f) } }
    )
    Spacer(Modifier.height(8.dp))
    Stepper(
        label = "纵向",
        value = "${el.yDp.toInt()}dp",
        onMinus = { onCommit { it.copy(yDp = it.yDp - 4f) } },
        onPlus = { onCommit { it.copy(yDp = it.yDp + 4f) } }
    )
    Spacer(Modifier.height(8.dp))
    Stepper(
        label = "透明",
        value = "${(el.alpha * 100).toInt()}%",
        onMinus = { onCommit { it.copy(alpha = (it.alpha - 0.1f).coerceAtLeast(0.1f)) } },
        onPlus = { onCommit { it.copy(alpha = (it.alpha + 0.1f).coerceAtMost(1f)) } }
    )
    Spacer(Modifier.height(8.dp))
    Stepper(
        label = "旋转",
        value = "${el.rotation.toInt()}°",
        onMinus = { onCommit { it.copy(rotation = it.rotation - 5f) } },
        onPlus = { onCommit { it.copy(rotation = it.rotation + 5f) } }
    )
    Spacer(Modifier.height(10.dp))
    Text("底色", fontSize = 12.sp, color = CustomUi.Sub)
    Spacer(Modifier.height(6.dp))
    ColorSwatches(
        current = el.bgColor,
        onPick = { c -> onChange { it.copy(bgColor = c) } },
        onGestureStart = onHistory
    )
    Spacer(Modifier.height(8.dp))
    Text("描边", fontSize = 12.sp, color = CustomUi.Sub)
    Spacer(Modifier.height(6.dp))
    ColorSwatches(
        current = el.borderColor,
        onPick = { c -> onChange { it.copy(borderColor = c) } },
        onGestureStart = onHistory
    )
    Spacer(Modifier.height(6.dp))
    Stepper(
        label = "描边",
        value = "${el.borderWidthDp.toInt()}dp",
        onMinus = { onCommit { it.copy(borderWidthDp = (it.borderWidthDp - 1f).coerceAtLeast(0f)) } },
        onPlus = { onCommit { it.copy(borderWidthDp = (it.borderWidthDp + 1f).coerceAtMost(12f)) } }
    )
    Spacer(Modifier.height(12.dp))
    CustomDivider()
    Spacer(Modifier.height(10.dp))
    Text("点击动作", fontSize = 12.sp, color = CustomUi.Sub)
    Spacer(Modifier.height(6.dp))
    ActionSlotRow("单击", el.clickAction, el.clickValue, { onOpenPicker(0) }) { v ->
        onChange { it.copy(clickValue = v) }
    }
    ActionSlotRow("双击", el.doubleClickAction, el.doubleClickValue, { onOpenPicker(2) }) { v ->
        onChange { it.copy(doubleClickValue = v) }
    }
    ActionSlotRow("三击", el.tripleClickAction, el.tripleClickValue, { onOpenPicker(3) }) { v ->
        onChange { it.copy(tripleClickValue = v) }
    }
    ActionSlotRow("长按", el.longClickAction, el.longClickValue, { onOpenPicker(1) }) { v ->
        onChange { it.copy(longClickValue = v) }
    }
    if (el.doubleClickAction != ElementAction.NONE || el.tripleClickAction != ElementAction.NONE) {
        Text("配了双击或三击后，单击会稍等约 0.3 秒再触发", fontSize = 11.sp, color = CustomUi.Hint)
        Spacer(Modifier.height(6.dp))
    }
}

/** 动作选择行 + 参数框，四个手势槽共用。 */
@Composable
private fun ActionSlotRow(
    label: String,
    action: ElementAction,
    value: String,
    onOpen: () -> Unit,
    onValueChange: (String) -> Unit
) {
    ActionRow(label = label, action = action) { onOpen() }
    if (action.needsValue) {
        Spacer(Modifier.height(6.dp))
        CustomField(value = value, onValueChange = onValueChange, hint = action.hint)
    }
    Spacer(Modifier.height(8.dp))
}

/** 宽高独立调整；开启等比时另一边按当前比例跟着变，专门伺候色块和图片。 */
private fun resizeElement(
    el: CanvasElement,
    delta: Float,
    width: Boolean,
    keepRatio: Boolean
): CanvasElement {
    if (!keepRatio) {
        return if (width) el.copy(widthDp = (el.widthDp + delta).coerceIn(24f, 720f))
        else el.copy(heightDp = (el.heightDp + delta).coerceIn(24f, 1280f))
    }
    val ratio = el.heightDp / el.widthDp.coerceAtLeast(1f)
    return if (width) {
        val w = (el.widthDp + delta).coerceIn(24f, 720f)
        el.copy(widthDp = w, heightDp = (w * ratio).coerceIn(24f, 1280f))
    } else {
        val h = (el.heightDp + delta).coerceIn(24f, 1280f)
        el.copy(heightDp = h, widthDp = (h / ratio).coerceIn(24f, 720f))
    }
}

/**
 * 用窗口可见区域差计算软键盘高度（dp）。
 * 不依赖 WindowInsets.ime —— MainActivity 的 windowSoftInputMode 是 adjustNothing，
 * 该模式下 Compose 的 IME insets 可能恒为 0；这个监听器在任意模式下都有效。
 */
@Composable
private fun rememberKeyboardHeightDp(): Float {
    val view = LocalView.current
    val density = LocalDensity.current.density
    var heightPx by remember { mutableStateOf(0) }
    DisposableEffect(view) {
        val observer = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val diff = view.rootView.height - rect.bottom
            val next = if (diff > 200) diff else 0
            if (next != heightPx) heightPx = next
        }
        val vto = view.viewTreeObserver
        vto.addOnGlobalLayoutListener(observer)
        onDispose {
            if (vto.isAlive) vto.removeOnGlobalLayoutListener(observer)
            else view.viewTreeObserver.removeOnGlobalLayoutListener(observer)
        }
    }
    return (heightPx / density).coerceAtLeast(0f)
}

/** 把 content:// 图片拷进内部存储，返回绝对路径；失败返回 null。 */
private fun copyImageToInternal(ctx: Context, uri: Uri): String? = runCatching<String?> {
    val dir = java.io.File(ctx.filesDir, "custom_assets")
    if (!dir.exists()) dir.mkdirs()
    val mime = ctx.contentResolver.getType(uri)
    val suffix = when {
        mime?.contains("png") == true -> "png"
        mime?.contains("webp") == true -> "webp"
        mime?.contains("gif") == true -> "gif"
        else -> "jpg"
    }
    val dest = java.io.File(dir, "img_${System.currentTimeMillis()}.$suffix")
    val input = ctx.contentResolver.openInputStream(uri) ?: return@runCatching null
    input.use { src -> dest.outputStream().use { out -> src.copyTo(out) } }
    if (dest.length() == 0L) {
        dest.delete()
        return@runCatching null
    }
    dest.absolutePath
}.getOrNull()
