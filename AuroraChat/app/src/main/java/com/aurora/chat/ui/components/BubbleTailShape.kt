package com.aurora.chat.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** 气泡尖角朝哪边：对方消息朝左（LEFT）、自己的消息朝右（RIGHT）、关闭时不凸（NONE）。 */
enum class BubbleTailSide { NONE, LEFT, RIGHT }

/**
 * 文字气泡轮廓：圆角矩形 + 侧边一个小尖角。
 *
 * 关键点：**整条轮廓是一条闭合路径**，尖角是从侧边直接弯出来的，不是另外叠一个三角形。
 * 所以尖角与气泡主体同一个填充、同一条边界，不会出现接缝、分界线、或「贴上去」的痕迹。
 *
 * 几何（默认值）：
 * - 凸出 [TAIL_WIDTH] = 6dp；
 * - 尖角基部高 [TAIL_HEIGHT] = 13dp；
 * - 尖端垂直位置固定在**距顶部 30%** 处（微信观感）：单行短气泡看着就是居中，
 *   五六行的长气泡也不会把尖角甩到正中间。上下再夹一层安全边距，避免贴到圆角上。
 *
 * 用法：`Modifier.clip(BubbleTailShape(corner, side))`，并给尖角那一侧留出
 * [TAIL_WIDTH] 的内边距，尖角才有地方画（见 `bubbleBackground`）。
 */
class BubbleTailShape(
    private val corner: Dp,
    private val side: BubbleTailSide,
    private val tailWidth: Dp = TAIL_WIDTH,
    private val tailHeight: Dp = TAIL_HEIGHT
) : Shape {

    companion object {
        /** 尖角凸出距离（dp）—— 4.5dp，收一档，不再显得胖 */
        val TAIL_WIDTH = 4.5.dp

        /** 尖角基部高度（dp）—— 9dp，与凸出接近 1:1，尖端角度约 90°，小而圆钝、不削尖 */
        val TAIL_HEIGHT = 9.dp

        /** 尖端中心距气泡顶部的比例 */
        private const val TAIL_TOP_FRACTION = 0.30f
    }

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        if (side == BubbleTailSide.NONE || w <= 0f || h <= 0f) {
            return RoundedCornerShape(corner).createOutline(size, layoutDirection, density)
        }

        val c = with(density) { corner.toPx() }.coerceIn(0f, minOf(w, h) / 2f)
        val tw = with(density) { tailWidth.toPx() }.coerceIn(0f, w * 0.30f)
        val th = with(density) { tailHeight.toPx() }.coerceIn(0f, h * 0.70f)
        val half = th / 2f

        // 尖端中心：距顶部 30%，但夹在「躲开上下圆角」的安全范围内；气泡太矮就退回正中
        // +1.5dp 的余量，保证尖角基部不会正好压在圆角弧线上（否则交界处会有点夹、看着不圆润）
        val gap = with(density) { 1.5.dp.toPx() }
        val minY = c + half + gap
        val maxY = h - c - half - gap
        val centerY = if (minY <= maxY) (h * TAIL_TOP_FRACTION).coerceIn(minY, maxY) else h / 2f

        // 尖角两侧的控制点：往里收一点，让尖端不那么刀切，跟微信那种圆润的小尖角一致
        val ctrlX = tw * 0.68f
        val ctrlY = th * 0.28f

        val path = Path()
        if (side == BubbleTailSide.LEFT) {
            val bl = tw                              // 主体左边界（尖角在它左边）
            path.moveTo(bl + c, 0f)
            path.lineTo(w - c, 0f)
            path.quadraticBezierTo(w, 0f, w, c)
            path.lineTo(w, h - c)
            path.quadraticBezierTo(w, h, w - c, h)
            path.lineTo(bl + c, h)
            path.quadraticBezierTo(bl, h, bl, h - c)
            // 左边：往下走到尖角下沿 → 弯出尖端 → 弯回上沿 → 继续往上
            path.lineTo(bl, centerY + half)
            path.quadraticBezierTo(bl - ctrlX, centerY + ctrlY, 0f, centerY)
            path.quadraticBezierTo(bl - ctrlX, centerY - ctrlY, bl, centerY - half)
            path.lineTo(bl, c)
            path.quadraticBezierTo(bl, 0f, bl + c, 0f)
        } else {
            val br = w - tw                          // 主体右边界（尖角在它右边）
            path.moveTo(c, 0f)
            path.lineTo(br - c, 0f)
            path.quadraticBezierTo(br, 0f, br, c)
            path.lineTo(br, centerY - half)
            path.quadraticBezierTo(br + ctrlX, centerY - ctrlY, w, centerY)
            path.quadraticBezierTo(br + ctrlX, centerY + ctrlY, br, centerY + half)
            path.lineTo(br, h - c)
            path.quadraticBezierTo(br, h, br - c, h)
            path.lineTo(c, h)
            path.quadraticBezierTo(0f, h, 0f, h - c)
            path.lineTo(0f, c)
            path.quadraticBezierTo(0f, 0f, c, 0f)
        }
        path.close()
        return Outline.Generic(path)
    }
}
