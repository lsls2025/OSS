package com.aurora.chat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

// 品牌色:与全局 AuroraPrimary(0xFF1E40AF)一致的深蓝,不允许偏浅
private val AskBlue = Color(0xFF1E40AF)
private val AskBlueSoft = Color(0xFFE8EFFD)
private val AskTextDark = Color(0xFF1F2937)
private val AskTextGray = Color(0xFF6B7280)
private val AskBorder = Color(0xFFE5E7EB)

/**
 * AI 向用户提问(ask_user 工具)的抽屉式问答面板宿主。
 * 收集 AskUserBus.pending,有请求时从**屏幕最底部**滑上抽屉(盖在输入栏之上,不挤压任何布局);
 * 一题一页大卡片,支持选项点选(单选/多选)、「其他」自由填写、上一步/下一步/跳过/完成。
 * 「跳过/下一步/完成」= 正常作答提交;「叉/点遮罩/下拉/返回键」= 收起面板但不提交,
 * 提问保持等待,点消息流里的「正在向用户提问」状态行可带着作答草稿回来接着答。
 * 作答完成后抽屉退下,问答记录以「回答内容」折叠块(默认收起)留在消息流里。
 *
 * 注意:宿主必须挂在聊天根 Box 的直属层——放进 Column 会占走布局空间把工具栏顶起来。
 */
@Composable
fun AskUserHost() {
    val req by AiChatManager.AskUserBus.pending.collectAsState()
    val hidden by AiChatManager.AskUserBus.hidden.collectAsState()
    if (req != null && !hidden) AskUserPanel(req!!)
}

