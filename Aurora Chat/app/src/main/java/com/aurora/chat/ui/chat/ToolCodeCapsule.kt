package com.aurora.chat.ui.chat

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「工具调用」两处 UI（会话内联详情面板 / 「工具调用」全屏清单）共用的代码展示组件。
 *
 * 为什么抽成共享文件：内联详情面板要显示「运行 Python」的完整代码（用户要求「应该多知道几行啊」），
 * 与清单页的代码胶囊必须是同一套交互（等宽 / 折叠 12 行 / 点击复制 / 展开全部），
 * 各写一份必然越改越歪。原先这些组件是 AiToolCallsScreen 的私有实现，现提取供两处共用。
 */

/**
 * 行数会变化的文本：在「折叠 N 行」与「全文」之间切换时做高度过渡。
 *
 * 为什么不能直接改 maxLines：`Text` 的行数没有可插值的东西，改了就一步重排，卡片瞬间变高/变矮。
 * 这里的做法是让**容器高度**做过渡：
 *  - `animateContentSize` 测量到新高度后逐帧上报给父布局，所以卡片底边、后续卡片都跟着一起走；
 *  - 外层 `clipToBounds` 保证展开过程中文字被裁掉一部分，而不是溢出压住下面的内容
 *    （clip 放在 Box 上而不是同一个修饰符链里，这样裁切高度必定等于动画中的高度，不会错位）。
 */
@Composable
internal fun FoldingText(
    text: String,
    maxLines: Int,
    fontSize: TextUnit,
    color: Color,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    onLayoutOverflow: (Boolean) -> Unit = {}
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {
        Text(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(tween(220)),
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { onLayoutOverflow(it.hasVisualOverflow) }
        )
    }
}

/** 代码折叠行数：一屏能看个大概，又不至于把清单撑成代码编辑器。 */
internal const val CODE_FOLD_LINES = 12

/** 执行输出折叠行数：看得到结果开头，长输出点开再看全部。 */
internal const val OUTPUT_FOLD_LINES = 10

/** 变更预览折叠行数：小改动一屏看完，大改动点开。 */
internal const val DIFF_FOLD_LINES = 8

/** 复制到剪贴板 + Toast（输出 / 变更块共用；失败不抛异常） */
private fun copyText(ctx: android.content.Context, label: String, text: String, toast: String) {
    try {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(ctx, "复制失败", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 执行结果块（stdout / stderr / 失败原因）。
 *
 * 以前工具结果只交给 AI 读一遍就丢掉，用户点开「运行 Python」只能看到 timeout=30 这类属性；
 * 现在直接把真实输出摆出来：成功看结果，失败看报错。浅灰底 + 等宽，和代码胶囊同一视觉语言。
 * 交互同代码胶囊：点击任意位置复制全文，超过 [OUTPUT_FOLD_LINES] 行才出现「展开全部」。
 */
@Composable
internal fun ToolOutputBlock(output: String, failed: Boolean) {
    val ctx = LocalContext.current
    var expanded by remember(output) { mutableStateOf(false) }
    var overflow by remember(output) { mutableStateOf(false) }
    val noRipple = remember { MutableInteractionSource() }
    val lineCount = remember(output) { output.count { it == '\n' } + 1 }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF7F8FA))
            .clickable(interactionSource = noRipple, indication = null) {
                copyText(ctx, "输出", output, "输出已复制")
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (failed) "输出 · 执行失败" else "输出", fontSize = 10.sp,
                color = if (failed) Color(0xFFB91C1C) else Color(0xFF9CA3AF))
            Spacer(Modifier.weight(1f))
            Text("$lineCount 行", fontSize = 10.sp, color = Color(0xFF9CA3AF))
        }
        Spacer(Modifier.height(3.dp))
        FoldingText(
            text = output,
            maxLines = if (expanded) Int.MAX_VALUE else OUTPUT_FOLD_LINES,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = if (failed) Color(0xFFB91C1C) else Color(0xFF374151),
            onLayoutOverflow = { if (!expanded) overflow = it }
        )
        if (overflow) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (expanded) "收起 ▲" else "展开全部 ▼",
                fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = noRipple, indication = null) { expanded = !expanded }
                    .padding(vertical = 4.dp)
            )
        }
    }
}

/**
 * 文件变更预览块：`- 旧行` 红底、`+ 新行` 绿底，其余行灰色上下文。
 *
 * 数据来自工具参数里的 old_string / new_string / content / patch（见 [toolDiffOf]），
 * 不需要读取整个文件，所以能随调用立刻显示；代价是只覆盖被替换片段，不标真实行号。
 * 点击复制原文，超过 [DIFF_FOLD_LINES] 行折叠。
 */
