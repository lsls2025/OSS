package com.aurora.chat.ui.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 属性面板里的一行：显示当前动作，点一下打开分类选择器，不再是一条横滑长排。 */
@Composable
fun ActionRow(
    label: String,
    action: ElementAction,
    isCanvas: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CustomUi.FieldBg)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = CustomUi.Sub)
        Spacer(Modifier.weight(1f))
        Text(
            if (isCanvas && action == ElementAction.NONE) "无动作" else action.label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = CustomUi.Primary
        )
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 16.sp, color = CustomUi.Hint)
    }
}

/**
 * 点击动作的分组网格选择器。
 *
 * 分组顺序由 ElementAction.groups 决定：硬件与权限类排最上，复制分享这类杂项垫底。
 * 动作多，顶部给一个搜索框直接筛，不用一路滚到底。需要参数的动作在这里直接填。
 */
@Composable
fun ActionPicker(
    current: ElementAction,
    value: String,
    isCanvas: Boolean,
    onPick: (ElementAction) -> Unit,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    // 外层编辑页已经按键盘高度整体上抬，这里只按剩余可用高度限制卡片，
    // 保证键盘弹出时「完成」和参数框不会被推到屏幕外面。
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClose() },
        contentAlignment = Alignment.BottomCenter
    ) {
        val cap = (maxHeight.value * 0.84f).dp
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(CustomUi.CardBg)
                .navigationBarsPadding()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* 吃掉点击，避免穿透关闭 */ }
                .heightIn(max = cap)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "点击动作",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CustomUi.Text,
                    modifier = Modifier.weight(1f)
                )
                SmallAction("完成", active = true) { onClose() }
            }
            Spacer(Modifier.height(10.dp))
            CustomDivider()
            Spacer(Modifier.height(10.dp))

            CustomField(value = query, onValueChange = { query = it }, hint = "搜索动作")
            Spacer(Modifier.height(10.dp))

            if (current.needsValue) {
                Text(current.hint, fontSize = 11.sp, color = CustomUi.Hint)
                Spacer(Modifier.height(6.dp))
                CustomField(
                    value = value,
                    onValueChange = onValueChange,
                    hint = current.hint
                )
                Spacer(Modifier.height(12.dp))
            }

            ElementAction.groups.forEach { group ->
                val actions = ElementAction.entries.filter {
                    it.group == group && (query.isBlank() ||
                        it.label.contains(query, ignoreCase = true) ||
                        it.key.contains(query, ignoreCase = true))
                }
                if (actions.isEmpty()) return@forEach
                Text(group, fontSize = 12.sp, color = CustomUi.Sub)
                Spacer(Modifier.height(6.dp))
                actions.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { a ->
                            ActionCell(
                                label = if (isCanvas && a == ElementAction.NONE) "无动作" else a.label,
                                selected = a == current,
                                modifier = Modifier.weight(1f)
                            ) { onPick(a) }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f).padding(end = 8.dp)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActionCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .padding(end = 8.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) CustomUi.Primary else CustomUi.FieldBg)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else CustomUi.Text,
            maxLines = 1
        )
    }
}
