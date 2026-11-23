package com.aurora.chat.ui.custom

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.R
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * 把 [CanvasSpec] 渲染成画面。
 *
 * 编辑器预览、真机启动层、悬浮窗共用这一个渲染器；编辑器里另有一个交互版画布，
 * 但元素的换算与内容渲染都复用本文件的工具，保证「拖的时候看到什么，运行起来就是什么」。
 */

/** 设计 dp → 实际布局 dp 的等比缩放，以及画布在容器中的居中偏移（单位都是 dp）。 */
internal data class CanvasTransform(val scale: Float, val offsetX: Float, val offsetY: Float)

internal fun computeCanvasTransform(
    spec: CanvasSpec,
    maxWidthDp: Float,
    maxHeightDp: Float
): CanvasTransform {
    val designW = spec.widthDp.coerceAtLeast(1f)
    val designH = spec.heightDp.coerceAtLeast(1f)
    val scale = min(maxWidthDp / designW, maxHeightDp / designH)
    return CanvasTransform(
        scale = scale,
        offsetX = (maxWidthDp - designW * scale) / 2f,
        offsetY = (maxHeightDp - designH * scale) / 2f
    )
}

internal fun canvasBrush(spec: CanvasSpec): Brush =
    if (spec.backgroundEnd != 0L) {
        Brush.verticalGradient(listOf(Color(spec.background), Color(spec.backgroundEnd)))
    } else {
        SolidColor(Color(spec.background))
    }

/** 元素在容器中的摆放（编辑器与运行时共用）；offsetX/Y 传 0 表示容器本身就是画布。 */
internal fun Modifier.canvasElementPlacement(
    el: CanvasElement,
    scale: Float,
    offsetX: Float = 0f,
    offsetY: Float = 0f
): Modifier {
    val shape = RoundedCornerShape((el.cornerRadiusDp * scale).coerceAtLeast(0f).dp)
    return this
        .offset(
            x = (offsetX + el.xDp * scale).dp,
            y = (offsetY + el.yDp * scale).dp
        )
        .size(
            width = (el.widthDp * scale).coerceAtLeast(0f).dp,
            height = (el.heightDp * scale).coerceAtLeast(0f).dp
        )
        .graphicsLayer {
            rotationZ = el.rotation
            alpha = el.alpha.coerceIn(0f, 1f)
        }
        .clip(shape)
        .background(Color(el.bgColor))
        .then(
            if (el.borderWidthDp > 0f) {
                Modifier.border(
                    width = (el.borderWidthDp * scale).coerceAtLeast(0.5f).dp,
                    color = Color(el.borderColor),
                    shape = shape
                )
            } else Modifier
        )
}

