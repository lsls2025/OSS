package com.aurora.chat.ui.components

import android.graphics.RenderEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.chat.ErrorReporter
import com.kyant.backdrop.asAndroidRuntimeShader
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/**
 * 消息气泡背景。
 *
 * 普通预设色：`clip + background` 实底，行为完全不变。
 *
 * 「液态」[BubbleColorStorage.LIQUID]：玻璃气泡，折射源取 [LocalChatGlassBackdrop]
 * （会话页背景图层，见 ChatGlassBackdropSource）。
 *
 * 折射必须挂在图层 RenderEffect 上：只有
 * `RenderEffect.createRuntimeShaderEffect(shader, "content")` 才会把图层像素绑成 AGSL 的
 * `content`；用 ShaderBrush 画矩形永远拿不到内容（Skia 的 shader 不能读下方画布）。
 *
 * 采样预算：凸透镜边缘要取气泡轮廓**外侧**的背景像素，所以把 `padding` 设为折射高度，
 * 让 drawBackdrop 在气泡四周多录一圈真实背景（库实现：图层尺寸 = size + padding*2，
 * 录制前 canvas 平移 padding），着色器内用 origin=padding 换回气泡局部坐标。
 */
@Composable
fun Modifier.bubbleBackground(
    bubbleColor: Color,
    corner: Dp = 12.dp,
    /** 胶囊形（气泡两侧全圆；圆角矩形留给分享卡片等） */
    capsule: Boolean = false,
    /** 侧边小尖角（「气泡设置 → 气泡凸起」）：只给文字气泡用，图片/视频/卡片一律 [BubbleTailSide.NONE] */
    tail: BubbleTailSide = BubbleTailSide.NONE
): Modifier {
    if (!BubbleColorStorage.isLiquid(bubbleColor)) {
        if (tail == BubbleTailSide.NONE) {
            return this.clip(RoundedCornerShape(corner))
                .background(bubbleColor)
        }
        // 尖角：整条轮廓（圆角 + 尖角）由 BubbleTailShape 一次性画完，不叠三角形，所以没有接缝。
        // 尖角那一侧留出凸出宽度，尖角才有地方画；内层文字仍贴主体边缘，不会压到尖角上。
        val inset = BubbleTailShape.TAIL_WIDTH
        return this.clip(BubbleTailShape(corner, tail))
            .background(bubbleColor)
            .padding(
                start = if (tail == BubbleTailSide.LEFT) inset else 0.dp,
                end = if (tail == BubbleTailSide.RIGHT) inset else 0.dp
            )
    }
    val fallback = rememberCanvasBackdrop {
        // 录制层的 DrawScope 尺寸已包含 padding，这里铺满整层，避免折射在预览里采到空白
        drawRect(Brush.verticalGradient(listOf(Color(0xFFEDF1F7), Color(0xFFE0E7F1))))
    }
    val backdrop = LocalChatGlassBackdrop.current ?: fallback
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { if (capsule) Capsule() else RoundedRectangle(corner) },
        effects = {
            val shortSide = minOf(size.width, size.height)
            // 折射带高度：太厚会把整颗气泡压成波纹，取短边的 22% 且不超过 16dp
            val refraction = minOf(shortSide * 0.22f, 16.dp.toPx())
            if (refraction > 0f) {
                padding = refraction
                if (isRuntimeShaderSupported()) {
                    try {
                        val shader = obtainRuntimeShader("LiquidBubbleLens", LIQUID_GLASS_AGSL)
                        shader.setFloatUniform("origin", padding, padding)
                        shader.setFloatUniform("bubbleSize", size.width, size.height)
                        shader.setFloatUniform("radius", if (capsule) shortSide * 0.5f else corner.toPx())
                        shader.setFloatUniform("refractionHeight", refraction)
                        // 位移上限略小于 padding，保证采样点不会落到录制层外
                        shader.setFloatUniform("strength", refraction * 0.9f)
                        shader.setFloatUniform("dispersion", refraction * 0.06f)
                        renderEffect = RenderEffect
                            .createRuntimeShaderEffect(shader.asAndroidRuntimeShader(), "content")
                            .asComposeRenderEffect()
                    } catch (e: Exception) {
                        // 个别机型 AGSL 编译/驱动失败时降级为普通玻璃，不闪退；只记一次，避免刷屏
                        renderEffect = null
                        reportLensFailure(e)
                    }
                }
            }
            // 先挂折射，再包裹色彩增强/模糊（库内部把已有 renderEffect 当 inner）
            vibrancy()
            blur(1.dp.toPx())
        }
    )
}

/** 着色器失败只上报一次：每个气泡都会走这段代码，重复写日志会把日志系统刷爆 */
private val lensFailureReported = java.util.concurrent.atomic.AtomicBoolean(false)

private fun reportLensFailure(e: Exception) {
    if (lensFailureReported.compareAndSet(false, true)) {
        ErrorReporter.warn("LiquidBubble", "液态气泡折射着色器不可用，已降级为普通玻璃：${e.message}")
    }
}

/**
 * 凸透镜 AGSL：以圆角矩形/胶囊 SDF 为轮廓，边缘位移最大、向中心快速衰减到 0，
 * 沿外法线到气泡外侧取样（padding 提供像素），并按位移量做 RGB 色散。
 * 轮廓外原样返回，不参与弯折。
 */
private const val LIQUID_GLASS_AGSL = """
uniform shader content;
uniform float2 origin;        // 气泡左上角在录制图层内的坐标（= padding）
uniform float2 bubbleSize;    // 气泡自身尺寸
uniform float radius;         // 圆角半径（胶囊 = 短边一半）
uniform float refractionHeight;
uniform float strength;
uniform float dispersion;

float sdRoundRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

half4 main(float2 coord) {
    float2 p = coord - origin;
    float2 halfSize = bubbleSize * 0.5;
    float2 c = p - halfSize;
    float sd = sdRoundRect(c, halfSize, radius);
    if (sd > 0.0) {
        return content.eval(coord);
    }

    float t = clamp(-sd / refractionHeight, 0.0, 1.0);
    float edge = 1.0 - t;
    float amount = strength * edge * edge;

    // SDF 外法线方向
    float2 halfMax = max(halfSize - radius, float2(0.0));
    float2 clamped = clamp(c, -halfMax, halfMax);
    float2 delta = c - clamped;
    float2 dir = length(delta) > 0.001 ? normalize(delta) : float2(0.0);

    float2 samplePos = coord + dir * amount;
    float split = dispersion * edge;

    half4 cr = content.eval(samplePos + dir * split);
    half4 cg = content.eval(samplePos);
    half4 cb = content.eval(samplePos - dir * split);

    return half4(cr.r, cg.g, cb.b, max(max(cr.a, cg.a), cb.a));
}
""";
