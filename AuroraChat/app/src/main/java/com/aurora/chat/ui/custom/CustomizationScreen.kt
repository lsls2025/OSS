package com.aurora.chat.ui.custom

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 「其他 → 自定义」管理页。
 *
 * 视觉对齐群聊信息页：浅灰底、纯白无阴影卡片、深蓝强调色、0.5dp 分割线。
 * 悬浮窗一项控制的是**系统级悬浮窗服务**（卡片 / 悬浮条 / 悬浮球 / 小图标在编辑器里选），
 * 首次开启会引导授予「显示在其他应用上层」权限。
 */
@Composable
fun CustomizationScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var extensions by remember { mutableStateOf(HostExtensionStore.all(ctx)) }
    var editing by remember { mutableStateOf<HostExtension?>(null) }
    var preview by remember { mutableStateOf<HostExtension?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var pendingShow by remember { mutableStateOf<HostExtension?>(null) }
    // 删除单个扩展 / 清空全部，都要先确认一次，不直接删
    var confirmDelete by remember { mutableStateOf<HostExtension?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    // 悬浮球状态存在 SharedPreferences 里，用这个版本号驱动界面刷新
    var floatRevision by remember { mutableStateOf(0) }

    // 从系统权限页、或从悬浮球上的「×」关闭后回到应用，都要重新读一次状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) floatRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 任何一次管理页操作出错都只跳过这一次，不把异常抛给界面。 */
    fun safe(tag: String, block: () -> Unit) {
        ExtensionSafety.guardRun(ctx, tag) { block() }
    }

    // 保护模式：上次有自定义功能崩过，冷启动会自动跳过启动画面与悬浮窗恢复
    val protectedMode = remember(floatRevision) { ExtensionSafety.isSafeMode(ctx) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (FloatingExtensionHost.hasOverlayPermission(ctx)) {
            pendingShow?.let { ext -> safe("开启悬浮窗") { FloatingExtensionHost.show(ctx, ext) } }
        }
        pendingShow = null
        floatRevision++
    }

    fun requestOverlay(after: HostExtension?) {
        pendingShow = after
        FloatingExtensionHost.beforeOverlaySettings()
        overlayLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            )
        )
    }

    fun showFloating(ext: HostExtension) {
        if (FloatingExtensionHost.hasOverlayPermission(ctx)) {
            FloatingExtensionHost.show(ctx, ext)
            floatRevision++
        } else {
            requestOverlay(ext)
        }
    }

    // 背景铺满整屏；状态栏留白由 CustomTopBar 内部负责（白底一直铺到顶部）。
    Box(Modifier.fillMaxSize().background(CustomUi.PageBg)) {
        Column(Modifier.fillMaxSize()) {
            CustomTopBar(
                title = "自定义",
                onBack = onClose,
                right = { TopAction("＋") { showCreate = true } }
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (protectedMode) {
                    CustomSection(modifier = Modifier.padding(vertical = 5.dp)) {
                        Text(
                            "保护模式已开启",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CustomUi.Danger
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            ExtensionSafety.reason(ctx).ifBlank { "上次异常已跳过，自定义功能已暂停" }
                                .take(60),
                            fontSize = 12.sp,
                            color = CustomUi.Hint,
                            maxLines = 2
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallAction("恢复", modifier = Modifier.weight(1f)) {
                                safe("解除保护模式") {
                                    ExtensionSafety.clearSafeMode(ctx)
                                    extensions = HostExtensionStore.all(ctx)
                                    floatRevision++
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            SmallAction("清空配置", danger = true, modifier = Modifier.weight(1f)) {
                                safe("清空自定义配置") {
                                    FloatingExtensionHost.hide(ctx)
                                    HostExtensionStore.clear(ctx)
                                    ExtensionSafety.clearSafeMode(ctx)
                                    extensions = HostExtensionStore.all(ctx)
                                    floatRevision++
                                }
                            }
                        }
                    }
                }

                if (extensions.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无扩展，点右上角 ＋ 新建", fontSize = 13.sp, color = CustomUi.Hint)
                    }
                }

                extensions.forEach { ext ->
                    val showing = remember(floatRevision, ext.id) {
                        FloatingExtensionHost.isActive(ctx, ext.id)
                    }
                    CustomSection(modifier = Modifier.padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    ext.name.ifBlank { "未命名" },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CustomUi.Text
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    if (ext.kind == ExtensionKind.FLOATING_WINDOW) {
                                        "系统悬浮窗（${ext.floatingStyle.label}）"
                                    } else "启动画面",
                                    fontSize = 12.sp,
                                    color = CustomUi.Hint
                                )
                            }
                            if (ext.kind == ExtensionKind.LAUNCH_SCREEN) {
                                CustomSwitch(
                                    checked = ext.enabled,
                                    onCheckedChange = { on ->
                                        safe("切换启用状态") {
                                            extensions = HostExtensionStore.setEnabled(ctx, extensions, ext.id, on)
                                        }
                                    }
                                )
                            } else {
                                SmallAction(
                                    label = if (showing) "关闭" else "显示",
                                    active = showing
                                ) {
                                    safe("切换悬浮窗") {
                                        if (FloatingExtensionHost.isActive(ctx, ext.id)) {
                                            FloatingExtensionHost.hide(ctx)
                                            floatRevision++
                                        } else {
                                            showFloating(ext)
                                        }
                                    }
                                }
                            }
                        }
                        // 启动画面卡片上直接调显示时长，不用进编辑器翻设置页
                        if (ext.kind == ExtensionKind.LAUNCH_SCREEN) {
                            Spacer(Modifier.height(8.dp))
                            Stepper(
                                label = "时长",
                                value = "${ext.durationMs}ms",
                                onMinus = {
                                    safe("调整启动时长") {
                                        val next = ext.copy(
                                            durationMs = (ext.durationMs - 500L).coerceAtLeast(0L)
                                        )
                                        HostExtensionStore.upsert(ctx, next)
                                        extensions = HostExtensionStore.all(ctx)
                                    }
                                },
                                onPlus = {
                                    safe("调整启动时长") {
                                        val next = ext.copy(
                                            durationMs = (ext.durationMs + 500L).coerceAtMost(10_000L)
                                        )
                                        HostExtensionStore.upsert(ctx, next)
                                        extensions = HostExtensionStore.all(ctx)
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        CustomDivider()
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallAction("编辑", modifier = Modifier.weight(1f)) { editing = ext }
                            Spacer(Modifier.width(8.dp))
                            SmallAction("预览", modifier = Modifier.weight(1f)) { preview = ext }
                            Spacer(Modifier.width(8.dp))
                            SmallAction("删除", danger = true, modifier = Modifier.weight(1f)) {
                                confirmDelete = ext
                            }
                        }
                    }
                }

                // 只有真的存在悬浮窗扩展且权限没给时才提示，避免开屏就是一堆说明
                val needOverlay = extensions.any { it.kind == ExtensionKind.FLOATING_WINDOW } &&
                    !FloatingExtensionHost.hasOverlayPermission(ctx)
                if (needOverlay) {
                    Spacer(Modifier.height(10.dp))
                    CustomSection {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "需要悬浮窗权限",
                                fontSize = 13.sp,
                                color = CustomUi.Text,
                                modifier = Modifier.weight(1f)
                            )
                            SmallAction("开启") { requestOverlay(null) }
                        }
                    }
                }

                if (extensions.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    SmallAction("清空全部", danger = true) {
                        confirmClear = true
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // 新建类型选择：轻卡片，不用 M3 弹窗
        if (showCreate) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { showCreate = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 48.dp)
                        .fillMaxWidth()
                        .clip(CustomUi.CardShape)
                        .background(CustomUi.CardBg)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { /* 吃掉点击，避免穿透关闭 */ }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        "新建",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CustomUi.Text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                    CustomDivider()
                    CreateTypeRow("启动画面") {
                        showCreate = false
                        editing = HostExtensionStore.blank(ExtensionKind.LAUNCH_SCREEN, "我的启动画面")
                    }
                    CustomDivider()
                    CreateTypeRow("悬浮窗") {
                        showCreate = false
                        editing = HostExtensionStore.blank(ExtensionKind.FLOATING_WINDOW, "我的悬浮窗")
                    }
                }
            }
        }

        // 删除 / 清空确认：轻卡片，与新建类型选择同一风格
        if (confirmDelete != null || confirmClear) {
            val target = confirmDelete
            fun dismiss() {
                confirmDelete = null
                confirmClear = false
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { dismiss() },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 48.dp)
                        .fillMaxWidth()
                        .clip(CustomUi.CardShape)
                        .background(CustomUi.CardBg)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { /* 吃掉点击，避免穿透关闭 */ }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        if (confirmClear) "清空全部扩展？"
                        else "删除「${target?.name?.ifBlank { "未命名" } ?: ""}」？",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CustomUi.Text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                    Text(
                        if (confirmClear) "所有启动画面和悬浮窗配置都会被移除，无法恢复"
                        else "该扩展的画布与事件配置会被移除，无法恢复",
                        fontSize = 12.sp,
                        color = CustomUi.Hint,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    CustomDivider()
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallAction("取消", modifier = Modifier.weight(1f)) { dismiss() }
                        Spacer(Modifier.width(8.dp))
                        SmallAction(
                            if (confirmClear) "全部删除" else "删除",
                            danger = true,
                            modifier = Modifier.weight(1f)
                        ) {
                            safe("删除扩展") {
                                if (confirmClear) {
                                    FloatingExtensionHost.hide(ctx)
                                    HostExtensionStore.clear(ctx)
                                } else if (target != null) {
                                    FloatingExtensionHost.onRemoved(ctx, target.id)
                                    HostExtensionStore.remove(ctx, target.id)
                                }
                                extensions = HostExtensionStore.all(ctx)
                                floatRevision++
                            }
                            dismiss()
                        }
                    }
                }
            }
        }

        // 全屏预览：绑了事件的元素在这里点了也会真的执行，所见即所得
        preview?.let { p ->
            Box(Modifier.fillMaxSize()) {
                ExtensionCanvas(
                    spec = p.canvas,
                    modifier = Modifier.fillMaxSize(),
                    onElementAction = { _, action, value ->
                        if (action != ElementAction.NONE) {
                            ExtensionActionRunner.run(ctx, action, value)
                        } else if (p.action != ElementAction.NONE) {
                            ExtensionActionRunner.run(ctx, p.action, p.actionValue)
                        }
                    },
                    onCanvasAction = {
                        if (p.action != ElementAction.NONE) {
                            ExtensionActionRunner.run(ctx, p.action, p.actionValue)
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
                        ) { preview = null },
                    contentAlignment = Alignment.Center
                ) { Text("×", fontSize = 20.sp, color = Color.White) }
            }
        }

        // 可视化拖拽编辑器（全屏覆盖）
        editing?.let { e ->
            CanvasEditorScreen(
                initial = e,
                onClose = { editing = null },
                onSaved = {
                    safe("保存后刷新") {
                        extensions = HostExtensionStore.all(ctx)
                        // 悬浮球正在显示时即时刷新画面，不用关掉重开
                        FloatingExtensionHost.refresh(ctx)
                    }
                }
            )
        }
    }
}

@Composable
private fun CreateTypeRow(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, fontSize = 14.sp, color = CustomUi.Primary, fontWeight = FontWeight.Medium)
    }
}
