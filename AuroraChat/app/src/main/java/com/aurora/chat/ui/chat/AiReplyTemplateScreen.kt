package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI 回复模板（工具栏「模板」入口）。
 *
 * 与齿轮设置页同一套全屏方案：普通 Compose 层从右侧切入，不用 Dialog，
 * 因此不会吃到 `decorFitsSystemWindows=false` 的状态栏穿透 / 顶栏配色不一致；
 * 白色背景铺满整屏，顶栏单独让出状态栏高度。
 *
 * 交互按既有约定：只有功能标题，没有说明文字、提示、placeholder、Toast 和保存按钮；
 * 开关与文本改动即时落盘。注入文本本身就是模板内容，不是提示语，所以直接铺在编辑框里。
 */
@Composable
fun AiReplyTemplateScreen(userId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var activeId by remember { mutableStateOf(AiReplyTemplateStore.activeId(ctx, userId)) }

    // 关闭状态下也允许预先编辑第一个模板；启用后编辑器作用于当前启用项。
    val editorId = activeId.ifEmpty { AiReplyTemplateStore.templates.first().id }
    var inject by remember(editorId) {
        mutableStateOf(AiReplyTemplateStore.injectText(ctx, userId, editorId))
    }
    var customized by remember(editorId) {
        mutableStateOf(AiReplyTemplateStore.isCustomized(ctx, userId, editorId))
    }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            .statusBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val noRipple = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(indication = null, interactionSource = noRipple) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    null,
                    Modifier.size(22.dp),
                    tint = com.aurora.chat.ui.theme.appTextPrimary()
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "模板",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary()
            )
        }
        HorizontalDivider(thickness = 1.dp, color = com.aurora.chat.ui.theme.appDivider())

        AiReplyTemplateStore.templates.forEach { t ->
            val on = activeId == t.id
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(t.name, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
                Switch(
                    checked = on,
                    onCheckedChange = { enabled ->
                        activeId = if (enabled) t.id else ""
                        AiReplyTemplateStore.setActive(ctx, userId, activeId)
                    }
                )
            }
            HorizontalDivider(thickness = 1.dp, color = com.aurora.chat.ui.theme.appDivider())
        }

        // 展开 / 收回都带高度补间，下面的内容跟着平滑位移，不会突然跳一下。
        AnimatedVisibility(
            visible = activeId.isNotEmpty(),
            enter = expandVertically(animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
            exit = shrinkVertically(animationSpec = tween(260)) + fadeOut(animationSpec = tween(260))
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "系统注入",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = com.aurora.chat.ui.theme.appTextPrimary(),
                        modifier = Modifier.weight(1f)
                    )
                    if (customized) {
                        val noRipple = remember { MutableInteractionSource() }
                        Text(
                            "还原",
                            fontSize = 13.sp,
                            color = Color(0xFF1E40AF),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(indication = null, interactionSource = noRipple) {
                                    AiReplyTemplateStore.resetInjectText(ctx, userId, editorId)
                                    inject = AiReplyTemplateStore.injectText(ctx, userId, editorId)
                                    customized = false
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = inject,
                    onValueChange = {
                        inject = it
                        AiReplyTemplateStore.setInjectText(ctx, userId, editorId, it)
                        customized = true
                    },
                    textStyle = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
