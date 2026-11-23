package com.aurora.chat.ui.components
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 每个字母的固定行高：与视觉一致，保证「点到哪个字母就是哪个字母」。 */
private val LETTER_ITEM_HEIGHT = 18.dp
/** 气泡与字母条的间隙（贴着字母但不粘连）。 */
private val BUBBLE_GAP = 8.dp

/**
 * 微信/QQ 风格字母索引栏（支持点击 + 拖动，触点精确对应字母）：
 *  - 字母按固定行高、整体垂直居中排布；
 *  - 手指按下/拖动时，把 Y 坐标按「同一套行高 + 居中偏移」换算成字母，
 *    做到点哪个字母就选哪个字母（而不是按整条高度粗略均分）；
 *  - 拖动即时跟手，气泡贴右侧显示当前字母，抬起消失。
 *
 * @param letters 字母表（如 A-Z + #，或仅存在的分组字母）
 * @param onLetterSelected 选中字母回调（仅在字母变化时触发）
 */
@Composable
fun AlphabetIndexBar(
    letters: List<String>,
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (letters.isEmpty()) return
    val density = LocalDensity.current
    var activeLetter by remember { mutableStateOf<String?>(null) }
    var activeY by remember { mutableStateOf(0f) }

    BoxWithConstraints(modifier = modifier.width(28.dp).fillMaxHeight()) {
        val barHeight = maxHeight
        val barHeightPx = with(density) { barHeight.toPx() }
        val itemPx = with(density) { LETTER_ITEM_HEIGHT.toPx() }
        val totalPx = itemPx * letters.size
        // 字母整体垂直居中时的顶部偏移：触摸换算与视觉排布共用同一套几何
        val topPadPx = ((barHeightPx - totalPx) / 2f).coerceAtLeast(0f)

        Box(Modifier.fillMaxSize()) {
            // 字母列：固定行高、整体垂直居中
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center
            ) {
                letters.forEach { letter ->
                    Box(
                        modifier = Modifier.height(LETTER_ITEM_HEIGHT).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = letter,
                            fontSize = 10.sp,
                            fontWeight = if (letter == activeLetter) FontWeight.Bold else FontWeight.Normal,
                            color = if (letter == activeLetter) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextSecondary()
                        )
                    }
                }
            }

            // 手势层：覆盖整条栏；触点按同一套行高 + 居中偏移换算，点到哪就是哪
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(letters) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun pick(y: Float): String {
                                val idx = ((y - topPadPx) / itemPx).toInt()
                                    .coerceIn(0, letters.lastIndex)
                                return letters[idx]
                            }
                            try {
                                pick(down.position.y).let {
                                    activeLetter = it
                                    activeY = down.position.y
                                    onLetterSelected(it)
                                }
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    pick(change.position.y).let {
                                        if (it != activeLetter) {
                                            activeLetter = it
                                            activeY = change.position.y
                                            onLetterSelected(it)
                                        }
                                    }
                                }
                            } finally {
                                activeLetter = null
                            }
                        }
                    }
            )

            // 跟手气泡：贴右侧、与字母条留 BUBBLE_GAP 空隙，垂直跟随并钳在栏内
            activeLetter?.let { letter ->
                val bubbleSize = 52.dp
                val rawY = with(density) { (activeY - bubbleSize.toPx() / 2).toDp() }
                val clampedY: Dp = when {
                    rawY < 0.dp -> 0.dp
                    rawY > barHeight - bubbleSize -> (barHeight - bubbleSize).coerceAtLeast(0.dp)
                    else -> rawY
                }
                Box(
                    modifier = Modifier
                        // 必须用 TopEnd：offset(y) 是相对对齐点偏移，
                        // 若用 CenterEnd 会从「垂直居中」再往下偏，导致按 A 时气泡在中间、按 Z 时飘出屏幕。
                        .align(Alignment.TopEnd)
                        // 字母条宽 28dp + 间隙 8dp = 气泡右边缘距屏幕右侧 36dp
                        .offset(x = -(28.dp + BUBBLE_GAP), y = clampedY)
                        .size(bubbleSize)
                        .clip(CircleShape)
                        .background(Color(0xCC374151)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(letter, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
