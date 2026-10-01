package com.aurora.chat.ui.chat

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.aurora.chat.data.local.LocalStorage
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.components.TokenInsufficientCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private val SANDBOX_COLORS = listOf(
    0xFF1E40AF, 0xFFEC4899, 0xFF10B981, 0xFFF59E0B, 0xFF06B6D4, 0xFFEF4444
).map { Color(it) }

/** 超出预设颜色数量时循环取色，避免 AI 数量 > 6 时下标越界闪退。 */
private fun sandboxColor(i: Int) = SANDBOX_COLORS[i % SANDBOX_COLORS.size]

private val TRAIT_DEFAULTS = listOf(50f, 0f, 25f, 15f, 0f)

/** 每轮请求携带的最近历史条数（滑动窗口），防止无限循环下历史无限增长。 */
private const val HISTORY_WINDOW = 20

/** 单 AI 模式：用户沉默多久后 AI 才做一次“二次输出”（毫秒）。 */
private const val SANDBOX_WAIT_TIMEOUT = 15_000L
/** 单 AI 模式：连续“二次输出”达到此次数后停止自言自语，只等待用户发话。 */
private const val MAX_SANDBOX_FOLLOWUPS = 2
/** 开启「允许单AI多次发言」后，单个 AI 连续追加发言的最大次数，防止无限循环。 */
private const val MAX_MULTI_SPEAK = 3
/** 沙盒配置持久化到本地的 SharedPreferences 文件名。 */
private const val PREFS_SANDBOX = "aurora_sandbox"

/** 单个 AI 的配置（名称 + 5 维性格 + 自定义设定 + 角色设定 + 头像），可观察。 */
class SandboxAiConfig {
    var name by mutableStateOf("")
    val traits = mutableStateListOf<Float>().apply { addAll(TRAIT_DEFAULTS) }
    val customTraits = mutableStateListOf<CustomTrait>()
    /** 角色设定：仅该 AI 自己知道，其他 AI 不会知道（按字面意思注入 system 提示） */
    var privateNote by mutableStateOf("")
    /** 该 AI 自定义头像（点击名称左侧头像上传），对话中显示 */
    var avatarBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
}

/** 沙盒中的一条发言。 */
private var sandboxMsgSeq = 0L
private fun nextSandboxMsgId() = ++sandboxMsgSeq

data class SandboxMsg(
    val id: Long = nextSandboxMsgId(),
    val senderName: String,
    val color: Color,
    val text: String,
    val streaming: Boolean = false,
    val avatar: android.graphics.Bitmap? = null
)

/**
 * 沙盒的持久状态（提升到 MainActivity，离开界面仍保留设置与聊天）。
 * - running：配置态(false) / 沙盒对话态(true)
 * - playing：对话循环是否在跑（true=播放中，false=已暂停）
 */
class AiSandboxState {
    val maxCount = 20
    var count by mutableStateOf(2)
    val configs = List(maxCount) { SandboxAiConfig() }
    var unified by mutableStateOf("")
    var running by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var showAdvanced by mutableStateOf(false)
    var sequential by mutableStateOf(true)
    /** 允许单个 AI 在一次轮次中自行决定是否追加发言（如追问）。默认关闭。 */
    var allowMultiSpeak by mutableStateOf(false)
    /** 是否把其他 AI 的性格透露给每个 AI。默认关闭（仅显示姓名）。 */
    var notifyPersonalities by mutableStateOf(false)
    val transcript = mutableStateListOf<SandboxMsg>()
    var sessionTokens by mutableStateOf(0L)
    var showCloseConfirm by mutableStateOf(false)
    var loopJob: Job? = null

    // ── @点名 上下文运行期状态（无需持久化）：仅用于给每个 AI 注入「谁被点名」提示，不跳过任何 AI 的发言 ──
    /** 最近一次「我」发言中用户点名的 AI 名称；null 表示未点名任何人。 */
    var mentionedTarget: String? = null
    /** 被点名的 AI 是否已经回应过这次点名（回应后点名上下文自动失效，回归常规群聊）。 */
    var targetResponded: Boolean = false
    /** 上次解析点名时对应的「我」消息索引，用于识别用户是否发了新消息。 */
    var lastMentionUserIdx: Int = -1