@Composable
fun ExtensionCanvas(
    spec: CanvasSpec,
    modifier: Modifier = Modifier,
    /** 非 null 时元素可点：单击触发 clickAction，长按触发 longClickAction（预览用）。 */
    onElementAction: ((el: CanvasElement, action: ElementAction, value: String) -> Unit)? = null,
    /** 非 null 时点画布空白处触发整体动作（预览用）。 */
    onCanvasAction: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    // 只有真的写了 {变量} 才每秒重组；纯静态画布不会空转。
    val hasVariables = spec.elements.any { DynamicValues.hasVariables(it.text) }
    var tick by remember { mutableStateOf(0) }
    if (hasVariables) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000L)
                tick++
            }
        }
    }
    BoxWithConstraints(
        modifier = modifier
            .background(canvasBrush(spec))
            .then(
                if (onCanvasAction != null) {
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onCanvasAction() }
                } else Modifier
            )
    ) {
        val t = computeCanvasTransform(spec, maxWidth.value, maxHeight.value)
        spec.elements.forEach { el ->
            val interactive = onElementAction != null &&
                (el.clickAction != ElementAction.NONE || el.longClickAction != ElementAction.NONE)
            Box(
                modifier = Modifier
                    .canvasElementPlacement(el, t.scale, t.offsetX, t.offsetY)
                    .then(
                        if (interactive) {
                            Modifier.pointerInput(el.id, el.clickAction, el.longClickAction) {
                                detectTapGestures(
                                    onTap = {
                                        if (el.clickAction != ElementAction.NONE) {
                                            onElementAction?.invoke(el, el.clickAction, el.clickValue)
                                        }
                                    },
                                    onLongPress = {
                                        if (el.longClickAction != ElementAction.NONE) {
                                            onElementAction?.invoke(el, el.longClickAction, el.longClickValue)
                                        }
                                    }
                                )
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                CanvasElementContent(el, t.scale, ctx, tick)
            }
        }
    }
}

@Composable
internal fun CanvasElementContent(
    el: CanvasElement,
    scale: Float,
    ctx: Context = LocalContext.current,
    tick: Int = 0
) {
    when (el.kind) {
        CanvasElementKind.TEXT, CanvasElementKind.BUTTON -> {
            val raw = remember(el.text, tick) { DynamicValues.resolve(el.text, ctx) }
            val weight = if (el.bold || el.kind == CanvasElementKind.BUTTON) {
                FontWeight.SemiBold
            } else FontWeight.Normal
            // 字号按元素框自动收缩：画布缩放很小或框偏矮时，也不会把字裁掉半截
            val size = fittedFontSize(
                text = raw,
                base = el.fontSizeSp * scale,
                maxWidthDp = el.widthDp * scale,
                maxHeightDp = el.heightDp * scale,
                weight = weight
            )
            Text(
                text = raw,
                style = TextStyle(
                    fontSize = size.sp,
                    fontWeight = weight,
                    color = Color(el.textColor),
                    textAlign = when (el.textAlign) {
                        0 -> TextAlign.Start
                        2 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    // 等宽数字：时间/网速数值变化时字宽不变，不会左右跳
                    fontFeatureSettings = "tnum"
                ),
                maxLines = 3
            )
        }

        CanvasElementKind.IMAGE, CanvasElementKind.ICON -> {
            // assetPath 为空时回退到内置品牌图；非空时读用户选的本地图片（已拷进内部存储）。
            val bitmap = remember(el.assetPath) {
                if (el.assetPath.isBlank()) null
                else runCatching { android.graphics.BitmapFactory.decodeFile(el.assetPath) }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = el.text.ifBlank { "图片" },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_app_icon),
                    contentDescription = el.text.ifBlank { "应用图标" },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        CanvasElementKind.SHAPE -> Unit // 只画背景色块
    }
}

/**
 * 找到能把 [text] 完整放进元素框的字号。
 *
 * 之前统一给字号兜底 8sp，画布缩到很小（悬浮球、小画布、模板缩略图）时
 * 8sp 的字塞进几 dp 高的框里，就会被裁成残缺的字；这里改为按实际测量收缩。
 */
@Composable
private fun fittedFontSize(
    text: String,
    base: Float,
    maxWidthDp: Float,
    maxHeightDp: Float,
    weight: FontWeight
): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, base, maxWidthDp, maxHeightDp, weight, density) {
        if (text.isBlank() || base <= 0f) {
            base
        } else {
            val maxW = with(density) { maxWidthDp.dp.toPx() }.toInt().coerceAtLeast(1)
            val maxH = with(density) { maxHeightDp.dp.toPx() }.toInt().coerceAtLeast(1)
            var size = base
            var tries = 0
            while (tries < 16 && size > 1f) {
                val r = runCatching {
                    measurer.measure(
                        text = AnnotatedString(text),
                        style = TextStyle(fontSize = size.sp, fontWeight = weight),
                        maxLines = 3,
                        constraints = Constraints(maxWidth = maxW)
                    )
                }.getOrNull()
                if (r == null) return@remember size
                if (r.size.height <= maxH) return@remember size
                size *= 0.88f
                tries++
            }
            size.coerceAtLeast(1f)
        }
    }
}