@Composable
internal fun ToolDiffBlock(diff: String) {
    val ctx = LocalContext.current
    var expanded by remember(diff) { mutableStateOf(false) }
    val noRipple = remember { MutableInteractionSource() }
    val lines = remember(diff) { diff.lines() }
    val changed = remember(diff) { lines.count { isAddedLine(it) || isRemovedLine(it) } }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF7F8FA))
            .clickable(interactionSource = noRipple, indication = null) {
                copyText(ctx, "变更", diff, "变更内容已复制")
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("变更", fontSize = 10.sp, color = Color(0xFF9CA3AF))
            Spacer(Modifier.weight(1f))
            Text("$changed 行改动", fontSize = 10.sp, color = Color(0xFF9CA3AF))
        }
        Spacer(Modifier.height(3.dp))
        val shown = if (expanded) lines else lines.take(DIFF_FOLD_LINES)
        shown.forEach { line -> DiffLine(line) }
        if (lines.size > DIFF_FOLD_LINES) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (expanded) "收起 ▲" else "展开全部 ▼",
                fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = noRipple, indication = null) { expanded = !expanded }
                    .padding(vertical = 4.dp)
            )
        }
    }
}

/** 新增行：以 + 开头（兼容 `+ ` 与 apply_patch 的 `+code`），但排除 `+++` 文件头 */
private fun isAddedLine(line: String) = line.startsWith("+") && !line.startsWith("+++")

/** 删除行：以 - 开头，但排除 `---` 文件头 */
private fun isRemovedLine(line: String) = line.startsWith("-") && !line.startsWith("---")

@Composable
private fun DiffLine(line: String) {
    val added = isAddedLine(line)
    val removed = isRemovedLine(line)
    val bg = when {
        added -> Color(0xFFE8F7EE)
        removed -> Color(0xFFFDEDED)
        else -> Color.Transparent
    }
    val fg = when {
        added -> Color(0xFF166534)
        removed -> Color(0xFFB91C1C)
        else -> Color(0xFF6B7280)
    }
    Text(
        text = line,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFamily = FontFamily.Monospace,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 代码胶囊标题：说明这是哪门语言的代码，比笼统的「参数」信息量大。 */
internal fun codeCapsuleLabel(tool: String): String = when (tool) {
    "execute_python" -> "Python 代码"
    "serve_start" -> "服务脚本"
    "shell_exec" -> "Shell 脚本"
    else -> "代码"
}

/**
 * 代码胶囊：等宽字体 + 折叠 + 一键复制。
 *
 * 交互（用户要求「点击胶囊体内可以复制这个代码」）：
 *  · 胶囊内任意位置点击 = 复制全文 + Toast 提示（不需要再去找按钮）；
 *  · 底部「展开全部」是内层 clickable，会先消费点击，不会误触复制；
 *  · 复制的是 [code] 原文（保留缩进与换行），粘到 Termux / 编辑器即可直接复跑。
 *
 * 视觉与「本次参数」胶囊同款底/圆角，区别只在等宽字体和「点击复制」提示。
 */
@Composable
internal fun CodeCapsule(code: String, label: String) {
    val ctx = LocalContext.current
    var codeExpanded by remember(code) { mutableStateOf(false) }
    var codeOverflow by remember(code) { mutableStateOf(false) }
    val noRipple = remember { MutableInteractionSource() }
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF7F8FA))
            .clickable(interactionSource = noRipple, indication = null) {
                try {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, code))
                    Toast.makeText(ctx, "代码已复制（$lineCount 行）", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(ctx, "复制失败", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 10.sp, color = Color(0xFF9CA3AF))
            Spacer(Modifier.weight(1f))
            Text("$lineCount 行", fontSize = 10.sp, color = Color(0xFF9CA3AF))
        }
        Spacer(Modifier.height(3.dp))
        FoldingText(
            text = code,
            maxLines = if (codeExpanded) Int.MAX_VALUE else CODE_FOLD_LINES,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF374151),
            // 只有真被 12 行截断时才给「展开全部」，否则点了没变化会显得莫名其妙
            onLayoutOverflow = { if (!codeExpanded) codeOverflow = it }
        )
        if (codeOverflow) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (codeExpanded) "收起 ▲" else "展开全部 ▼",
                fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = noRipple, indication = null) {
                        codeExpanded = !codeExpanded
                    }
                    .padding(vertical = 4.dp)
            )
        }
    }
}
