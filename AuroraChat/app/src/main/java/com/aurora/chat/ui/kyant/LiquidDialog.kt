package com.aurora.chat.ui.kyant

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle

/**
 * LiquidDialog —— 与系统公告同方案的玻璃弹窗。
 *
 * 经典模式：完全等价于 material3.AlertDialog（参数逐字透传，行为不变）。
 * 液态模式：真 Dialog 是独立窗口，graphicsLayer 无法跨窗口捕获主界面内容 → 玻璃会退化成灰。
 * 因此改为「主窗口内 Box 覆盖层」：卡片自身铺一张背景图并用 layerBackdrop 捕获，
 * 玻璃层折射这张彩图（与 Kyant0 demo 折射壁纸原理一致）。卡片保留半透明白 surface 保证正文可读。
 *
 * 关键约束（见项目约定）：
 * 1) 覆盖层失去 Dialog 的返回键/点外关闭，这里补 BackHandler + 点遮罩关闭（dismissOnClickOutside=true）；
 * 2) 输入类弹窗（密码/卡密）传 glassSurfaceAlpha 调高，保证输入框可读。
 */
@Composable
fun LiquidDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.White,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: @Composable () -> Unit = {},
    icon: @Composable (() -> Unit)? = null,
    backdrop: com.kyant.backdrop.Backdrop? = null,
    glassSurfaceAlpha: Float = 0.42f,
    dismissOnClickOutside: Boolean = true,
) {
    val ctx = LocalContext.current
    val useGlass = false

    if (!useGlass) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            containerColor = containerColor,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            icon = icon,
        )
        return
    }

    // 折射源：默认纯色（纯色页面够用）；调用方也可传入彩色 backdrop（如社区背景图），
    // 避免纯色折射退化成灰白塑料感（项目约定 —— 玻璃必须折射有色彩的图层）。
    val defaultGlassBackdrop = rememberCanvasBackdrop { drawRect(Color(0xFFEFF1F5)) }
    val glassBackdrop = backdrop ?: defaultGlassBackdrop
    BackHandler(enabled = true) { onDismissRequest() }
    // 入场动画：整体淡入 + 卡片由 0.92 轻微放大到 1，避免"啪"一下出现显得生硬
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .then(if (dismissOnClickOutside) Modifier.clickable { onDismissRequest() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.fillMaxSize().background(Color(0x60000000)))

        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .heightIn(max = 540.dp)
                .graphicsLayer {
                    val p = appear.value
                    scaleX = 0.92f + 0.08f * p
                    scaleY = 0.92f + 0.08f * p
                }
        ) {
            Column(
                modifier = Modifier
                    .drawBackdrop(
                        backdrop = glassBackdrop,
                        shape = { RoundedRectangle(20.dp) },
                        effects = {
                            colorControls(brightness = 0.45f, saturation = 1.6f)
                            blur(6f.dp.toPx())
                            lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
                        },
                        highlight = { Highlight.Plain },
                        onDrawSurface = { drawRect(Color(0xFFFAFAFA).copy(alpha = glassSurfaceAlpha)) }
                    )
                    .clickable(enabled = false) {}
                    .padding(24.dp)
            ) {
                icon?.let {
                    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) { it() }
                }
                title?.let {
                    Box(Modifier.padding(bottom = 12.dp)) { it() }
                }
                text?.let {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // 【崩溃防护】本 Box 是这一层的最外层滚动容器，会向内容传递「无限最大高度」约束；
                        // 一旦调用方在自己的弹窗内容里又套了一层 verticalScroll / LazyColumn，
                        // 内层滚动容器拿到无限约束就会抛
                        // IllegalStateException: Vertically scrollable component was measured with an infinity maximum height
                        // （开发者广播弹窗就是这么崩的）。
                        // 这里用 heightIn 把无限高度收敛成一个极大的有限值：普通内容（短文本/表单）行为完全不变，
                        // 嵌套滚动容器则不再崩溃。
                        Box(Modifier.fillMaxWidth().heightIn(max = 3000.dp)) { it() }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton()
                    if (dismissButton !== confirmButton) {
                        Spacer(Modifier.width(12.dp))
                    }
                    confirmButton()
                }
            }
        }
    }
}

/**
 * GlassDialogOverlay —— 玻璃卡片容器，供「真 Dialog 窗口」类弹窗在液态模式下复用。
 *
 * 真 Dialog() 是独立窗口，玻璃无法捕获主界面内容（约束#1），因此液态模式不再用 Dialog，
 * 而是直接在主窗口内渲染全屏覆盖层 + 玻璃卡片（卡片自身铺背景图并用 layerBackdrop 捕获）。
 * 经典模式调用方应自行用原 Dialog(...) 包裹同一份 content，保持原样。
 */
@Composable
fun GlassDialogOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceAlpha: Float = 0.42f,
    dismissOnClickOutside: Boolean = true,
    content: @Composable () -> Unit,
) {
    val glassBackdrop = rememberCanvasBackdrop { drawRect(Color(0xFFEFF1F5)) }
    BackHandler(enabled = true) { onDismiss() }
    // 入场动画，与 LiquidDialog 保持一致
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .then(if (dismissOnClickOutside) Modifier.clickable { onDismiss() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.fillMaxSize().background(Color(0x60000000)))

        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .heightIn(max = 560.dp)
                .graphicsLayer {
                    val p = appear.value
                    scaleX = 0.92f + 0.08f * p
                    scaleY = 0.92f + 0.08f * p
                }
                .then(modifier)
        ) {
            Column(
                modifier = Modifier
                    .drawBackdrop(
                        backdrop = glassBackdrop,
                        shape = { RoundedRectangle(20.dp) },
                        effects = {
                            colorControls(brightness = 0.45f, saturation = 1.6f)
                            blur(6f.dp.toPx())
                            lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
                        },
                        highlight = { Highlight.Plain },
                        onDrawSurface = { drawRect(Color(0xFFFAFAFA).copy(alpha = surfaceAlpha)) }
                    )
                    .clickable(enabled = false) {}
                    .padding(24.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * 玻璃弹窗动作按钮。
 *
 * 液态模式：透明胶囊 + 彩色文字（与系统公告「确定」按钮完全一致）——
 *   surfaceColor = White.copy(0.3f) 提供极淡白底保证可读，文字保持品牌色（普通蓝 / 危险红）。
 * 经典模式：原 Material TextButton，保持经典观感一行不变。
 *
 * 用途：LiquidDialog 在液态下是玻璃卡片，若内部直接用 Material TextButton/Button，
 * 会出现"按钮不是蓝字透明胶囊"的观感不一致。统一改用本组件。
 */
@Composable
fun LiquidDialogButton(
    text: String,
    onClick: () -> Unit,
    textColor: Color = Color(0xFF1E40AF),
    enabled: Boolean = true,
) {
    val ctx = LocalContext.current
    val useGlass = false
    if (useGlass) {
        val backdrop = rememberCanvasBackdrop { drawRect(Color(0xFFEFF1F5)) }
        LiquidButton(
            onClick = { if (enabled) onClick() },
            backdrop = backdrop,
            surfaceColor = Color.White.copy(alpha = 0.3f)
        ) {
            Text(
                text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) textColor else textColor.copy(alpha = 0.45f)
            )
        }
    } else {
        TextButton(onClick = onClick, enabled = enabled) {
            Text(text, color = textColor)
        }
    }
}
