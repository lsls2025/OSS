package com.aurora.chat.ui.tools

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


private val DURATION_UNITS = listOf(
    DurationUnit("分钟", 60L),
    DurationUnit("小时", 3600L),
    DurationUnit("天", 86400L),
    DurationUnit("月", 2592000L),
    DurationUnit("年", 31536000L)
)



private val QUICK_PRESETS = listOf(
    QuickPreset("30分钟", 30, 0),
    QuickPreset("1小时", 1, 1),
    QuickPreset("12小时", 12, 1),
    QuickPreset("1天", 1, 2),
    QuickPreset("7天", 7, 2),
    QuickPreset("30天", 30, 2)
)

// 禁言类型：1=评论禁言, 2=对话禁言, 3=全部禁言
private data class MuteTypeOption(val value: Int, val label: String, val desc: String)
private val MUTE_TYPES = listOf(
    MuteTypeOption(1, "评论禁言", "禁止在社区发布评论"),
    MuteTypeOption(2, "对话禁言", "禁止发送聊天消息"),
    MuteTypeOption(3, "全部禁言", "禁止评论和聊天")
)

@Composable
fun MuteManagementScreen(
    user: UserInfo,
    onBack: () -> Unit,
    onMuteSuccess: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var durationValue by remember { mutableStateOf("1") }
    var selectedUnitIndex by remember { mutableIntStateOf(2) } // 默认"天"
    var selectedMuteType by remember { mutableIntStateOf(3) } // 默认全部禁言
    var isMuting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var blockIP by remember { mutableStateOf(true) }

    // 计算总秒数
    fun calcTotalSeconds(): Long = (durationValue.toLongOrNull() ?: 0L) * DURATION_UNITS[selectedUnitIndex].seconds

    // 格式化时长显示
    fun calcDurationDisplay(): String {
        val num = durationValue.toIntOrNull() ?: 0
        val unit = DURATION_UNITS[selectedUnitIndex].label
        return "$num$unit"
    }

    fun doMute() {
        val dur = durationValue.toLongOrNull()
        if (dur == null || dur <= 0) {
            errorMsg = "请输入有效的禁言时长"
            return
        }
        isMuting = true
        errorMsg = null
        scope.launch {
            try {
                val result = AuroraApi.adminMuteUser(user.id, calcTotalSeconds(), selectedMuteType)
                withContext(Dispatchers.Main) {
                    isMuting = false
                    if (result.success) {
                        android.widget.Toast.makeText(ctx, "已禁言 ${user.username} (${calcDurationDisplay()})", android.widget.Toast.LENGTH_SHORT).show()
                        onMuteSuccess()
                        onBack()
                    } else {
                        errorMsg = result.message
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isMuting = false
                    errorMsg = "禁言失败: ${e.localizedMessage}"
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .statusBarsPadding()
        ) {
            // ===== 顶部标题栏 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "←",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable { onBack() }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "禁言用户: ${user.username}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1F2937)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "ID: ${user.id}",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color(0xFFE5E7EB))
            )

            // ===== 可滚动内容 =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // ── 禁言类型选择 ──
                Text("禁言类型", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                Spacer(modifier = Modifier.height(8.dp))
                MUTE_TYPES.forEach { type ->
                    val isSelected = selectedMuteType == type.value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFFFEF3C7) else Color(0xFFF9FAFB))
                            .clickable { selectedMuteType = type.value; errorMsg = null }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Color(0xFFD97706) else Color(0xFFD1D5DB))
                                .padding(4.dp)
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = type.label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF1F2937)
                            )
                            Text(
                                text = type.desc,
                                fontSize = 12.sp,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = Color(0xFFE5E7EB), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(20.dp))

                // ── 快捷预设按钮 ──
                Text("快捷时长", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QUICK_PRESETS.take(3).forEach { preset ->
                        val isSelected2 = durationValue == preset.value.toString() && selectedUnitIndex == preset.unitIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected2) Color(0xFFD97706) else Color(0xFFF3F4F6))
                                .clickable {
                                    durationValue = preset.value.toString()
                                    selectedUnitIndex = preset.unitIndex
                                    errorMsg = null
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = preset.label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected2) FontWeight.Medium else FontWeight.Normal,
                                color = if (isSelected2) Color.White else Color(0xFF6B7280)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QUICK_PRESETS.drop(3).forEach { preset ->
                        val isSelected2 = durationValue == preset.value.toString() && selectedUnitIndex == preset.unitIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected2) Color(0xFFD97706) else Color(0xFFF3F4F6))
                                .clickable {
                                    durationValue = preset.value.toString()
                                    selectedUnitIndex = preset.unitIndex
                                    errorMsg = null
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = preset.label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected2) FontWeight.Medium else FontWeight.Normal,
                                color = if (isSelected2) Color.White else Color(0xFF6B7280)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── 自定义时长输入 ──
                Text("自定义时长", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val inputStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1F2937)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF3F4F6))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        BasicTextField(
                            value = durationValue,
                            onValueChange = { newVal ->
                                if (newVal.isEmpty() || newVal.all { it.isDigit() }) {
                                    durationValue = newVal
                                    errorMsg = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = inputStyle,
                            singleLine = true,
                            cursorBrush = SolidColor(Color(0xFFD97706)),
                            decorationBox = { inner ->
                                Box {
                                    if (durationValue.isEmpty()) Text("输入时长", fontSize = 16.sp, color = Color(0xFF9CA3AF))
                                    inner()
                                }
                            }
                        )
                    }

                    DURATION_UNITS.forEachIndexed { index, unit ->
                        val isUnitSelected = selectedUnitIndex == index
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isUnitSelected) Color(0xFFD97706) else Color(0xFFF3F4F6))
                                .clickable {
                                    selectedUnitIndex = index
                                    errorMsg = null
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = unit.label,
                                fontSize = 14.sp,
                                fontWeight = if (isUnitSelected) FontWeight.Medium else FontWeight.Normal,
                                color = if (isUnitSelected) Color.White else Color(0xFF6B7280)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = Color(0xFFE5E7EB), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(20.dp))

                // 错误提示
                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        fontSize = 13.sp,
                        color = Color(0xFFDC2626)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 禁言预览信息
                val muteTypeLabel = MUTE_TYPES.find { it.value == selectedMuteType }?.label ?: "全部禁言"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFFBEB))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "禁言预览",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFD97706)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "用户: ${user.username}" + if (user.qqNumber.isNotBlank()) " (QQ: ${user.qqNumber})" else "",
                            fontSize = 13.sp,
                            color = Color(0xFF4B5563)
                        )
                        Text(
                            text = "类型: $muteTypeLabel",
                            fontSize = 13.sp,
                            color = Color(0xFF4B5563)
                        )
                        Text(
                            text = "时长: ${calcDurationDisplay()}",
                            fontSize = 13.sp,
                            color = Color(0xFF4B5563)
                        )
                        if (blockIP) {
                            Text(
                                text = "同时拒收该用户+IP",
                                fontSize = 13.sp,
                                color = Color(0xFFDC2626)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "禁言后用户将立即无法发言，返回「你已被禁言」",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF)
                )
            }

            // ===== 底部确认按钮 =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(16.dp)
                    .navigationBarsPadding()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isMuting) Color(0xFF9CA3AF)
                            else Color(0xFFD97706)
                        )
                        .clickable(enabled = !isMuting) { doMute() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isMuting) "禁言中..." else "确认禁言",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
