package com.aurora.chat.ui.activity

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private data class DriftingBottle(
    val id: Long,
    val content: String,
    val rotation: Float,
    val speed: Float,
    val waveOffset: Float,
    val targetY: Float,
    val layer: Int = 0, // 0=后层 1=中层 2=前层
    val startX: Float = -1f,
    val staggerIdx: Int = 0, // 错开索引，延后进入屏幕
    val skipDelay: Boolean = false // true=队列补入，跳过起始延迟
)

private data class CloudData(
    var cx: Float,
    val cy: Float,
    val scale: Float,
    val alpha: Float,
    val speedMul: Float
)


@Composable
fun BottleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var bottleContent by remember { mutableStateOf("") }
    var bottleCount by remember { mutableStateOf(0) }
    var isThrowing by remember { mutableStateOf(false) }
    var isPicking by remember { mutableStateOf(false) }
    var pickedBottle by remember { mutableStateOf<JSONObject?>(null) }
    var showPickDialog by remember { mutableStateOf(false) }
    var showLimitDialog by remember { mutableStateOf(false) }
    var limitDialogMessage by remember { mutableStateOf("") }
    var justThrewBottle by remember { mutableStateOf(false) }
    val bottleList = remember { mutableStateListOf<DriftingBottle>() }
    val bottleQueue = remember { mutableStateListOf<DriftingBottle>() }

    // 实时查询漂流瓶总数 + 刷新海面瓶子列表
    fun refreshAll() {
        scope.launch {
            val countR = AuroraApi.getBottleCount()
            if (countR.success && countR.data != null) {
                bottleCount = countR.data.optInt("count", 0)
            }
            val listR = AuroraApi.getBottleList()
            if (listR.success && listR.data != null) {
                val newIds = (0 until listR.data!!.length()).map { listR.data!!.getJSONObject(it).optLong("id") }.toSet()
                // 移除已被捞走的
                bottleList.removeAll { it.id !in newIds }
                bottleQueue.removeAll { it.id !in newIds }
                val existingIds = (bottleList + bottleQueue).map { it.id }.toSet()
                // 构造新瓶子
                val newBottles = mutableListOf<DriftingBottle>()
                for (i in 0 until listR.data!!.length()) {
                    val obj = listR.data!!.getJSONObject(i)
                    val id = obj.optLong("id")
                    if (id !in existingIds) {
                        val l = (bottleList.size + bottleQueue.size + newBottles.size) % 3
                        newBottles.add(DriftingBottle(
                            id = id,
                            content = obj.optString("content", ""),
                            rotation = Random.nextFloat() * 30f - 15f,
                            speed = 22f + Random.nextFloat() * 20f,
                            waveOffset = Random.nextFloat() * 200f,
                            targetY = when (l) {
                                0 -> 0.38f + Random.nextFloat() * 0.10f
                                1 -> 0.50f + Random.nextFloat() * 0.10f
                                else -> 0.62f + Random.nextFloat() * 0.12f
                            },
                            layer = l,
                            staggerIdx = newBottles.size
                        ))
                    }
                }
                // 刚扔出的新瓶子无视 5 限制直接上屏，其余排队
                val maxOnScreen = if (justThrewBottle) 6 else 5
                justThrewBottle = false
                val slots = (maxOnScreen - bottleList.size).coerceAtLeast(0)
                bottleList.addAll(newBottles.take(slots))
                bottleQueue.addAll(newBottles.drop(slots))
            }
        }
    }

    LaunchedEffect(Unit) { refreshAll() }

    // 扔瓶子
    fun throwBottle() {
        if (bottleContent.isBlank()) {
            Toast.makeText(context, "请输入漂流瓶内容", Toast.LENGTH_SHORT).show()
            return
        }
        isThrowing = true
        scope.launch {
            val r = AuroraApi.throwBottle(bottleContent.trim())
            isThrowing = false
            if (r.success) {
                bottleContent = ""
                focusManager.clearFocus()
                Toast.makeText(context, "漂流瓶已扔入大海", Toast.LENGTH_SHORT).show()
                justThrewBottle = true
                refreshAll()
            } else if (r.message == "没瓶子了") {
                limitDialogMessage = "没瓶子了"
                showLimitDialog = true
            } else {
                Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 捞瓶子
    fun pickBottle() {
        isPicking = true
        scope.launch {
            val r = AuroraApi.pickBottle()
            isPicking = false
            if (r.success && r.data != null) {
                val bottle = r.data.optJSONObject("bottle")
                if (bottle != null && bottle.length() > 0) {
                    pickedBottle = bottle
                    showPickDialog = true
                } else if (r.message == "没有捞到") {
                    Toast.makeText(context, "没有捞到", Toast.LENGTH_SHORT).show()
                }
            } else if (r.message == "没体力了") {
                limitDialogMessage = "没体力了"
                showLimitDialog = true
            } else if (!r.success) {
                Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
            }
            refreshAll()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部导航
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF3F4F6))
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                }
                Spacer(Modifier.weight(1f))
                Text("漂流瓶", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(36.dp))
            }

            Spacer(Modifier.height(8.dp))

            // 大海动画和漂流瓶总数 — 基于帧时间的连续波浪，永不跳帧
            val wavePhase = remember { mutableFloatStateOf(0f) }
            val wavePhase2 = remember { mutableFloatStateOf(0f) }
            val density = LocalDensity.current
            val boxHeightPx = with(density) { 180.dp.toPx() }
            // 云朵 — 错开初始位置，飘走即再生（mutableStateListOf 驱动 Canvas 重绘）
            val clouds = remember { mutableStateListOf<CloudData>() }
            LaunchedEffect(Unit) {
                // 初始：等间距分布在 0~1.2 区间
                for (i in 0 until 3) {
                    clouds.add(CloudData(i * 0.40f + 0.1f, 0.12f + Random.nextFloat() * 0.18f, 1.6f + Random.nextFloat() * 1.0f, 0.35f + Random.nextFloat() * 0.25f, 0.6f + Random.nextFloat() * 1.0f))
                }
                // 等入场动画结束再启动连续动画，避免双重动画叠加导致卡顿
                kotlinx.coroutines.delay(350)
                var lastFrameMs = System.nanoTime() / 1_000_000
                while (true) {
                    withFrameNanos { nanos ->
                        val nowMs = nanos / 1_000_000
                        val deltaMs = (nowMs - lastFrameMs).coerceAtMost(100)
                        lastFrameMs = nowMs
                        wavePhase.value += deltaMs * 0.0015f
                        wavePhase2.value += deltaMs * 0.0011f
                    }
                    // 云朵位置更新（反向遍历避免移除时索引偏移）
                    var i = clouds.lastIndex
                    while (i >= 0) {
                        val c = clouds[i]
                        val newCx = c.cx - 0.0008f * c.speedMul
                        if (newCx < -0.35f) {
                            clouds.removeAt(i)
                            clouds.add(CloudData(1.2f, 0.10f + Random.nextFloat() * 0.20f, 1.4f + Random.nextFloat() * 1.0f, 0.35f + Random.nextFloat() * 0.25f, 0.6f + Random.nextFloat() * 1.0f))
                        } else {
                            if (i < clouds.size) clouds[i] = c.copy(cx = newCx)
                        }
                        i--
                    }
                }
            }

            var areaWidth by remember { mutableIntStateOf(0) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(180.dp)
                    .graphicsLayer(clip = true)
                    .onSizeChanged { areaWidth = it.width }
            ) {
                val phase = wavePhase.value
                val phase2 = wavePhase2.value
                val bottleTint = Color(0xFF4A7DB5)
                val bottleSizePx = with(density) { 48.dp.toPx() }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val skyColor = Color(0xFFD6E8F7)
                    val waveColor1 = Color(0xFF8DBFEA)
                    val waveColor2 = Color(0xFF5B9BD5)
                    val dx = 4f
                    drawRect(skyColor, Offset.Zero, size)
                    // 云朵 — 从状态数据渲染
                    clouds.forEach { cld ->
                        val cx = cld.cx * w
                        val cy = cld.cy * h
                        val c = Color.White.copy(alpha = cld.alpha)
                        val s = cld.scale
                        val path = Path().apply {
                            moveTo(cx - 38f * s, cy + 4f * s)
                            cubicTo(cx - 38f * s, cy - 8f * s, cx - 28f * s, cy - 18f * s, cx - 16f * s, cy - 14f * s)
                            cubicTo(cx - 14f * s, cy - 28f * s, cx + 4f * s, cy - 34f * s, cx + 16f * s, cy - 24f * s)
                            cubicTo(cx + 22f * s, cy - 36f * s, cx + 42f * s, cy - 30f * s, cx + 40f * s, cy - 14f * s)
                            cubicTo(cx + 52f * s, cy - 10f * s, cx + 48f * s, cy + 6f * s, cx + 36f * s, cy + 8f * s)
                            cubicTo(cx + 40f * s, cy + 16f * s, cx + 28f * s, cy + 18f * s, cx + 18f * s, cy + 14f * s)
                            cubicTo(cx + 8f * s, cy + 20f * s, cx - 8f * s, cy + 20f * s, cx - 18f * s, cy + 14f * s)
                            cubicTo(cx - 28f * s, cy + 18f * s, cx - 38f * s, cy + 14f * s, cx - 38f * s, cy + 4f * s)
                            close()
                        }
                        drawPath(path, c)
                    }
                    fun waveY(baseY: Float, x: Float, phase: Float, freq: Float, amp: Float): Float {
                        return baseY + sin(x / freq + phase) * amp + sin(x / (freq * 0.5f) + phase * 1.3f) * amp * 0.4f
                    }
                    val pathBack = Path().apply {
                        val baseY = h * 0.55f; moveTo(0f, h); var x = 0f
                        while (x <= w) { lineTo(x, waveY(baseY, x, phase, 60f, 12f)); x += dx }
                        lineTo(w, h); close()
                    }
                    drawPath(pathBack, waveColor1.copy(alpha = 0.6f))
                    val pathMid = Path().apply {
                        val baseY = h * 0.65f; moveTo(0f, h); var x = 0f
                        while (x <= w) { lineTo(x, waveY(baseY, x, phase2, 50f, 10f)); x += dx }
                        lineTo(w, h); close()
                    }
                    drawPath(pathMid, waveColor2.copy(alpha = 0.5f))
                }

                // ── 按层渲染漂流瓶（层0→中层波浪→层1→前层波浪→层2）──
                val currentWavePhase = wavePhase.value
                if (areaWidth > 0) {
                    // 通用瓶子渲染函数（内联）
                    @Composable
                    fun renderBottleLayer(bottles: List<DriftingBottle>) {
                        bottles.forEach { bottle ->
                            key(bottle.id) {
                                val bottleId = bottle.id
                                val animX = remember(bottle.id) { Animatable(areaWidth.toFloat()) }
                                LaunchedEffect(bottle.id) {
                                    if (!bottle.skipDelay) delay(1000L + Random.nextLong(4000))
                                    animX.animateTo(
                                        targetValue = -bottleSizePx,
                                        animationSpec = tween(
                                            durationMillis = ((areaWidth + bottleSizePx) / bottle.speed * 350f).toInt().coerceIn(8000, 35000),
                                            easing = LinearEasing
                                        )
                                    )
                                    // 漂完移除，从队列补一个（补入的跳过起始延迟）
                                    bottleList.remove(bottle)
                                    if (bottleQueue.isNotEmpty()) {
                                        bottleList.add(bottleQueue.removeAt(0).copy(skipDelay = true))
                                    }
                                }
                                val xOffset = animX.value
                                val midPhase = currentWavePhase + bottle.waveOffset
                                val yWave = sin(xOffset / 80f + midPhase) * 20f + sin(xOffset / 40f + midPhase * 1.3f) * 8f
                                val yBase = boxHeightPx * bottle.targetY + yWave
                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(xOffset.roundToInt(), yBase.roundToInt()) }
                                        .size(48.dp)
                                        .graphicsLayer { rotationZ = bottle.rotation + sin(midPhase + xOffset * 0.01f) * 5f }
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                            scope.launch {
                                                val r = AuroraApi.pickBottleById(bottleId)
                                                if (r.success && r.data != null) {
                                                    val b = r.data.optJSONObject("bottle")
                                                    if (b != null && b.length() > 0) {
                                                        pickedBottle = b; showPickDialog = true
                                                    }
                                                } else {
                                                    Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                                                }
                                                refreshAll()
                                            }
                                        }
                                ) {
                                    Image(
                                        painter = painterResource(com.aurora.chat.R.drawable.ic_bottle),
                                        contentDescription = null, modifier = Modifier.fillMaxSize(),
                                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(bottleTint)
                                    )
                                }
                            }
                        }
                    }
                    // 层0 → 中层波浪 → 层1 → 前层波浪 → 层2
                    renderBottleLayer(bottleList.filter { it.layer == 0 })
                    MidWaveCanvas(phase, phase2, density)
                    renderBottleLayer(bottleList.filter { it.layer == 1 })
                    FrontWaveCanvas(phase, density)
                    renderBottleLayer(bottleList.filter { it.layer == 2 })
                }
                // 没有层2瓶子时才在外面画前层波浪覆盖所有
                if (bottleList.none { it.layer == 2 }) FrontWaveCanvas(phase, density)

                // 文字叠加在波浪上方
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "$bottleCount",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E3A5F)
                    )
                    Text(
                        text = "大海中漂流瓶总数",
                        fontSize = 13.sp,
                        color = Color(0xFF1E3A5F)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // 输入区域 - 扔瓶子
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFF0F4FF))
                    .padding(20.dp)
            ) {
                Column {
                    Text("扔瓶子", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                    Spacer(Modifier.height(8.dp))
                    Text("写下你想说的话，装进漂流瓶扔向大海",
                        fontSize = 12.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = bottleContent,
                            onValueChange = { if (it.length <= 500) bottleContent = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 28.dp),
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = Color(0xFF1F2937),
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (bottleContent.isEmpty()) {
                                        Text(
                                            "在此输入你想说的话...",
                                            fontSize = 14.sp,
                                            color = Color(0xFF9CA3AF),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${bottleContent.length}/500", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E40AF))
                                .clickable(enabled = !isThrowing) {
                                    throwBottle()
                                }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (isThrowing) "扔出中..." else "扔瓶子",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 捞瓶子按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFF0FFF4))
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("捞瓶子", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                        Spacer(Modifier.height(4.dp))
                        Text("从大海中随机捞起一个漂流瓶",
                            fontSize = 12.sp, color = Color(0xFF6B7280))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF16A34A))
                            .clickable(enabled = !isPicking) {
                                pickBottle()
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (isPicking) "捞取中..." else "捞瓶子",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // 捞到漂流瓶弹窗 — 不显示发送者，不留本地
    if (showPickDialog && pickedBottle != null) {
        val content = pickedBottle!!.optString("content", "")
        Dialog(
            onDismissRequest = {
                showPickDialog = false
                pickedBottle = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "捞到了一个漂流瓶",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937)
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF3F4F6))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = content,
                            fontSize = 15.sp,
                            color = Color(0xFF374151),
                            lineHeight = 24.sp
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E40AF))
                            .clickable {
                                showPickDialog = false
                                pickedBottle = null
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("关闭", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    // 每日次数限制弹窗
    if (showLimitDialog) {
        Dialog(
            onDismissRequest = { showLimitDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "提示",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = limitDialogMessage,
                        fontSize = 15.sp,
                        color = Color(0xFF374151)
                    )
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E40AF))
                            .clickable { showLimitDialog = false }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("知道了", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MidWaveCanvas(phase: Float, phase2: Float, density: androidx.compose.ui.unit.Density) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val waveColor2 = Color(0xFF5B9BD5); val dx = 4f
        fun wy(baseY: Float, x: Float, p: Float, freq: Float, amp: Float) = baseY + sin(x / freq + p) * amp + sin(x / (freq * 0.5f) + p * 1.3f) * amp * 0.4f
        val path = Path().apply {
            val baseY = h * 0.65f; moveTo(0f, h); var x = 0f
            while (x <= w) { lineTo(x, wy(baseY, x, phase2, 50f, 10f)); x += dx }
            lineTo(w, h); close()
        }
        drawPath(path, waveColor2.copy(alpha = 0.5f))
    }
}

@Composable
private fun FrontWaveCanvas(phase: Float, density: androidx.compose.ui.unit.Density) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val waveColor2 = Color(0xFF5B9BD5); val dx = 4f
        fun wy(baseY: Float, x: Float, p: Float, freq: Float, amp: Float) = baseY + sin(x / freq + p) * amp + sin(x / (freq * 0.5f) + p * 1.3f) * amp * 0.4f
        val path = Path().apply {
            val baseY = h * 0.78f; moveTo(0f, h); var x = 0f
            while (x <= w) { lineTo(x, wy(baseY, x, phase + 0.5f, 40f, 8f)); x += dx }
            lineTo(w, h); close()
        }
        drawPath(path, waveColor2.copy(alpha = 0.35f))
    }
}
