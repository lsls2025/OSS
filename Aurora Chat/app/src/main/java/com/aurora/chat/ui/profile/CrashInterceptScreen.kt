package com.aurora.chat.ui.profile

import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.CrashHandler
import com.aurora.chat.CrashInterceptManager
import com.aurora.chat.ErrorReporter
import com.aurora.chat.LogLevel

/**
 * 闪退拦截统计 —— 显示成功拦截次数（CrashHandler捕获到的）和未拦截次数。
 */
@Composable
fun CrashInterceptScreen(
    onBack: () -> Unit,
    onOpenErrorLog: () -> Unit
) {
    val context = LocalContext.current
    // 成功拦截：CrashHandler 持久化的计数（每次捕获到闪退 +1）— 读盘移 IO
    val successCount by produceState(0, context) {
        withContext(Dispatchers.IO) { value = CrashHandler.getCrashCount(context) }
    }
    // 未成功拦截：FATAL 日志文件数减去成功拦截数（差值为未被handler捕获的）— 列目录移 IO
    val fatalFiles by produceState(0, context) {
        withContext(Dispatchers.IO) { value = ErrorReporter.getFilesByLevel(LogLevel.FATAL).size }
    }
    val failCount = remember(successCount, fatalFiles) { (fatalFiles - successCount).coerceAtLeast(0) }
    // 最近一次闪退是否拦截成功（读盘移 IO）
    val lastIntercepted by produceState<Boolean?>(null, context) {
        withContext(Dispatchers.IO) { value = CrashHandler.getLastIntercepted(context) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White).padding(20.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "闪退拦截",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937)
                )
            }
            Spacer(Modifier.height(16.dp))

            // 最近一次拦截结果
            CardContent(
                "最近一次",
                if (lastIntercepted == null) "暂无记录"
                else if (lastIntercepted == true) "拦截成功"
                else "拦截失败",
                if (lastIntercepted == true) Color(0xFF16A34A)
                else if (lastIntercepted == false) Color(0xFFDC2626)
                else Color(0xFF9CA3AF),
                if (lastIntercepted == true) Color(0xFFF0FFF4)
                else if (lastIntercepted == false) Color(0xFFFEF2F2)
                else Color(0xFFF3F4F6)
            )

            Spacer(Modifier.height(12.dp))

            // 成功拦截次数
            CardContent("成功拦截", "$successCount 次",
                if (successCount > 0) Color(0xFF16A34A) else Color(0xFF9CA3AF),
                Color(0xFFF0FFF4))

            Spacer(Modifier.height(12.dp))

            // 失败拦截次数
            CardContent("失败拦截", "$failCount 次",
                if (failCount > 0) Color(0xFFDC2626) else Color(0xFF16A34A),
                Color(0xFFFEF2F2))

            Spacer(Modifier.height(12.dp))

            // 总闪退次数
            CardContent("闪退次数", "${successCount + failCount} 次",
                Color(0xFF1E40AF), Color(0xFFEFF6FF))

            Spacer(Modifier.height(8.dp))

            // 重置成功计数按钮
            if (successCount > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFEF2F2))
                        .clickable {
                            CrashHandler.resetCrashCount(context)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("重置成功拦截计数", fontSize = 13.sp, color = Color(0xFFDC2626),
                        fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(12.dp))

            // 查看错误日志按钮
            Button(
                onClick = onOpenErrorLog,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4B5563))
            ) {
                Text("查看错误日志", color = Color.White, fontSize = 15.sp)
            }

            Spacer(Modifier.height(12.dp))

            // 上传按钮
            Button(
                onClick = { CrashInterceptManager.shareErrorLogs(context) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
            ) {
                Text("上传错误日志给开发者", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun CardContent(title: String, value: String, accent: Color, bg: Color) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(bg).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Color(0xFF6B7280))
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}