@Composable
private fun AskUserPanel(req: AiChatManager.AskUserBus.Request) {
    val questions = req.questions
    val noRipple = remember { MutableInteractionSource() }

    // 每题的作答状态:挂在请求实例的草稿上(面板收起再展开时逐字保留,接着上次的进度继续答)
    val draft = req.draft
    val selected = draft.selected
    val otherChecked = draft.otherChecked
    val otherText = draft.otherText
    val freeText = draft.freeText
    var page by draft.page
    val lastPage = questions.size - 1

    // 组装答案 JSON 并提交:所有题都会包含(未作答的 skipped=true),AI 能完整知道每一题的情况
    fun submit() {
        val answers = JSONArray()
        var answered = 0
        questions.forEachIndexed { i, q ->
            val picks = selected[i].value.mapNotNull { q.options.getOrNull(it) }.toMutableList()
            var otherVal = ""
            if (q.other && otherChecked[i].value) otherVal = otherText[i].value.trim()
            val free = freeText[i].value.trim()
            val skipped = when {
                q.options.isEmpty() -> free.isEmpty() && otherVal.isEmpty()
                else -> picks.isEmpty() && otherVal.isEmpty()
            }
            if (!skipped) answered++
            if (q.other && otherVal.isNotEmpty()) picks.add("其他:$otherVal")
            val readable = when {
                skipped -> "(跳过)"
                picks.isEmpty() -> free
                free.isEmpty() -> picks.joinToString("、")
                else -> picks.joinToString("、") + ";" + free
            }
            answers.put(JSONObject()
                .put("index", i)
                .put("question", q.question)
                .put("multi", q.multi)
                .put("skipped", skipped)
                .put("selected", JSONArray().apply { picks.forEach { put(it) } })
                .put("other", otherVal)
                .put("free_text", free)
                .put("answer", readable)
            )
        }
        val result = JSONObject()
            .put("ok", true)
            .put("total", questions.size)
            .put("answered", answered)
            .put("answers", answers)
        req.deferred.complete(result.toString())
        AiChatManager.AskUserBus.clear(req)
    }

    // 返回键 = 收起面板(不提交、不清空作答草稿),与「叉/点遮罩/下拉」语义一致:
    // 提问保持等待,用户随时点消息流里的「正在向用户提问」状态行回来接着答。
    BackHandler { AiChatManager.AskUserBus.hide() }

    // 滑入动画:抽屉从屏幕最底边往上滑
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 题目内容区最大高度:屏高的 58%(超出内部滚动),抽屉整体高度由内容决定,不顶满全屏
        val contentMaxHeight = maxHeight * 0.58f

        // 半透明遮罩:拦截对下层聊天/输入栏的点击;点遮罩 = 收起面板(不提交,可随时恢复)
        Box(
            Modifier.fillMaxSize().background(Color(0x400F172A))
                .clickable(interactionSource = noRipple, indication = null) {
                    AiChatManager.AskUserBus.hide()
                }
        )
        AnimatedVisibility(
            visibleState = appear,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // 抽屉:贴屏幕最底边(白色一路铺到底,含手势条区域),顶部圆角,宽度撑满
            Column(
                Modifier.fillMaxWidth().imePadding()
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(Color.White)
                    .clickable(interactionSource = noRipple, indication = null) {}
            ) {
                // ── 头部(拖拽指示条 + 顶栏):按住往下拉超过阈值 = 收起面板(不提交) ──
                Column(
                    Modifier.fillMaxWidth().pointerInput(Unit) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onVerticalDrag = { _, dy -> totalDrag += dy },
                            onDragEnd = { if (totalDrag > 140f) AiChatManager.AskUserBus.hide() }
                        )
                    }
                ) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFE5E7EB)))
                    }
                    // ── 顶栏:收起(=不提交,可恢复) + 标题 + 进度 ──
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFF3F4F6))
                                .clickable(interactionSource = noRipple, indication = null) {
                                    AiChatManager.AskUserBus.hide()
                                },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Close, "收起(不提交,可随时恢复)", tint = AskTextGray, modifier = Modifier.size(20.dp)) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("AI 向你提问", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AskTextDark)
                            Text("逐题作答,可跳过不想要的题", fontSize = 11.sp, color = AskTextGray)
                        }
                        Text(
                            "${page + 1}/${questions.size}",
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AskBlue
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(AskBorder))

                // ── 题目卡片(一题一页;内容超过屏高 58% 才内部滚动,平时抽屉贴合内容高度) ──
                Column(
                    Modifier.fillMaxWidth().heightIn(max = contentMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    val q = questions[page]
                    Text(
                        q.question, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        color = AskTextDark, lineHeight = 28.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (q.multi) "多选题(可勾选多个)" else if (q.options.isEmpty()) "开放题(请输入你的答案)" else "单选题",
                        fontSize = 12.sp, color = AskTextGray
                    )
                    Spacer(Modifier.height(16.dp))

                    if (q.options.isEmpty()) {
                        // ── 无选项:大号自由填写框 ──
                        OutlinedFreeInput(
                            value = freeText[page].value,
                            onValueChange = { freeText[page].value = it }
                        )
                    } else {
                        q.options.forEachIndexed { oi, opt ->
                            val isSel = page < selected.size && oi in selected[page].value
                            OptionRow(
                                label = opt,
                                multi = q.multi,
                                selected = isSel,
                                onClick = {
                                    val cur = selected[page].value
                                    selected[page].value =
                                        if (q.multi) if (oi in cur) cur - oi else cur + oi
                                        else if (oi in cur) emptySet() else setOf(oi)
                                }
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    // ── 「其他」自由填写项(与上方内容保持明确间距,开放题的自由填写框不与它粘连) ──
                    if (q.other) {
                        if (q.options.isEmpty()) Spacer(Modifier.height(14.dp))
                        val checked = otherChecked[page].value
                        OptionRow(
                            label = "其他",
                            multi = true,
                            selected = checked,
                            onClick = { otherChecked[page].value = !checked }
                        )
                        AnimatedVisibility(visible = checked) {
                            Column {
                                Spacer(Modifier.height(10.dp))
                                OutlinedFreeInput(
                                    value = otherText[page].value,
                                    onValueChange = { otherText[page].value = it },
                                    placeholder = "请输入你的答案…"
                                )
                            }
                        }
                    }
                }

                // ── 底部操作栏:上一步 / 跳过 / 下一步|完成 ──
                // 白底一路铺到屏幕最底边,内容用 navigationBarsPadding 避开手势条
                Row(
                    Modifier.fillMaxWidth().background(Color.White)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 上一步
                    Box(
                        Modifier.weight(1f).height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (page > 0) Color(0xFFF3F4F6) else Color(0xFFF9FAFB))
                            .border(1.dp, if (page > 0) AskBorder else Color(0xFFF3F4F6), RoundedCornerShape(14.dp))
                            .clickable(
                                enabled = page > 0,
                                interactionSource = noRipple, indication = null
                            ) { page-- },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("上一步", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = if (page > 0) AskTextDark else Color(0xFFD1D5DB))
                    }
                    Spacer(Modifier.width(10.dp))
                    // 跳过(清空当前题作答,前进;最后一题时等同提交)
                    Box(
                        Modifier.weight(1f).height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(interactionSource = noRipple, indication = null) {
                                selected[page].value = emptySet()
                                otherChecked[page].value = false
                                otherText[page].value = ""
                                freeText[page].value = ""
                                if (page < lastPage) page++ else submit()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("跳过", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AskTextGray)
                    }
                    Spacer(Modifier.width(10.dp))
                    // 下一步 / 完成
                    Box(
                        Modifier.weight(1f).height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AskBlue)
                            .clickable(interactionSource = noRipple, indication = null) {
                                if (page < lastPage) page++ else submit()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (page < lastPage) "下一步" else "完成",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/** 选项行:单选=圆点,多选=勾选方块;选中态蓝底蓝框 */
@Composable
private fun OptionRow(label: String, multi: Boolean, selected: Boolean, onClick: () -> Unit) {
    val noRipple = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) AskBlueSoft else Color.White)
            .border(
                1.5.dp, if (selected) AskBlue else AskBorder,
                RoundedCornerShape(14.dp)
            )
            .clickable(interactionSource = noRipple, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (multi) {
            // 复选方块
            Box(
                Modifier.size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) AskBlue else Color.White)
                    .border(1.5.dp, if (selected) AskBlue else Color(0xFFD1D5DB), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        } else {
            // 单选圆点
            Box(
                Modifier.size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.5.dp, if (selected) AskBlue else Color(0xFFD1D5DB), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(AskBlue))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, color = if (selected) AskBlue else AskTextDark, lineHeight = 21.sp)
    }
}

/** 大号自由填写框(开放题 / 「其他」输入) */
@Composable
private fun OutlinedFreeInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "请输入…"
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.5.dp, AskBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .heightIn(min = 88.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = AskTextDark, lineHeight = 22.sp),
        cursorBrush = SolidColor(AskBlue),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 15.sp, color = Color(0xFF9CA3AF), textAlign = TextAlign.Start)
            }
            inner()
        }
    )
}
