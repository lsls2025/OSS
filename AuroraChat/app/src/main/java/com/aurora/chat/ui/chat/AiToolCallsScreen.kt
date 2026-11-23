package com.aurora.chat.ui.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「工具调用」全屏清单(独立全屏,绝不嵌套):
 * 列出本条 AI 消息调用的全部工具——工具名 / 成败状态 / 发起时刻(精确到毫秒)。
 * 数据源为消息内持久化的 aiToolStages 列表,由 AiChatManager 在每次工具调用时采集。
 */
@Composable
fun AiToolCallsScreen(records: List<ChatMsg.ToolStageRecord>, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // 毫秒级时间戳格式化(统一在 remember 里建一次,避免列表项反复创建)
    val tsFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    // 展开状态:按条目下标记录(列表滚动重建也不丢)
    val expandedIdx = remember { mutableStateMapOf<Int, Boolean>() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            // 消费空区点击,防止穿透到下层 AI 聊天界面
            .clickable(interactionSource = noRipple, indication = null) {}
    ) {
        // ── 顶栏:白色背景先铺满(含状态栏区域),再让出状态栏高度 ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937))
            }
            Column(Modifier.weight(1f)) {
                Text("工具调用", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Text(
                    "共 ${records.size} 次工具调用",
                    fontSize = 11.sp, color = Color(0xFF9CA3AF)
                )
            }
            IconButton(onClick = {
                val copy = buildString {
                    records.forEach { r ->
                        append("[${if (r.state == 1) "成功" else "失败"}] ${toolDisplayName(r.tool)}")
                        // 具体目标 + 关键参数一并复制，便于用户拿去做对账/反馈。
                        // 目标压成单行（statusLineOf）：代码类工具的完整正文下方「代码:」段会原样带出，
                        // 这里再留多行只会把「一条记录一行」的对账格式打乱。
                        if (r.target.isNotBlank()) append("  ·  ${statusLineOf(r.target)}")
                        if (r.reason.isNotBlank()) append("  ·  ${r.reason}")
                        if (r.argsSummary.isNotBlank()) append("\n参数: ${r.argsSummary.replace("\n", "；")}")
                        // 代码全文一并带出：清单本身也是「用户要自己复跑」的入口
                        if (r.code.isNotBlank()) append("\n代码:\n${r.code}")
                        if (r.ts > 0) append("  ${tsFmt.format(Date(r.ts))}")
                        append("\n")
                    }
                }
                try {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("AI 工具调用清单", copy))
                    Toast.makeText(ctx, "已复制调用清单", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }) {
                Text("复制", fontSize = 13.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
            }
        }

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有工具调用记录", fontSize = 13.sp, color = Color(0xFF9CA3AF))
            }
            return@Column
        }

        // ── 调用清单 ──
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(records.size) { i ->
                val r = records[i]
                val ok = r.state == 1
                val expanded = expandedIdx[i] == true
                var reasonOverflow by remember(i) { mutableStateOf(false) }
                var targetOverflow by remember(i) { mutableStateOf(false) }
                // 产物块（本次参数）的第二级折叠：卡片展开后它可能极长（大段文件内容/命令输出），
                // 默认只显示前 6 行，溢出时由块底部自己的开关再展开，避免把整页撑爆。
                var argsExpanded by remember(i) { mutableStateOf(false) }
                var argsOverflow by remember(i) { mutableStateOf(false) }
                // 这张卡到底有没有「被折叠起来、需要点开才看得到」的内容：
                // 目标行被单行省略 / 原因被 3 行省略 / 有完整的本次参数（仅展开时显示）。
                // 三者都为假时卡片没有任何可展开的东西 —— 此时不能出现「展开详情」，更不能出现「收起」，
                // 点了也不该有任何变化（旧逻辑把 expanded 本身当成显示条件，导致空卡片点一下就冒出「收起」）。
                val hasMore = targetOverflow || reasonOverflow || r.argsSummary.isNotBlank() || r.code.isNotBlank()
                Column(
                    Modifier
                        .fillMaxWidth()
                        // 注意：这里**不能**用 animateContentSize。Compose 的 animateContentSize 只动画
                        // 「上报给父布局的高度」，内容在动画第一帧就已经变成最终尺寸——挂在带背景的卡片上时，
                        // 背景会瞬间缩到最终大小，而父布局仍按旧高度占位，于是卡片底部留出一块空白、
                        // 下面的卡片要等动画结束才补上来（用户实测反馈「胶囊瞬间消失、留一小块空位」）。
                        // 正确做法：卡片高度永远等于内容高度，展开/收起的高度变化全部由内容自己动画
                        // （胶囊用 expandVertically / shrinkVertically），这样卡片底边、后续卡片都逐帧跟随。
                        .clip(RoundedCornerShape(12.dp)).background(Color.White)
                        // 仅在有可展开内容时才响应点击（空卡片点了不做任何事）
                        .then(
                            if (hasMore) Modifier.clickable(interactionSource = noRipple, indication = null) {
                                expandedIdx[i] = !expanded
                            } else Modifier
                        )
                        .padding(12.dp)
                ) {
                    // 第一行:序号 + 工具名 + 状态徽标
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$i",
                            fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFF9CA3AF),
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFFF3F4F6))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            // 只显示中文短名：原始英文工具标识一律不出现在界面上（排查时看日志）
                            toolFriendlyName(r.tool),
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = Color(0xFF1F2937),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (ok) "调用成功" else "调用失败",
                            fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (ok) Color(0xFF15803D) else Color(0xFFB91C1C),
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (ok) Color(0x1A16A34A) else Color(0x1ADC2626))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    // 第二行:具体目标（哪个文件 / 哪条命令 / 哪个 URL / 什么关键字）
                    // 用户要求「一个不漏」：这条是明细页的对账核心，单行省略、点卡片展开看全文
                    if (r.target.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text("目标", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                        FoldingText(
                            text = r.target,
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF111827),
                            // 折叠态下记录是否真的被省略：只有省略了才值得展开
                            onLayoutOverflow = { if (!expanded) targetOverflow = it }
                        )
                    }
                    // 第三行:AI 自述调用原因(调用时间与工具名之间;手动审批/完全访问/root 模式均展示)
                    // 默认折叠 3 行;内容被省略时点卡片展开看全文。
                    // 代码类工具（execute_python / shell_exec / serve_start）的 reason 是自动生成的
                    // 「执行 Python 代码：<代码片段>」，与下方代码胶囊完全重复 —— 用户反馈「下方已经有
                    // 胶囊了，上面别再拿蓝色刷一遍代码」，所以有代码胶囊时这一行整条不渲染。
                    if (r.reason.isNotBlank() && r.code.isBlank()) {
                        Spacer(Modifier.height(6.dp))
                        FoldingText(
                            text = r.reason,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1E40AF),
                            onLayoutOverflow = { if (!expanded) reasonOverflow = it }
                        )
                    }
                    // 代码胶囊：跑代码的工具（execute_python / serve_start / shell_exec）把正文原样摊开，
                    // 点胶囊任意位置即复制全文 —— 参数摘要里只有「N 字 / M 行」，用户抄不走代码，
                    // 「想自己复跑一遍」就无从下手。层级与「本次参数」一致，走同样的展开/收起动画。
                    AnimatedVisibility(
                        visible = expanded && r.code.isNotBlank(),
                        enter = expandVertically(animationSpec = tween(220), expandFrom = Alignment.Top) +
                            fadeIn(tween(160)),
                        exit = shrinkVertically(animationSpec = tween(200), shrinkTowards = Alignment.Top) +
                            fadeOut(tween(120))
                    ) {
                        CodeCapsule(code = r.code, label = codeCapsuleLabel(r.tool))
                    }
                    // 第四块:展开后显示本次调用的完整参数（脱敏后的 key=value，大字段只留字数）
                    // 参数/产物可能极长（大段文件内容、命令输出），因此在块内做第二级折叠：
                    // 默认 6 行，只有真被截断时才在块底部出现「展开全部」，不占用卡片级的「展开详情」。
                    // 进出场必须带 expandVertically / shrinkVertically：只做 fadeIn/fadeOut 时节点是
                    // 「原地淡出、消失那一刻才让出高度」，收起的瞬间高度会一步塌掉（用户反馈的胶囊突然消失）。
                    AnimatedVisibility(
                        visible = expanded && r.argsSummary.isNotBlank(),
                        enter = expandVertically(animationSpec = tween(220), expandFrom = Alignment.Top) +
                            fadeIn(tween(160)),
                        exit = shrinkVertically(animationSpec = tween(200), shrinkTowards = Alignment.Top) +
                            fadeOut(tween(120))
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFF7F8FA))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("本次参数", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                                Spacer(Modifier.weight(1f))
                                Text("${r.argsSummary.length} 字", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                            }
                            FoldingText(
                                text = r.argsSummary,
                                maxLines = if (argsExpanded) Int.MAX_VALUE else 6,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = Color(0xFF4B5563),
                                // 折叠态下记录是否真被截断：只有截断了才值得显示「展开全部」
                                onLayoutOverflow = { if (!argsExpanded) argsOverflow = it }
                            )
                            if (argsOverflow) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (argsExpanded) "收起 ▲" else "展开全部 ▼",
                                    fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                                    // 内层消费点击，避免同时触发外层的整卡收起
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(interactionSource = noRipple, indication = null) {
                                            argsExpanded = !argsExpanded
                                        }
                                        .padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                    // 切换提示只在「确实存在被折叠内容」时出现：
                    // 空卡片既不显示「展开详情」，也不会因为点了一下就冒出「收起」。
                    if (hasMore) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (expanded) "收起 ▲" else "展开详情 ▼",
                            fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // 最后:调用发起时刻(精确到毫秒)
                    Text(
                        if (r.ts > 0) "调用时间  " + tsFmt.format(Date(r.ts)) else "调用时间  —(旧记录无时间戳)",
                        fontSize = 11.sp,
                        color = Color(0xFF6B7280)
                    )
                }
            }
        }
    }
}

// FoldingText / CODE_FOLD_LINES / codeCapsuleLabel / CodeCapsule 原先是本文件的私有实现，
// 现应「会话内联详情面板也要显示代码胶囊」的需求提取到 ToolCodeCapsule.kt（internal，同包直接用）。
