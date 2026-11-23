package com.aurora.chat.ui.components
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 流光文字(shimmer / text shimmer)。
 *
 * 用在"任务正在进行"的提示文案上(思考中 / 等待模型响应 / 正在汇总…),替代过去那种一动不动的静态占位:
 * 静态占位只能表达"这里将来有东西",流光文字能直接表达"现在正在跑"。视觉等价于 ChatGPT 的
 * "Thinking"、Cursor 的执行状态行。
 *
 * 两段动画叠加,而不是单纯改颜色:
 * 1. **扫光**:把一段「实色 → 浅色 → 实色」的线性渐变当作文字的笔刷,并让渐变带沿文字方向循环平移,
 *    看上去就是一道光反复掠过(比整体变色高级,因为高亮是局部的、会跑)。
 * 2. **呼吸**:整行透明度在 1.0 ↔ 0.55 之间往返(周期取扫光的一半),叠加出「忽明忽暗」的进行感 ——
 *    也就是"变浅 → 变回实色 → 再变浅"的那层反复。
 *
 * 实现要点:渐变必须铺在**实际文字宽度**上,所以首帧先按纯色渲染,由 onTextLayout 量到宽度后再接上扫光
 * (首帧只差一帧,肉眼无感);这样无需调用方关心文本多长,短到两个字、长到整句都对。
 *
 * 两种动画可分别取用:[sweep]=false 关掉扫光只留闪烁(交付总结卡片即此形态),
 * 再配合 [blinkAlpha] 由外层下发统一节奏,使同一张卡片里的所有文字**同步**明暗。
 *
 * @param color 文字的实色(常态色)
 * @param highlightColor 扫光高亮色,应比 [color] 更浅,才能形成"浅色掠过"
 * @param durationMillis 一次扫光的周期;呼吸周期取它的一半
 * @param softWrap 是否允许换行。单行状态文案(思考中 / 正在汇总…)保持 false;
 *   **多行正文**(思考内容、交付总结字段内容)必须传 true 并按 [lineHeight] 排版,
 *   否则会被压成一行、宽度溢出被裁(视觉上就是"文字残缺")。
 * @param sweep 是否启用"扫光"(渐变带掠过文字)。交付总结卡片统一传 false:它只要**整体闪烁**。
 * @param blinkAlpha 由外层统一提供的闪烁透明度。非空 → 用它替代本组件自己的呼吸动画,
 *   使同一张卡片内的所有文字**严格同步**明暗(各自 rememberInfiniteTransition 会各跑各的、相位错开)。
 */
@Composable
fun ShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
    fontWeight: FontWeight? = null,
    color: Color = com.aurora.chat.ui.theme.appTextHint(),
    highlightColor: Color = Color(0xFFE2E8F0),
    durationMillis: Int = 1400,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
    softWrap: Boolean = false,
    lineHeight: TextUnit? = null,
    sweep: Boolean = true,
    blinkAlpha: Float? = null
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    // 扫光进度 0→1:渐变带从文字左侧外部推进到右侧外部。
    // sweep=false 时**不订阅**它 —— 订阅了它每帧变化就会把本组件重组一遍,纯属浪费。
    val sweepProgress: Float = if (sweep) {
        val s by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmerSweep"
        )
        s
    } else 0f
    // 整行呼吸:1.0 ↔ 目标值往返(周期取扫光一半,两者错开才像"活的")。
    // 多行正文的呼吸幅度刻意收窄(0.86) —— 一大段文字在 0.55 透明度下会明显难读,
    // 而单行短状态文案降到 0.55 反而更醒目,所以按"是不是多行"分档。
    val innerBreath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (softWrap) 0.86f else 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerBreath"
    )
    // 外层给了统一节奏就用它;`?:` 短路 ⇒ 此时**不会**读取 innerBreath,也就不会按内层节奏重组。
    val alpha = blinkAlpha ?: innerBreath
    // 文本实测宽度:渐变带要严格按它铺开(0 = 还没量到,先走纯色)
    var textWidth by remember { mutableFloatStateOf(0f) }
    val bandPx = with(LocalDensity.current) { 72.dp.toPx() }
    val brush = if (sweep) remember(sweepProgress, textWidth, color, highlightColor, bandPx) {
        if (textWidth <= 0f) null
        else {
            val startX = -bandPx + sweepProgress * (textWidth + bandPx * 2f)
            Brush.linearGradient(
                colors = listOf(color, highlightColor, color),
                start = Offset(startX, 0f),
                end = Offset(startX + bandPx, 0f)
            )
        }
    } else null
    Text(
        text = text,
        modifier = modifier.alpha(alpha),
        // 注意:TextStyle.copy 有**两个互斥重载**(一个收 color、一个收 brush),不能同时传 color+brush;
        // 所以按"有没有量到宽度"二选一:未量到 → 纯色;量到 → 笔刷(笔刷自重着色,color 让位)。
        style = if (brush == null) {
            LocalTextStyle.current.copy(
                color = color, fontSize = fontSize, fontWeight = fontWeight,
                lineHeight = lineHeight ?: LocalTextStyle.current.lineHeight
            )
        } else {
            LocalTextStyle.current.copy(
                brush = brush, fontSize = fontSize, fontWeight = fontWeight,
                lineHeight = lineHeight ?: LocalTextStyle.current.lineHeight
            )
        },
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = TextOverflow.Clip,
        textAlign = textAlign,
        onTextLayout = { layout ->
            val w = layout.size.width.toFloat()
            if (w > 0f && w != textWidth) textWidth = w
        }
    )
}
