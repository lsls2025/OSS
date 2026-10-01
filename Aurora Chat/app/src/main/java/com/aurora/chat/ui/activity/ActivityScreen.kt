package com.aurora.chat.ui.activity

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.ui.chat.AiChatManager
import androidx.compose.foundation.horizontalScroll
import android.widget.Toast
import com.aurora.chat.R
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.server.ServerScreen
import com.aurora.chat.ui.server.SetDialogSystemBarColors
import kotlinx.coroutines.launch

@Composable
fun ActivityScreen(userId: Long, onDismiss: () -> Unit, initialPage: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentPage by remember { mutableIntStateOf(initialPage.coerceIn(0, 7)) } // 0=list, 3=bottle, 5=member, 6=balance, 7=website-hosting

    // 子页面中按返回键退回到列表页
    BackHandler(enabled = currentPage != 0) {
        currentPage = 0
    }

    Dialog(
        onDismissRequest = { if (currentPage == 0) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        // 清除 Dialog 默认灰色状态栏蒙层，改为白底 + 深色图标
        SetDialogSystemBarColors(android.graphics.Color.WHITE, android.graphics.Color.WHITE, lightStatusBars = true)
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInHorizontally(tween(350), initialOffsetX = { it }) +
                        fadeIn(tween(350)) togetherWith
                        slideOutHorizontally(tween(350), targetOffsetX = { -it }) +
                        fadeOut(tween(350))
                } else {
                    slideInHorizontally(tween(350), initialOffsetX = { -it }) +
                        fadeIn(tween(350)) togetherWith
                        slideOutHorizontally(tween(350), targetOffsetX = { it }) +
                        fadeOut(tween(350))
                }
            },
            label = "activity_anim"
        ) { target ->
            val density = LocalDensity.current
            val edgeSwipeMod = Modifier.pointerInput(target) {
                val edgePx = with(density) { 30.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x > edgePx) {
                        do { awaitPointerEvent() } while (currentEvent.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    var dragDistance = 0f
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            dragDistance += change.position.x - change.previousPosition.x
                            change.consume()
                        }
                    } while (currentEvent.changes.any { it.pressed })
                    if (dragDistance > size.width * 0.3f && currentPage != 0) {
                        currentPage = 0
                    }
                }
            }
            Box(edgeSwipeMod) {
                when (target) {
                    3 -> BottleScreen(onBack = { currentPage = 0 })
                    6 -> BalanceScreen(onBack = { currentPage = 0 })
                    7 -> ServerScreen(direct = true) { currentPage = 0 }
                }
            }
        }
    }
}





// ==================== 余额页面 ====================
// Token 充值汇率：1 元 = 30 万 Token（用于充值弹窗按 token 计算应付金额，最低 1 元起充）
private const val TOKENS_PER_YUAN = 450000L

