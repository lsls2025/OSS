package com.aurora.chat.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.local.DataCache
import com.aurora.chat.ui.theme.AuroraChatTheme
import com.aurora.chat.ui.theme.AuroraPrimary
import com.aurora.chat.ui.theme.BgLight
import kotlinx.coroutines.launch

/**
 * 消息推送首选项界面：用户选择"自己的邮箱推送"或"官方邮箱推送"。
 * 选择状态/提示模式/自己邮箱配置均按账号本地持久化，离开重进不丢失。
 */
@Composable
fun MessagePushScreen(
    userId: Long,
    onBack: () -> Unit,
    // 由外层触发"同步推送配置到后端"；默认空实现，外层未接入时不影响编译与本地同步
    syncPushConfigToServer: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 默认：官方邮箱推送 + 默认模板；已有配置则恢复
    var selected by remember { mutableStateOf(DataCache.loadPushMethod(ctx, userId)) }
    var templateMode by remember { mutableStateOf(DataCache.loadPushTemplateMode(ctx, userId)) }
    var ownEmail by remember { mutableStateOf(DataCache.loadOwnEmail(ctx, userId)) }
    var ownAuth by remember { mutableStateOf(DataCache.loadOwnAuth(ctx, userId)) }
    var notifyEnabled by remember { mutableStateOf(DataCache.loadNotifyEnabled(ctx, userId) == 1) }

    // 将当前推送配置同步到后端（官方/自己的邮箱、模板/真实、免打扰列表）
    // 注意：Kotlin 局部函数必须先声明后使用，因此放在下面的 LaunchedEffect 之前
    fun syncPushConfigToServer() {
        val dndIds = DataCache.loadPushDndIds(ctx, userId)
        scope.launch {
            try {
                AuroraApi.savePushConfig(selected, templateMode, ownEmail, ownAuth, dndIds, if (notifyEnabled) 1 else 0)
            } catch (_: Exception) {
                // 网络异常时仅本地保留，下次进入再同步
            }
        }
    }

    // 推送方式/提示模式/总通知开关变化时即时持久化（本地 + 同步后端）
    LaunchedEffect(selected, templateMode, notifyEnabled) {
        DataCache.savePushConfig(ctx, userId, selected, templateMode, ownEmail, ownAuth, emptyList(), if (notifyEnabled) 1 else 0)
        syncPushConfigToServer()
    }

    AuroraChatTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F6F8))
                .statusBarsPadding()
        ) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color(0xFF1F2937)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "消息推送",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1F2937)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color(0xFFE5E7EB))
            )

            // 两个选项（内容可能超出屏幕，需可滚动）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ===== 总通知开关：关闭后所有邮箱推送都不发 =====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "接收消息通知",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1F2937)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "关闭后，离线时不再通过邮箱通知新消息（免打扰）",
                            fontSize = 13.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.Switch(
                        checked = notifyEnabled,
                        onCheckedChange = { notifyEnabled = it },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedTrackColor = Color(0xFF1E40AF),
                            checkedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFD1D5DB),
                            uncheckedThumbColor = Color(0xFF9CA3AF)
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected == 1) BgLight else Color.White, RoundedCornerShape(12.dp))
                        .then(if (selected == 1) Modifier.border(1.dp, AuroraPrimary, RoundedCornerShape(12.dp)) else Modifier)
                        .clickable { selected = 1 }
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "使用自己的邮箱推送",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1F2937)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "最稳定，需手动配置 SMTP 服务",
                            fontSize = 13.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                }

                // 选择“自己的邮箱”后：上方 SMTP 配置块 + 下方推送提示模板块，把“官方邮箱”往下推
                AnimatedVisibility(
                    visible = selected == 1,
                    enter = expandVertically(tween(320)) + fadeIn(tween(320)),
                    exit = shrinkVertically(tween(220)) + fadeOut(tween(220))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OwnEmailConfigBlock(
                            email = ownEmail,
                            auth = ownAuth,
                            onEmailChange = { ownEmail = it },
                            onAuthChange = { ownAuth = it },
                            onBind = {
                                DataCache.savePushConfig(ctx, userId, selected, templateMode, ownEmail, ownAuth)
                                syncPushConfigToServer()
                                android.widget.Toast.makeText(ctx, "已绑定，将使用你的邮箱推送", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                        PushTemplatePanel(
                            title = "使用自己的邮箱推送",
                            templateMode = templateMode,
                            onSelect = { templateMode = it }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected == 2) BgLight else Color.White, RoundedCornerShape(12.dp))
                        .then(if (selected == 2) Modifier.border(1.dp, AuroraPrimary, RoundedCornerShape(12.dp)) else Modifier)
                        .clickable { selected = 2 }
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "使用官方邮箱推送",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1F2937)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "最省事，高峰期可能因频率被邮箱官方限流",
                            fontSize = 13.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                }

                // 选择“官方邮箱”后：只显示推送提示模板块
                AnimatedVisibility(
                    visible = selected == 2,
                    enter = expandVertically(tween(320)) + fadeIn(tween(320)),
                    exit = shrinkVertically(tween(220)) + fadeOut(tween(220))
                ) {
                    PushTemplatePanel(
                        title = "使用官方邮箱推送",
                        templateMode = templateMode,
                        onSelect = { templateMode = it }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "请选择一种推送方式，后续可随时在设置中切换",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF)
                )
            }
        }
    }
}

/**
 * 自己的邮箱配置块：填写邮箱地址 + SMTP 授权码，点“绑定”后使用自己的邮箱推送。
 * 有授权码即代表邮箱归属本人，无需额外验证。
 */
@Composable
private fun OwnEmailConfigBlock(
    email: String,
    auth: String,
    onEmailChange: (String) -> Unit,
    onAuthChange: (String) -> Unit,
    onBind: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "SMTP 配置",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1F2937)
        )
        Text(
            text = "填写你自己的邮箱与授权码，绑定后消息将通过你的邮箱推送",
            fontSize = 12.sp,
            color = Color(0xFF6B7280)
        )
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("邮箱地址") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp)
        )
        OutlinedTextField(
            value = auth,
            onValueChange = onAuthChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("SMTP 授权码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(10.dp)
        )
        Button(
            onClick = onBind,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AuroraPrimary)
        ) {
            Text(
                text = "绑定",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

/**
 * 推送提示模板面板：让用户选择提示内容：提示模板（“有一条新消息”）或真实消息（以真实内容推送）。
 */
@Composable
private fun PushTemplatePanel(
    title: String,
    templateMode: Int,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1F2937)
        )
        Text(
            text = "推送提示内容",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF374151)
        )

        PushTemplateOption(
            checked = templateMode == 1,
            title = "提示模板",
            subtitle = "有消息时提示：“有一条新消息”",
            onClick = { onSelect(1) }
        )
        PushTemplateOption(
            checked = templateMode == 2,
            title = "真实消息",
            subtitle = "直接以消息真实内容（如邮箱正文）推送",
            onClick = { onSelect(2) }
        )
    }
}

/** 模板面板内的单选项（左侧单选圆点 + 标题 + 说明） */
@Composable
private fun PushTemplateOption(
    checked: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) BgLight else Color.Transparent, RoundedCornerShape(8.dp))
            .then(if (checked) Modifier.border(1.dp, AuroraPrimary, RoundedCornerShape(8.dp)) else Modifier)
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 单选圆点
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (checked) AuroraPrimary else Color.White)
                    .border(1.dp, if (checked) AuroraPrimary else Color(0xFF9CA3AF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (checked) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1F2937)
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF6B7280)
                )
            }
        }
    }
}
