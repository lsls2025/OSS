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

internal data class DurationUnit(val label: String, val seconds: Long)
private val DURATION_UNITS = listOf(
    DurationUnit("分钟", 60L),
    DurationUnit("小时", 3600L),
    DurationUnit("天", 86400L),
    DurationUnit("月", 2592000L),
    DurationUnit("年", 31536000L)
)

internal data class QuickPreset(val label: String, val value: Int, val unitIndex: Int)

private val QUICK_PRESETS = listOf(
    QuickPreset("30分钟", 30, 0),
    QuickPreset("12小时", 12, 1),
    QuickPreset("1个月", 1, 3),
    QuickPreset("1年", 1, 4),
    QuickPreset("10年", 10, 4)
)

@Composable
fun BanManagementScreen(
    user: UserInfo,
    onBack: () -> Unit,
    onBanSuccess: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var durationValue by remember { mutableStateOf("1") }
    var selectedUnitIndex by remember { mutableIntStateOf(3) } // 默认"月"
    var reason by remember { mutableStateOf("") }
    var unbanPopupMessage by remember { mutableStateOf("") }
    var isBanning by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 计算总秒数
    fun calcTotalSeconds(): Long = (durationValue.toLongOrNull() ?: 0L) * DURATION_UNITS[selectedUnitIndex].seconds

    // 格式化时长显示
    fun calcDurationDisplay(): String {
        val num = durationValue.toIntOrNull() ?: 0
        val unit = DURATION_UNITS[selectedUnitIndex].label
        return "$num$unit"
    }

    fun doBan() {
        if (reason.isBlank()) {
            errorMsg = "请填写封禁原因"
            return
        }
        val dur = durationValue.toLongOrNull()
        if (dur == null || dur <= 0) {
            errorMsg = "请输入有效的封禁时长"
            return
        }
        isBanning = true
        errorMsg = null
        scope.launch {
            try {
                val result = AuroraApi.adminBanUser(user.id, calcTotalSeconds(), reason.trim(), unbanPopupMessage.trim())
                withContext(Dispatchers.Main) {
                    isBanning = false
                    if (result.success) {
                        android.widget.Toast.makeText(ctx, "已封禁用户 ${user.username} (${calcDurationDisplay()})", android.widget.Toast.LENGTH_SHORT).show()
                        onBanSuccess()
                        onBack()
                    } else {
                        errorMsg = result.message
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isBanning = false
                    errorMsg = "封禁失败: ${e.localizedMessage}"
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
                    text = "封禁用户: ${user.username}",
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
                // ── 快捷预设按钮 ──
                Text("快捷时长", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QUICK_PRESETS.forEach { preset ->
                        val isSelected = durationValue == preset.value.toString() && selectedUnitIndex == preset.unitIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF1E40AF) else Color(0xFFF3F4F6))
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
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                color = if (isSelected) Color.White else Color(0xFF6B7280)
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
                    // 数值输入框
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
                                // 只允许数字
                                if (newVal.isEmpty() || newVal.all { it.isDigit() }) {
                                    durationValue = newVal
                                    errorMsg = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = inputStyle,
                            singleLine = true,
                            cursorBrush = SolidColor(Color(0xFF1E40AF)),
                            decorationBox = { inner ->
                                Box {
                                    if (durationValue.isEmpty()) Text("输入时长", fontSize = 16.sp, color = Color(0xFF9CA3AF))
                                    inner()
                                }
                            }
                        )
                    }

                    // 单位选择按钮
                    DURATION_UNITS.forEachIndexed { index, unit ->
                        val isUnitSelected = selectedUnitIndex == index
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isUnitSelected) Color(0xFF1E40AF) else Color(0xFFF3F4F6))
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

                Spacer(modifier = Modifier.height(24.dp))

                // ── 封禁原因输入框（必填） ──
                Text(
                    text = "封禁原因 *",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF6B7280)
                )
                Spacer(modifier = Modifier.height(8.dp))
                val reasonStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 15.sp,
                    color = Color(0xFF1F2937)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF3F4F6))
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = reason,
                        onValueChange = { reason = it; errorMsg = null },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = reasonStyle,
                        cursorBrush = SolidColor(Color(0xFF1E40AF)),
                        decorationBox = { inner ->
                            Box {
                                if (reason.isEmpty()) Text(
                                    "请输入封禁原因...",
                                    fontSize = 15.sp,
                                    color = Color(0xFF9CA3AF)
                                )
                                inner()
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── 解封后弹窗提示输入框（选填） ──
                Text(
                    text = "解封后弹窗提示（选填，为空则不弹窗）",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF6B7280)
                )
                Spacer(modifier = Modifier.height(8.dp))
                val popupStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 15.sp,
                    color = Color(0xFF1F2937)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF3F4F6))
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = unbanPopupMessage,
                        onValueChange = { unbanPopupMessage = it },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = popupStyle,
                        cursorBrush = SolidColor(Color(0xFF1E40AF)),
                        decorationBox = { inner ->
                            Box {
                                if (unbanPopupMessage.isEmpty()) Text(
                                    "解封后显示的弹窗提示文字（选填）...",
                                    fontSize = 15.sp,
                                    color = Color(0xFF9CA3AF)
                                )
                                inner()
                            }
                        }
                    )
                }

                // 错误提示
                if (errorMsg != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMsg!!,
                        fontSize = 13.sp,
                        color = Color(0xFFDC2626)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 封禁预览信息
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFEF2F2))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "封禁预览",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFDC2626)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "用户: ${user.username}" + if (user.qqNumber.isNotBlank()) " (QQ: ${user.qqNumber})" else "",
                            fontSize = 13.sp,
                            color = Color(0xFF4B5563)
                        )
                        Text(
                            text = "时长: ${calcDurationDisplay()}",
                            fontSize = 13.sp,
                            color = Color(0xFF4B5563)
                        )
                        Text(
                            text = "原因: ${reason.ifBlank { "(未填写)" }}",
                            fontSize = 13.sp,
                            color = Color(0xFF4B5563)
                        )
                        if (unbanPopupMessage.isNotBlank()) {
                            Text(
                                text = "解封弹窗: $unbanPopupMessage",
                                fontSize = 13.sp,
                                color = Color(0xFF4B5563)
                            )
                        }
                    }
                }
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
                            if (isBanning || reason.isBlank()) Color(0xFF9CA3AF)
                            else Color(0xFFDC2626)
                        )
                        .clickable(enabled = !isBanning && reason.isNotBlank()) { doBan() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isBanning) "封禁中..." else "确认封禁",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