@Composable
private fun BalanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loggedIn = AuroraApi.authToken != null

    var tokenBalance by remember { mutableLongStateOf(-1L) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    // 充值弹窗状态
    var showRechargeDialog by remember { mutableStateOf(false) }
    var rechargeTokens by remember { mutableStateOf("") }
    var rechargeOrderNo by remember { mutableStateOf("") }
    var rechargeSubmitting by remember { mutableStateOf(false) }

    fun load() {
        if (!loggedIn) { errorMsg = "请先登录后查看余额"; return }
        loading = true; errorMsg = ""
        scope.launch {
            val res = AuroraApi.tokenBalance()
            if (res.success && res.data != null) {
                val d = if (res.data!!.has("data")) res.data!!.optJSONObject("data") ?: res.data!! else res.data!!
                if (d.has("token_balance")) tokenBalance = d.getLong("token_balance")
                AiChatManager.cacheServerTokenBalance(context, AuroraApi.currentUserId, tokenBalance)
                Toast.makeText(context, "余额已刷新", Toast.LENGTH_SHORT).show()
            } else {
                errorMsg = res.message
                Toast.makeText(context, "刷新失败：${res.message}", Toast.LENGTH_SHORT).show()
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Box(Modifier.fillMaxSize().background(Color(0xFFF7F8FA))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // 顶部栏
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("←", fontSize = 24.sp, color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
                Spacer(Modifier.weight(1f))
                Text("我的余额", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.weight(1f))
                Text(if (loading) "刷新中..." else "刷新", fontSize = 14.sp, color = if (loading) Color(0xFF9CA3AF) else Color(0xFF1E40AF),
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { if (!loading) load() })
            }

            Spacer(Modifier.height(12.dp))

            if (!loggedIn) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        Text("请先登录后查看 Token 余额", fontSize = 14.sp, color = Color(0xFF6B7280))
                    }
                }
            } else {
                // 余额卡片（渐变高亮）
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                        .clickable { showRechargeDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Box(
                        Modifier.fillMaxWidth()
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(Color(0xFF1E40AF), Color(0xFF7C3AED))
                                )
                            )
                            .padding(24.dp)
                    ) {
                        Column {
                            Text("Token 余额", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
                            Spacer(Modifier.height(8.dp))
                            if (loading && tokenBalance < 0) {
                                Text("加载中…", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            } else {
                                Text(
                                    text = tokenBalance.toString(),
                                    fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color.White
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("个 Token", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("点击卡片充值", fontSize = 13.sp, color = Color.White.copy(alpha = 0.92f))
                                Spacer(Modifier.width(4.dp))
                                Text("›", fontSize = 18.sp, color = Color.White.copy(alpha = 0.92f))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (errorMsg.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(errorMsg, fontSize = 13.sp, color = Color(0xFFDC2626),
                        modifier = Modifier.padding(horizontal = 24.dp))
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "※ 余额为你账户的 Token 总数，对话消耗以官方接口统计为准。\n· 充值与卡密兑换获得的 Token 不会被清零。",
                    fontSize = 11.sp, color = Color(0xFF9CA3AF),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    // 充值弹窗：输入 token 数量与支付订单号，自动计算金额（最低 1 元起充），提交后由开发者审核
    if (showRechargeDialog) {
        AlertDialog(
            onDismissRequest = { if (!rechargeSubmitting) showRechargeDialog = false },
            containerColor = Color.White,
            title = { Text("充值 Token", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedTextField(
                        value = rechargeTokens,
                        onValueChange = { rechargeTokens = it.filter { c -> c.isDigit() }.take(12) },
                        label = { Text("充值 Token 数量") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    // 收款二维码（赞赏码）
                    Text("请扫码支付", fontSize = 13.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(8.dp))
                    Image(
                        painter = painterResource(R.drawable.qr_reward),
                        contentDescription = "收款码",
                        modifier = Modifier.size(180.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = rechargeOrderNo,
                        onValueChange = { rechargeOrderNo = it.trim() },
                        label = { Text("支付订单号") },
                        placeholder = { Text("微信支付订单号", fontSize = 13.sp, color = Color(0xFF9CA3AF)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("请填写微信支付完成后的订单号", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                    Spacer(Modifier.height(10.dp))
                    val tk = rechargeTokens.toLongOrNull() ?: 0L
                    val amt = if (tk > 0) (tk.toDouble() / TOKENS_PER_YUAN) else 0.0
                    Text("应付金额：¥%.2f".format(amt), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A))
                    if (tk > 0 && amt < 1.0) {
                        Spacer(Modifier.height(4.dp))
                        Text("最低 1 元起充（至少 ${TOKENS_PER_YUAN} Token）", fontSize = 12.sp, color = Color(0xFFDC2626))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("提交后由开发者审核，通过后 Token 自动到账。", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                }
            },
            confirmButton = {
                Button(
                    enabled = !rechargeSubmitting && (rechargeTokens.toLongOrNull() ?: 0) >= TOKENS_PER_YUAN && rechargeOrderNo.isNotBlank(),
                    onClick = {
                        val tk = rechargeTokens.toLongOrNull() ?: 0
                        val amt = tk.toDouble() / TOKENS_PER_YUAN
                        rechargeSubmitting = true
                        scope.launch {
                            val r = com.aurora.chat.data.api.AuroraApi.submitRechargeOrder(tk, amt, rechargeOrderNo.trim())
                            rechargeSubmitting = false
                            if (r.success) {
                                showRechargeDialog = false
                                rechargeTokens = ""
                                rechargeOrderNo = ""
                                android.widget.Toast.makeText(context, "订单已提交，等待审核", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, r.message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) { Text(if (rechargeSubmitting) "提交中..." else "提交订单") }
            },
            dismissButton = {
                TextButton(onClick = { if (!rechargeSubmitting) showRechargeDialog = false }) { Text("取消", color = Color(0xFF6B7280)) }
            }
        )
    }
}