    /** 持久化沙盒配置（数量、各 AI 名称/性格/角色设定/自定义设定、统一背景、高级开关、头像）到本地。 */
    fun saveConfig(ctx: android.content.Context) {
        val prefs = ctx.getSharedPreferences(PREFS_SANDBOX, android.content.Context.MODE_PRIVATE)
        val edit = prefs.edit()
        edit.putInt("count", count)
        edit.putString("unified", unified)
        edit.putBoolean("sequential", sequential)
        edit.putBoolean("allowMultiSpeak", allowMultiSpeak)
        edit.putBoolean("notifyPersonalities", notifyPersonalities)
        for (i in 0 until maxCount) {
            val c = configs[i]
            edit.putString("name_$i", c.name)
            edit.putString("privateNote_$i", c.privateNote)
            c.traits.forEachIndexed { k, v -> edit.putFloat("trait_${i}_$k", v) }
            edit.putInt("customCount_$i", c.customTraits.size)
            c.customTraits.forEachIndexed { ci, ct ->
                edit.putString("customText_${i}_$ci", ct.text)
                edit.putFloat("customPct_${i}_$ci", ct.pct)
            }
            val avFile = java.io.File(ctx.filesDir, "sandbox_avatar_$i.png")
            if (c.avatarBitmap != null) {
                try {
                    java.io.FileOutputStream(avFile).use { out ->
                        c.avatarBitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (_: Exception) {}
            } else if (avFile.exists()) {
                avFile.delete()
            }
        }
        edit.apply()
    }

    /** 从本地恢复沙盒配置；若无存档则保持默认（不覆盖）。 */
    fun loadConfig(ctx: android.content.Context) {
        val prefs = ctx.getSharedPreferences(PREFS_SANDBOX, android.content.Context.MODE_PRIVATE)
        val savedCount = prefs.getInt("count", -1)
        if (savedCount < 0) return
        count = savedCount.coerceIn(1, maxCount)
        unified = prefs.getString("unified", "") ?: ""
        sequential = prefs.getBoolean("sequential", true)
        allowMultiSpeak = prefs.getBoolean("allowMultiSpeak", false)
        notifyPersonalities = prefs.getBoolean("notifyPersonalities", false)
        for (i in 0 until maxCount) {
            val c = configs[i]
            c.name = prefs.getString("name_$i", "") ?: ""
            c.privateNote = prefs.getString("privateNote_$i", "") ?: ""
            val savedTraits = (0 until 5).map { k -> prefs.getFloat("trait_${i}_$k", TRAIT_DEFAULTS[k]) }
            c.traits.clear(); c.traits.addAll(savedTraits)
            val cc = prefs.getInt("customCount_$i", 0)
            c.customTraits.clear()
            for (ci in 0 until cc) {
                val t = prefs.getString("customText_${i}_$ci", "") ?: ""
                val p = prefs.getFloat("customPct_${i}_$ci", 50f)
                if (t.isNotBlank()) c.customTraits.add(CustomTrait(t, p))
            }
            val avFile = java.io.File(ctx.filesDir, "sandbox_avatar_$i.png")
            if (avFile.exists()) {
                try { c.avatarBitmap = android.graphics.BitmapFactory.decodeFile(avFile.absolutePath) } catch (_: Exception) {}
            } else {
                c.avatarBitmap = null
            }
        }
    }
}



/**
 * AI 沙盒：多 AI 轮流群聊。
 * - 配置态：设置数量、各自名称与性格、统一背景；底部「进入沙盒」。
 * - 沙盒对话态：按序号 1→N 依次发言（互不重叠），可暂停/启动；右上「关闭沙盒」二次确认后清理上下文。
 * 所有 AI 消耗 Token 由用户承担；开启时自动清理对话内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSandboxScreen(state: AiSandboxState, onBack: () -> Unit, userId: Long = 0, onRechargeToken: () -> Unit = {}, onTokenExhausted: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 高级功能已全部免费，不再按会员等级限制（会员体系已移除）



    // 我方（真实用户）头像：用于沙盒中「我」的发言气泡
    var myAvatarBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val file = LocalStorage.getMyAvatarFile(ctx, userId)
            if (file.exists()) myAvatarBitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        }
    }

    // 顶部实时余额：进入沙盒时拉取服务器真实 token 余额并缓存
    var serverBalance by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) {
        scope.launch {
            AiChatManager.syncServerLimit(ctx, userId)
            serverBalance = AiChatManager.getServerTokenBalance(ctx, userId)
        }
    }

    // 重新进入界面时：若仍在沙盒中且处于播放状态，恢复对话循环
    LaunchedEffect(Unit) {
        if (state.running && state.playing) startSandboxLoop(state, ctx, userId, scope, onTokenExhausted)
    }
    // 流式跟随：用户贴底时自动滚动到底部（含流式增长），上滑浏览时不强制
    LaunchedEffect(Unit) {
        snapshotFlow {
            val n = state.transcript.size
            if (n == 0) 0L else state.transcript[n - 1].text.length.toLong()
        }.collect {
            val n = state.transcript.size
            if (n == 0) return@collect
            val info = listState.layoutInfo
            val lastIndex = n - 1
            val lastItem = info.visibleItemsInfo.firstOrNull { it.index == lastIndex }
            // 是否已贴底：最后一条可见且底部贴近视口底；或最后一条尚未入屏但已接近末尾（新消息）
            val atBottom = if (lastItem != null) {
                (lastItem.offset + lastItem.size) <= info.viewportEndOffset + 5
            } else {
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= lastIndex - 1
            }
            if (atBottom) {
                val item = info.visibleItemsInfo.firstOrNull { it.index == lastIndex }
                if (item != null) {
                    // 最后一条比视口高（流式增长）：把其底部对齐视口底，避免新内容沉到屏外
                    val overflow = (item.offset + item.size) - info.viewportEndOffset
                    if (overflow > 0) {
                        val vpH = info.viewportEndOffset - info.viewportStartOffset
                        listState.scrollToItem(lastIndex, vpH - item.size)
                    }
                } else {
                    // 最后一条尚未入屏（新消息）：滚动使其底部贴底
                    listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
                }
            }
        }
    }

    // 配置持久化：任何设置（数量/名称/性格/角色设定/统一背景/高级开关）变更后自动保存到本地，
    // 关闭软件重新进入也不会丢失。
    LaunchedEffect(Unit) {
        snapshotFlow {
            buildList {
                add(state.count)
                add(state.unified)
                add(state.sequential)
                add(state.allowMultiSpeak)
                add(state.notifyPersonalities)
                for (i in 0 until state.maxCount) {
                    val c = state.configs[i]
                    add(c.name)
                    add(c.privateNote)
                    for (t in c.traits) add(t)
                    for (ct in c.customTraits) { add(ct.text); add(ct.pct) }
                }
            }
        }.collect { state.saveConfig(ctx) }
    }

    // 输入模式切换：false=沙盒按钮视图（键盘图标），true=手动输入视图（圆圈图标）
    var showInput by remember { mutableStateOf(false) }
    // 输入框文本（照搬普通聊天样式）
    var inputText by remember { mutableStateOf("") }

    // 用户手动发送消息：插入到对话流；若处于暂停态则自动启动循环让 AI 回应
    val sendUserMessage: (String) -> Unit = lambda@{ raw ->
        val t = raw.trim()
        if (t.isBlank()) return@lambda
        state.transcript.add(SandboxMsg(senderName = "我", color = Color(0xFF6B7280), text = t))
        inputText = ""
        if (!state.playing) {
            state.playing = true
            startSandboxLoop(state, ctx, userId, scope, onTokenExhausted)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF7F8FA)).imePadding()) {
        if (!state.running) {
            // ───── 配置态 ─────
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937))
                }
                Text("AI 沙盒", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937),
                    modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.width(10.dp))
                Text("注意：所有 AI 消耗的 Token 均由你承担", fontSize = 11.sp, color = Color(0xFFDC2626),
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }

            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("AI 数量", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFF3F4F6))
                        .clickable { if (state.count > 1) state.count-- }, contentAlignment = Alignment.Center) {
                        Text("−", fontSize = 20.sp, color = Color(0xFF374151))
                    }
                    Text(state.count.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937),
                        modifier = Modifier.padding(horizontal = 24.dp))
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFF3F4F6))
                        .clickable {
                            if (state.count < state.maxCount) state.count++
                        }, contentAlignment = Alignment.Center) {
                        Text("+", fontSize = 20.sp, color = Color(0xFF374151))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("最多支持 ${state.maxCount} 个，推荐 2 个", fontSize = 12.sp, color = Color(0xFF6B7280))
                }

                Spacer(Modifier.height(16.dp))

                // 统一背景/开场命令
                Text("统一背景 / 开场命令（可选）", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = state.unified,
                    onValueChange = { state.unified = it },
                    placeholder = { Text("例如：你们几个在讨论谁才是真正的程序员之神", color = Color(0xFF9CA3AF)) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                )

                // 高级设置（全部免费，不再限制会员等级）
                Row(
                    Modifier.fillMaxWidth().clickable { state.showAdvanced = !state.showAdvanced }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("高级设置", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                    Spacer(Modifier.weight(1f))
                    Text(if (state.showAdvanced) "收起" else "展开", fontSize = 12.sp, color = Color(0xFF6B7280))
                    Icon(
                        imageVector = if (state.showAdvanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null, tint = Color(0xFF6B7280), modifier = Modifier.size(20.dp)
                    )
                }
                if (state.showAdvanced) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF9FAFB)).padding(12.dp)
                    ) {
                        // 关闭顺序发言：字体右侧为开关按钮
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("关闭顺序发言", fontSize = 14.sp, color = Color(0xFF1F2937))
                                Text("开启后 AI 可灵活发言、互不打断、不再按固定序号", fontSize = 11.sp, color = Color(0xFF6B7280))
                            }
                            Spacer(Modifier.width(12.dp))
                            // 开关
                            val on = !state.sequential
                            Box(
                                Modifier.size(width = 46.dp, height = 26.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (on) Color(0xFF1E40AF) else Color(0xFFD1D5DB))
                                    .clickable { state.sequential = !state.sequential },
                                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White).padding(2.dp)) {}
                            }
                        }

                        // 允许单AI多次发言
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("允许单AI多次发言", fontSize = 14.sp, color = Color(0xFF1F2937))
                                Text("开启后 AI 可自行决定是否追加发言（如追问），仅在确需时使用", fontSize = 11.sp, color = Color(0xFF6B7280))
                            }
                            Spacer(Modifier.width(12.dp))
                            val onM = state.allowMultiSpeak
                            Box(
                                Modifier.size(width = 46.dp, height = 26.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (onM) Color(0xFF1E40AF) else Color(0xFFD1D5DB))
                                    .clickable { state.allowMultiSpeak = !state.allowMultiSpeak },
                                contentAlignment = if (onM) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White).padding(2.dp)) {}
                            }
                        }

                        // 通知AI其他AI的性格
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("通知AI其他AI的性格", fontSize = 14.sp, color = Color(0xFF1F2937))
                                Text("开启后每个 AI 会知道其他 AI 的性格；默认关闭，仅显示姓名", fontSize = 11.sp, color = Color(0xFF6B7280))
                            }
                            Spacer(Modifier.width(12.dp))
                            val onP = state.notifyPersonalities
                            Box(
                                Modifier.size(width = 46.dp, height = 26.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (onP) Color(0xFF1E40AF) else Color(0xFFD1D5DB))
                                    .clickable { state.notifyPersonalities = !state.notifyPersonalities },
                                contentAlignment = if (onP) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White).padding(2.dp)) {}
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Spacer(Modifier.height(16.dp))

                for (i in 0 until state.count) {
                    SandboxAiCard(config = state.configs[i], index = i, color = sandboxColor(i), onPersist = { state.saveConfig(ctx) })
                    Spacer(Modifier.height(12.dp))
                }
            }

            SandboxGradientButton("进入沙盒") {
                // 仅进入会话视图：保留已有聊天记录与已消耗，不清理（除非用户点「关闭沙盒」）
                state.running = true
            }
            Spacer(Modifier.height(16.dp))

        } else {
            // ───── 沙盒对话态 ─────
            // 顶部：左 键盘/圆圈（切换输入模式）、中 token 消耗、右 关闭沙盒（二次确认）
            val (used, limit) = AiChatManager.getOfficialUsage(ctx, userId)
            val isUnlimited = limit >= Long.MAX_VALUE
            val remain = if (isUnlimited) "无限" else (limit - used).coerceAtLeast(0).toString()
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { state.playing = false; state.running = false }) {
                    Icon(Icons.Filled.Close, contentDescription = "返回控制面板", tint = Color(0xFF1F2937))
                }
                Text(
                    "已消耗 ${state.sessionTokens} token · 余额 ${if (serverBalance >= 0L) serverBalance else "?"}",
                    fontSize = 11.sp, color = Color(0xFF6B7280),
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                )
                TextButton(onClick = { state.showCloseConfirm = true }) {
                    Text("关闭沙盒", color = Color(0xFFDC2626), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(state.transcript, key = { it.id }) { m ->
                    val isMe = m.senderName == "我"
                    if (isMe) {
                        // 用户消息：头像在右、气泡靠右、蓝色高亮
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.End
                        ) {
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text("我", fontSize = 13.sp, color = Color(0xFF1E40AF),
                                    fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    Modifier.clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF1E40AF))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        m.text + if (m.streaming) "▌" else "",
                                        fontSize = 15.sp, color = Color.White, lineHeight = 22.sp
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(if (myAvatarBitmap == null) m.color else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                if (myAvatarBitmap != null) {
                                    Image(
                                        bitmap = myAvatarBitmap!!.asImageBitmap(),
                                        contentDescription = "我",
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text("我", color = Color.White, fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }
                    } else {
                        // AI / 系统消息：头像在左、气泡靠左、白底
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            val aiBmp = m.avatar
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(if (aiBmp == null) m.color else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                if (aiBmp != null) {
                                    Image(
                                        bitmap = aiBmp.asImageBitmap(),
                                        contentDescription = m.senderName,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(m.senderName.take(2), color = Color.White, fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.senderName, fontSize = 13.sp, color = m.color,
                                    fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        m.text + if (m.streaming) "▌" else "",
                                        fontSize = 15.sp, color = Color(0xFF1F2937), lineHeight = 22.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            // 底部：左 键盘/圆圈（切换输入模式） + 右 启动沙盒 或 输入框；整体随键盘抬起
            Column(Modifier.fillMaxWidth().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 切换输入 / 沙盒模式：键盘图标 ⇄ 圆圈图标
                    IconButton(onClick = { showInput = !showInput }) {
                        Icon(
                            if (showInput) Icons.Filled.RadioButtonUnchecked else Icons.Filled.Keyboard,
                            contentDescription = if (showInput) "收起输入" else "输入消息",
                            tint = Color(0xFF1F2937)
                        )
                    }
                    if (showInput) {
                        // 手动输入视图（样式照搬普通聊天，AI 能看到用户输入）
                        Spacer(Modifier.width(8.dp))
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()
                        val borderColor by animateColorAsState(
                            targetValue = if (isFocused) Color(0xFF1E40AF) else Color(0xFFD1D5DB),
                            animationSpec = tween(200), label = "sandboxInputBorder"
                        )
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                .background(Color.White).border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 0.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 120.dp),
                                singleLine = false, maxLines = 5,
                                textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFF1F2937)),
                                cursorBrush = SolidColor(Color(0xFF1E40AF)),
                                decorationBox = { innerTextField ->
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                        if (inputText.isEmpty()) Text("输入消息...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                                        innerTextField()
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.height(36.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E40AF))
                                .clickable { sendUserMessage(inputText) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("发送", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                    } else {
                        // 沙盒控制视图：启动 / 暂停按钮（占据剩余宽度）
                        SandboxGradientButton(
                            if (state.playing) "暂停沙盒" else "启动沙盒",
                            modifier = Modifier.weight(1f)
                        ) {
                            state.playing = !state.playing
                            if (state.playing) startSandboxLoop(state, ctx, userId, scope)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // 关闭沙盒二次确认
    if (state.showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { state.showCloseConfirm = false },
            title = { Text("关闭沙盒", fontWeight = FontWeight.Bold) },
            text = { Text("关闭后，上下文（聊天记录）将被清理。确定关闭？") },
            confirmButton = {
                TextButton(onClick = {
                    state.showCloseConfirm = false
                    state.playing = false
                    state.transcript.clear()
                    state.sessionTokens = 0
                    state.running = false
                }) {
                    Text("确定关闭", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { state.showCloseConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/** 蓝粉流动渐变按钮（与「开启新对话」完全一致）。 */
@Composable
private fun SandboxGradientButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val floatTrans = rememberInfiniteTransition()
    val floatT by floatTrans.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val floatColors = listOf(
        0xFF38BDF8.toInt(), 0xFFEC4899.toInt(),
        0xFFF9A8D4.toInt(), 0xFF7DD3FC.toInt(), 0xFF38BDF8.toInt()
    )
    fun lerpFloatColor(tt: Float): Int {
        val seg = (floatColors.size - 1) * tt
        val i = seg.toInt().coerceIn(0, floatColors.size - 2)
        val f = seg - i
        val c1 = floatColors[i]; val c2 = floatColors[i + 1]
        val r = ((c1 shr 16 and 0xFF) * (1 - f) + (c2 shr 16 and 0xFF) * f).toInt()
        val g = ((c1 shr 8 and 0xFF) * (1 - f) + (c2 shr 8 and 0xFF) * f).toInt()
        val b = ((c1 and 0xFF) * (1 - f) + (c2 and 0xFF) * f).toInt()
        return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
    }
    val g1 = Color(lerpFloatColor(floatT))
    val g2 = Color(lerpFloatColor((floatT + 0.5f) % 1f))
    Box(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp)
            .background(Brush.linearGradient(colors = listOf(g1, g2)), RoundedCornerShape(50))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** 启动 / 恢复沙盒对话循环：按序号 1→N 依次发言，上一个说完才轮下一个；playing=false 时自然退出。 */
/**
 * 从用户发言中提取被 @点名 的 AI 名称（用于「只让被点名的 AI 发言」的隔离）。
 * 例如「AI 1 号请回复我」→ "AI 1"；「让 AI 9 说」→ "AI 9"。未点名则返回 null。
 */
private fun detectMentionedAi(userText: String, names: List<String>): String? {
    val text = userText.trim()
    if (text.isEmpty()) return null
    // 按名称长度降序匹配，避免短名被长名包含（如「AI 1」误命中「AI 10」）
    for (name in names.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
        var idx = text.indexOf(name)
        while (idx >= 0) {
            val after = if (idx + name.length < text.length) text[idx + name.length] else null
            // 名称后是「号」或边界，或后面不是数字（避免「AI 1」命中「AI 10」）才视为点名
            if (after == null || after == '号' || !after.isDigit()) return name
            idx = text.indexOf(name, idx + 1)
        }
    }
    return null
}

private fun startSandboxLoop(state: AiSandboxState, ctx: Context, userId: Long, scope: CoroutineScope, onTokenExhausted: () -> Unit = {}) {
    if (state.loopJob?.isActive == true) return
    state.loopJob = scope.launch {
        try {
            val names = (0 until state.count).map { i -> state.configs[i].name.ifBlank { "AI ${i + 1}" } }
            val traitList = (0 until state.count).map { i -> state.configs[i].traits.toList() }
            val customList = (0 until state.count).map { i -> state.configs[i].customTraits.toList() }
            val privateNotes = (0 until state.count).map { i -> state.configs[i].privateNote }
            val unifiedText = state.unified
            // 单 AI 模式：把真人「我」视为第二个参与者，AI 需等待用户发言，用户沉默过久才二次输出
            val singleAiMode = state.count == 1
            var consecutiveFollowUps = 0
            val multiSpeakOn = state.allowMultiSpeak && !singleAiMode
            // 单个 AI 本轮发言（含流式、额度兜底、PASS）。返回 true 表示该 AI 请求再次发言（[再说一次]）。
            suspend fun speakFor(i: Int): Boolean {
                val myName = names[i]
                val (uU, uL) = AiChatManager.getOfficialUsage(ctx, userId)
            if (uU >= uL) {
                state.playing = false
                onTokenExhausted()
                return false
            }
                val sys = buildSandboxSystem(i, myName, names, traitList, customList, unifiedText, privateNotes[i], multiSpeakOn, state.notifyPersonalities)
                // 关闭顺序发言（灵活发言）：允许该 AI 本轮选择不说话（单 AI 模式始终发言，不用 PASS）
                val passEnabled = !state.sequential && !singleAiMode
                val sysFinal = buildString {
                    append(sys)
                    // 点名上下文：明确告诉本 AI 用户点名的是谁——既让被点名者主动回应，也防止其他 AI 误以为叫自己
                    val mentioned = state.mentionedTarget
                    if (mentioned != null && !state.targetResponded) {
                        if (mentioned == myName) {
                            append("\n\n【点名】用户刚刚明确点名了你「$myName」，请以「$myName」的身份直接回应这句——这是专门叫你的。")
                        } else {
                            append("\n\n【点名】用户刚刚点名的是「$mentioned」，$myName 你没有被点名。这条消息不是专门叫你，请勿以「用户叫我了」的口吻回应（例如不要说「我在听着呢」）；你可以像群聊一样正常接话或补充，但绝不要冒充「$mentioned」、绝不要输出它的名字前缀。")
                        }
                    } else if (passEnabled) {
                        // 智能灵活发言：鼓励基于自身性格发言，仅在点名他人/真无话可说时 [PASS]，避免「全员一起发」或「全员沉默」
                        append("\n\n【灵活发言】你是群聊中的一员。只有当「用户点名了其他 AI」或「你实在没有任何新观点」时才输出一个词 [PASS]；否则请基于你自己的性格，给出你独特的看法或回应（即便别人说过，你的视角也应不同），不要总是沉默。")
                    }
                }
                val history = mutableListOf("system" to sysFinal)
                for (m in state.transcript.takeLast(HISTORY_WINDOW)) {
                    if (m.senderName == "系统") continue
                    if (m.senderName == "我") {
                        // 真实人类用户：user 角色，保留「我：」前缀（与 system 提示一致）
                        history.add("user" to "我：${m.text}")
                    } else if (m.senderName == myName) {
                        // 当前 AI 自己的历史发言：不加名字前缀（与「你发言不带前缀」的指令一致），
                        // 否则模型会把带「自己名字：」的历史当成另一个参与者，误认成别人说的
                        history.add("assistant" to m.text)
                    } else {
                        // 其它 AI：assistant 角色 + 名字前缀，便于区分
                        history.add("assistant" to "${m.senderName}：${m.text}")
                    }
                }
                // 记录本条 AI 消息的固定索引：避免用户中途发消息导致 lastIndex 偏移、流式文本误覆盖用户气泡
                val myIdx = state.transcript.size
                state.transcript.add(SandboxMsg(senderName = myName, color = sandboxColor(i), text = "", streaming = true, avatar = state.configs[i].avatarBitmap))
                // 实时估算：流式过程中按已生成文本长度近似累加 token，结束时再用真实用量校正
                var estSoFar = 0L
                var prevEst = 0L
                var wantMore = false
                try {
                    AiChatManager.streamChat(
                        ctx = ctx, userId = userId, history = history,
                        treatEmptyAsError = false,
                        onDelta = { cur ->
                            val idx = myIdx
                            // 识别多次发言标记（仅用于决定是否再讲一次，不展示给用户）
                            if (cur.contains("[再说一次]")) wantMore = true
                            if (idx in state.transcript.indices) state.transcript[idx] = state.transcript[idx].copy(text = cur.replace("[再说一次]", ""), streaming = true)
                            estSoFar = (cur.length * 0.6).toLong()
                            val step = (estSoFar - prevEst).coerceAtLeast(0)
                            prevEst = estSoFar
                            if (step > 0) state.sessionTokens += step
                        },
                        onDone = { usage, _, _, _ ->
                            val idx = myIdx
                            // 灵活发言：若 AI 选择 [PASS]，移除这条空发言（不计入对话）
                            val finalText = if (idx in state.transcript.indices) state.transcript[idx].text.trim() else ""
                            if (passEnabled && finalText == "[PASS]") {
                                if (idx in state.transcript.indices) state.transcript.removeAt(idx)
                            } else if (idx in state.transcript.indices) {
                                state.transcript[idx] = state.transcript[idx].copy(text = finalText, streaming = false)
                            }
                            // 用真实用量校正：有真实用量则替换估算，否则保留估算（避免数字回落）
                            val real = if (usage > 0) usage else estSoFar
                            state.sessionTokens = state.sessionTokens - estSoFar + real
                            if (usage > 0) AiChatManager.addOfficialUsage(ctx, userId, usage)
                        },
                        onError = { err ->
                            val idx = myIdx
                            if (idx in state.transcript.indices) state.transcript[idx] = state.transcript[idx].copy(
                                text = (state.transcript[idx].text.ifBlank { "" }) + "\n[出错：$err]", streaming = false)
                        }
                    )
                } catch (e: Exception) {
                    // 离开沙盒时协程被取消（CancellationException）属正常，不显示为错误
                    if (e is CancellationException) return@speakFor false
                    val idx = myIdx
                    if (idx in state.transcript.indices) state.transcript[idx] = state.transcript[idx].copy(text = "[异常：${e.message}]", streaming = false)
                }
                delay(500)
                return wantMore
            }

            var round = 0
            while (state.playing) {
                // ── 解析点名：仅当用户发了「新」消息（索引变化）时才更新；被点名 AI 回应后 targetResponded=true，点名上下文自动失效 ──
                val lastUserIdx = state.transcript.indexOfLast { it.senderName == "我" }
                if (lastUserIdx >= 0 && lastUserIdx != state.lastMentionUserIdx) {
                    state.mentionedTarget = detectMentionedAi(state.transcript[lastUserIdx].text, names)
                    state.targetResponded = false
                    state.lastMentionUserIdx = lastUserIdx
                }
                val mentionedTarget = state.mentionedTarget

                // 顺序发言：固定 0→N；关闭顺序发言：每轮按固定节奏轮转起始位（不再随机“抽签”）。
                val order = if (state.sequential) {
                    names.indices.toList()
                } else {
                    val offset = round % names.size
                    names.indices.map { (it + offset) % names.size }
                }
                round++
                for (i in order) {
                    if (!state.playing) break
                    val myName = names[i]
                    // 所有 AI 照常轮转：点名不阻止其他 AI 继续往下。点名只通过 system 提示让
                    // 「被点名的 AI 主动回应」、并让「其他 AI 别误以为叫自己」，从而解决身份错乱。
                    val isTarget = mentionedTarget != null && !state.targetResponded && mentionedTarget == myName
                    val more = speakFor(i)
                    if (isTarget) state.targetResponded = true
                    // 常规轮转：允许单 AI 在轮次内连续追加（上限 MAX_MULTI_SPEAK），由 AI 自行决定是否再讲
                    var extra = 0
                    while (state.playing && multiSpeakOn && more && extra < MAX_MULTI_SPEAK) {
                        val m2 = speakFor(i)
                        extra++
                        if (!m2) break
                    }
                }
            }
        } finally {
            state.loopJob = null
        }
    }
}

/** 构造第 i 个 AI 的 system 提示：自身性格（最强约束）+ 身份名 + 其他 AI 的名字/性格 + 统一背景。 */
private fun buildSandboxSystem(
    i: Int,
    myName: String,
    names: List<String>,
    traitList: List<List<Float>>,
    customList: List<List<CustomTrait>>,
    unified: String,
    privateNote: String,
    allowMultiSpeak: Boolean,
    notifyPersonalities: Boolean
): String = buildString {
    append("【你的专属性格，必须严格遵守】\n")
    append(AiChatManager.buildPersonaSystemPrompt(traitList[i], customList.getOrElse(i) { emptyList() }))
    append("\n无论其他 AI 说了什么、语气如何，你都必须始终且 ONLY 以「上面设定的性格」来回应，绝不能被他人的语气带偏、也绝不要模仿他人。")
    append("\n\n你当前的身份是「$myName」")
    val others = (names.indices).filter { it != i }.joinToString("\n") { j ->
        val t = traitList[j]
        val dims = if (notifyPersonalities) {
            "（性格：" + AiChatManager.TRAIT_LABELS.mapIndexed { k, l -> "$l${t[k].toInt()}%" }.joinToString("，") + "）"
        } else ""
        "· ${names[j]}$dims"
    }
    if (others.isNotBlank()) append("。参与对话的其他 AI 有：\n$others")
    if (others.isBlank()) {
        // 单 AI：与真人「我」一对一，禁止自言自语
        append("。当前对话中只有你与真实用户「我」两人，你们正在进行一对一聊天（不是群聊）。请只在用户「我」发话后才回应；若用户尚未说话，请耐心等待，绝不要自己跟自己对话或自言自语。")
    } else {
        append("\n你们正在进行群聊。请以上文为基础，以「$myName」的口吻继续发言，简洁、有观点、不要重复他人刚说过的话。")
    }
    append("每次只输出你这一段话，不要带「$myName：」或「AI X：」或「AI：」这类前缀。你永远只是「$myName」，绝不要冒充其他 AI、绝不要以别人的名字开头、绝不要假装是用户「我」。")
    append("\n注意：对话中若出现以「我：」开头的内容，那是真实的人类用户（不是 AI）的发言；以「其他 AI 名字：」开头的则是别的 AI 的发言。你只是「$myName」，当用户「我」发话时，请以你的身份自然地回应或接话，绝不要假装自己是「我」、也绝不要假装成其他 AI。")
    if (unified.isNotBlank()) append("\n\n统一背景 / 指令：$unified")
    if (privateNote.isNotBlank()) append("\n\n【只有你自己知道的专属设定（以下信息只告诉你一个 AI，其他 AI 不会知道，你也绝不要向任何人透露）】$privateNote")
    if (allowMultiSpeak) append("\n\n【多次发言】如果你认为确有需要向对话中追加一次自己的发言（例如想要追问对方、补充关键观点或回应刚刚的内容），请在你的整段发言末尾【单独一行】写上 [再说一次]；除确有必要时外，不要使用此标记，正常结束即可。系统会自动识别该标记并让你再讲一次。")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SandboxAiCard(config: SandboxAiConfig, index: Int, color: Color, onPersist: () -> Unit) {
    val labels = AiChatManager.TRAIT_LABELS
    val effectiveName = config.name.ifBlank { "AI ${index + 1}" }
    val ctx = LocalContext.current
    // 头像选择：点击名称左侧头像上传，上传后该 AI 在对话中显示此图片
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    config.avatarBitmap = android.graphics.BitmapFactory.decodeStream(input)
                    onPersist()
                }
            } catch (_: Exception) {}
        }
    }
    // 自定义设定：5 条性格之外的额外一条，按字面意思注入，不绑定任何维度
    var showCustomDialog by remember { mutableStateOf(false) }
    var customDialogText by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(color)
                    .clickable { avatarPicker.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                val bmp = config.avatarBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = effectiveName,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(effectiveName.take(3), color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = config.name,
                onValueChange = { config.name = it },
                placeholder = { Text("名称（默认 AI ${index + 1}）", color = Color(0xFF9CA3AF)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }

        Spacer(Modifier.height(10.dp))
        // 角色设定：仅该 AI 自己知道，其他 AI 不会知道
        Text("角色设定（仅该 AI 自己知道）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
        Text("告诉这个 AI 的私密信息，其他 AI 不会知道你跟他说了什么", fontSize = 11.sp, color = Color(0xFF6B7280))
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = config.privateNote,
            onValueChange = { config.privateNote = it },
            placeholder = { Text("例如：你其实是我养的一只猫，别让其他 AI 知道", color = Color(0xFF9CA3AF)) },
            singleLine = false,
            modifier = Modifier.fillMaxWidth().height(80.dp)
        )

        Spacer(Modifier.height(12.dp))
        Text("性格调试", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
        Text("拖动一个会自动压缩其余维度（总和 ≤ 100%）", fontSize = 11.sp, color = Color(0xFF6B7280))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val used = config.traits.sum().toInt()
            Text("已分配 $used% / 100%", fontSize = 11.sp,
                color = if (used > 100) Color(0xFFDC2626) else Color(0xFF6B7280))
        }
        Spacer(Modifier.height(4.dp))

        labels.forEachIndexed { k, label ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = Color(0xFF374151), modifier = Modifier.width(40.dp))
                Slider(
                    value = config.traits[k],
                    onValueChange = { newVal ->
                        val old = config.traits[k]
                        val othersSum = config.traits.sum() - old
                        val v = newVal.coerceIn(0f, 100f)
                        if (v + othersSum <= 100f) {
                            config.traits[k] = v
                        } else if (othersSum > 0f) {
                            val scale = (100f - v) / othersSum
                            for (j in config.traits.indices) if (j != k) config.traits[j] = config.traits[j] * scale
                            config.traits[k] = v
                        } else {
                            config.traits[k] = 100f
                        }
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    track = { positions ->
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFFE5E7EB))) {
                            Box(Modifier.fillMaxWidth((positions.value / 100f).coerceIn(0f, 1f))
                                .height(6.dp).clip(RoundedCornerShape(3.dp)).background(color)) {}
                        }
                    },
                    thumb = { Box(Modifier.size(22.dp).clip(CircleShape).background(color)) {} }
                )
                Text("${config.traits[k].toInt()}%", fontSize = 11.sp, color = Color(0xFF6B7280),
                    modifier = Modifier.width(36.dp))
            }
            Spacer(Modifier.height(10.dp))
        }

        // 极端（最后一条性格设定）下方：自定义入口，仅此一处，不绑定任何维度
        Text("＋ 自定义设定（在 5 条调试之外额外添加）", fontSize = 12.sp, color = Color(0xFF1E40AF),
            modifier = Modifier.fillMaxWidth()
                .clickable { showCustomDialog = true; customDialogText = "" }
                .padding(vertical = 6.dp))
        Spacer(Modifier.height(8.dp))

        // 自定义设定列表（按字面意思注入，可调节权重）
        if (config.customTraits.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("已添加的自定义设定", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
            Text("按字面意思注入，右侧可调节权重，点击 ✕ 删除", fontSize = 10.sp, color = Color(0xFF6B7280))
            Spacer(Modifier.height(6.dp))
            config.customTraits.forEachIndexed { ci, c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.text, fontSize = 12.sp, color = Color(0xFF374151))
                    }
                    Slider(
                        value = c.pct,
                        onValueChange = { config.customTraits[ci] = c.copy(pct = it) },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f),
                        track = { positions ->
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFE5E7EB))) {
                                Box(Modifier.fillMaxWidth((positions.value / 100f).coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color)) {}
                            }
                        },
                        thumb = { Box(Modifier.size(22.dp).clip(CircleShape).background(color)) {} }
                    )
                    Text("${c.pct.toInt()}%", fontSize = 11.sp, color = Color(0xFF374151),
                        modifier = Modifier.width(34.dp))
                    Text("✕", fontSize = 13.sp, color = Color(0xFFDC2626),
                        modifier = Modifier.clickable { config.customTraits.removeAt(ci) }.padding(start = 2.dp, end = 2.dp))
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text("沙盒中该 AI 将以「$effectiveName」身份与其他 AI 对话，并知晓彼此名称。",
            fontSize = 11.sp, color = Color(0xFF9CA3AF))

        // 自定义设定输入弹窗
        if (showCustomDialog) {
            AlertDialog(
                onDismissRequest = { showCustomDialog = false; customDialogText = "" },
                containerColor = Color.White,
                title = { Text("自定义设定", fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = customDialogText,
                        onValueChange = { customDialogText = it },
                        label = { Text("输入自定义内容（按字面意思注入）") },
                        placeholder = { Text("例如：回答时先给结论再展开") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val txt = customDialogText.trim()
                        if (txt.isNotBlank()) config.customTraits.add(CustomTrait(txt, 50f))
                        showCustomDialog = false; customDialogText = ""
                    }) { Text("添加", color = Color(0xFF1E40AF)) }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomDialog = false; customDialogText = "" }) { Text("取消") }
                }
            )
        }
    }
}
