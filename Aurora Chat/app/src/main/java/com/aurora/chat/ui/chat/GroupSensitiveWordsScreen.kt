package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.components.EventBlocker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 群敏感词（群主 / 管理员）
 *
 * 生效方式是「发出之后再处置」：服务端收到消息、落库、推给群成员，随后才发现命中，
 * 于是撤回并按设置禁言或移出。客户端这边只是一张配置表，不做任何发送前拦截。
 *
 * 提示文案在界面上是两段式：前半段「@群成员」固定不可改（真正 @ 谁由服务端按触发者补齐），
 * 管理员只填后面的正文；正文留空就用服务端内置的那句。
 */
private const val SENSITIVE_BYPASS_ADMIN = "admin"
private const val SENSITIVE_BYPASS_OWNER = "owner"
private const val SENSITIVE_BYPASS_NONE = "none"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupSensitiveWordsScreen(
    groupId: Long,
    onBack: () -> Unit,
    onSaved: (Boolean, Int, String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember(groupId) { mutableStateOf(false) }
    var words by remember(groupId) { mutableStateOf<List<String>>(emptyList()) }
    var action by remember(groupId) { mutableStateOf("recall") }
    var muteSeconds by remember(groupId) { mutableLongStateOf(3600L) }
    var kickBlacklist by remember(groupId) { mutableStateOf(false) }
    var bypassRole by remember(groupId) { mutableStateOf(SENSITIVE_BYPASS_ADMIN) }
    var noticeText by remember(groupId) { mutableStateOf("") }
    // 禁止发链接：与敏感词开关相互独立，命中后共用下面的「触发后」处置方式
    var blockLinks by remember(groupId) { mutableStateOf(false) }

    var wordInput by remember(groupId) { mutableStateOf("") }
    var customMinutes by remember(groupId) { mutableStateOf("") }
    var customMode by remember(groupId) { mutableStateOf(false) }
    var saving by remember(groupId) { mutableStateOf(false) }
    // 自动保存：只有读到了服务端配置才允许写回，否则首帧的默认值会盖掉线上配置
    var loaded by remember(groupId) { mutableStateOf(false) }
    var savedSnapshot by remember(groupId) { mutableStateOf("") }

    fun actionLabel(a: String) = when (a) {
        "mute" -> "禁言"
        "kick" -> "移出群聊"
        else -> "仅撤回"
    }

    // 一份配置的指纹：任何一项变了就自动落盘，没变就不发请求
    fun snapshot(): String = listOf(
        enabled, words.joinToString("\u0001"), action, muteSeconds,
        kickBlacklist, bypassRole, noticeText.trim(), blockLinks
    ).joinToString("\u0002")

    LaunchedEffect(groupId) {
        val data = runCatching { AuroraApi.getGroupSensitiveSettings(groupId) }.getOrNull()?.data
        if (data != null) {
            enabled = data.optBoolean("enabled", false)
            val arr = data.optJSONArray("words") ?: JSONArray()
            words = (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { w -> w.isNotBlank() } }
            action = data.optString("action", "recall").ifBlank { "recall" }
            muteSeconds = data.optLong("mute_seconds", 3600L).let { if (it > 0) it else 3600L }
            kickBlacklist = data.optBoolean("kick_blacklist", false)
            bypassRole = data.optString("bypass_role", SENSITIVE_BYPASS_ADMIN)
                .takeIf { it == SENSITIVE_BYPASS_OWNER || it == SENSITIVE_BYPASS_NONE }
                ?: SENSITIVE_BYPASS_ADMIN
            // 老数据里存的是带 {user} 的整句，界面上只保留正文部分
            noticeText = data.optString("notice_text", "").replace("{user}", "").trim()
            blockLinks = data.optBoolean("block_links", false)
            // 先记指纹再解锁：加载本身不算一次修改，否则刚进页面就会白写一遍
            savedSnapshot = snapshot()
            loaded = true
        }
    }

    fun addWord() {
        val w = wordInput.trim()
        if (w.isEmpty()) return
        if (words.any { it.equals(w, ignoreCase = true) }) {
            wordInput = ""
            return
        }
        if (words.size >= 200) {
            Toast.makeText(context, "最多 200 个词", Toast.LENGTH_SHORT).show()
            return
        }
        words = words + w.take(32)
        wordInput = ""
    }

    // 静默保存：不弹提示、不关页面，改完就在后台写下去
    suspend fun persist(): Boolean {
        val snap = snapshot()
        val r = AuroraApi.saveGroupSensitiveSettings(
            groupId = groupId,
            enabled = enabled,
            words = words,
            action = action,
            muteSeconds = muteSeconds,
            kickBlacklist = kickBlacklist,
            noticeText = noticeText.trim(),
            bypassRole = bypassRole,
            blockLinks = blockLinks
        )
        return if (r.success) {
            savedSnapshot = snap
            onSaved(enabled, words.size, actionLabel(action), blockLinks)
            true
        } else false
    }

    // 改动 → 停手 600ms 后自动保存（打字、连点词条都只会落一次）
    LaunchedEffect(enabled, words, action, muteSeconds, kickBlacklist, bypassRole, noticeText, blockLinks) {
        if (!loaded) return@LaunchedEffect
        val snap = snapshot()
        if (snap == savedSnapshot) return@LaunchedEffect
        delay(600)
        // 期间可能已经被手动「完成」写过了
        if (snap == savedSnapshot) return@LaunchedEffect
        persist()
    }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val ok = persist()
            saving = false
            if (ok) {
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                onBack()
            } else {
                Toast.makeText(context, "保存失败，已自动保存的内容稍后会重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 和「修改群欢迎语」同一套页面底色：白卡浮在浅灰上，卡与卡之间留呼吸
        EventBlocker(backgroundColor = com.aurora.chat.ui.theme.appCardBg())
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            // ── 顶部栏 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(28.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "群敏感词",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.width(28.dp))
            }
            Spacer(Modifier.height(8.dp))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── 开关 ──
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("群敏感词开关", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("开启群敏感词", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF1E40AF),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFD1D5DB)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 禁止发链接（独立开关，命中后和敏感词共用下面「触发后」的处置） ──
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("发言限制", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("禁止发链接", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = blockLinks,
                            onCheckedChange = { blockLinks = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF1E40AF),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFD1D5DB)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 词表 ──
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("敏感词", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SensitiveInputBox(
                            value = wordInput,
                            onValueChange = { wordInput = it },
                            placeholder = "输入词语",
                            maxChars = 32,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        val canAdd = wordInput.isNotBlank()
                        Box(
                            Modifier
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (canAdd) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appDivider())
                                .clickable(
                                    enabled = canAdd,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { addWord() }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "添加",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (canAdd) Color.White else com.aurora.chat.ui.theme.appTextHint()
                            )
                        }
                    }
                    if (words.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        WordChips(words = words, onRemove = { w -> words = words - w })
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 处置方式 ──
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("触发后", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    ActionRow("仅撤回", action == "recall") { action = "recall" }
                    ActionRow("禁言", action == "mute") { action = "mute" }
                    if (action == "mute") {
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                600L to "10分钟",
                                3600L to "1小时",
                                86400L to "1天",
                                604800L to "7天"
                            ).forEach { (sec, label) ->
                                PillChip(label = label, picked = !customMode && muteSeconds == sec) {
                                    customMode = false
                                    muteSeconds = sec
                                }
                            }
                            PillChip(label = "自定义", picked = customMode) { customMode = true }
                        }
                        if (customMode) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SensitiveInputBox(
                                    value = customMinutes,
                                    onValueChange = { v ->
                                        customMinutes = v.filter { it.isDigit() }.take(6)
                                        customMinutes.toLongOrNull()?.let { m ->
                                            if (m > 0) muteSeconds = m * 60L
                                        }
                                    },
                                    placeholder = "分钟",
                                    maxChars = 6,
                                    keyboardType = KeyboardType.Number,
                                    modifier = Modifier.width(96.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("分钟", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            }
                        }
                    }
                    ActionRow("移出群聊", action == "kick") { action = "kick" }
                    if (action == "kick") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("同时加入群黑名单", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                            Spacer(Modifier.weight(1f))
                            Switch(
                                checked = kickBlacklist,
                                onCheckedChange = { kickBlacklist = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1E40AF),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFD1D5DB)
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 谁可以发（豁免范围）──
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("谁可以发", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    ActionRow("群主可发", bypassRole == SENSITIVE_BYPASS_OWNER) {
                        bypassRole = SENSITIVE_BYPASS_OWNER
                    }
                    ActionRow("群主及管理员可发", bypassRole == SENSITIVE_BYPASS_ADMIN) {
                        bypassRole = SENSITIVE_BYPASS_ADMIN
                    }
                    ActionRow("全员不可发", bypassRole == SENSITIVE_BYPASS_NONE) {
                        bypassRole = SENSITIVE_BYPASS_NONE
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 提示文案：固定「@群成员」+ 正文 ──
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("提示文案", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().height(44.dp)
                            .clip(RoundedCornerShape(10.dp)).background(com.aurora.chat.ui.theme.appCardBg())
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("@群成员", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.width(1.dp).height(16.dp).background(Color(0xFFD1D5DB)))
                        Spacer(Modifier.width(8.dp))
                        // 正文留空 = 用服务端内置那句，所以这里不给占位文案
                        BasicTextField(
                            value = noticeText,
                            onValueChange = { noticeText = it.take(60) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = com.aurora.chat.ui.theme.appTextPrimary(),
                                lineHeight = 20.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            cursorBrush = SolidColor(Color(0xFF1E40AF))
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            Spacer(Modifier.height(12.dp))
            // ── 底部完成 ──
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (saving) Color(0xFF93A6D8) else Color(0xFF1E40AF))
                    .clickable { save() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (saving) "保存中…" else "完成",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 统一样式的单行输入框。
 *
 * 三个坑一起解决：
 *  1. 高度锁死 40dp —— 空框和满框一样高，不会因为有没有内容而上下抽动；
 *  2. 内容 CenterStart 居中 —— 光标不会贴顶，敲字前后位置一致；
 *  3. includeFontPadding = false —— 字体自带上下留白会让光标看起来偏上，关掉后与文字严格对齐。
 */
@Composable
private fun SensitiveInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxChars: Int = 32,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(com.aurora.chat.ui.theme.appCardBg())
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint(), lineHeight = 20.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.take(maxChars)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    lineHeight = 20.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                cursorBrush = SolidColor(Color(0xFF1E40AF)),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
            )
        }
    }
}

/** 词条：一行一个胶囊，点 ✕ 删除 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordChips(words: List<String>, onRemove: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        words.forEach { w ->
            Row(
                Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(com.aurora.chat.ui.theme.appCardBg())
                    .padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(w, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(4.dp))
                // ✕ 用图标而不是字符：字符的基线会让它在小方框里永远偏下半像素
                Box(
                    Modifier.size(20.dp).clip(CircleShape).clickable { onRemove(w) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "删除",
                        tint = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/** 小胶囊：禁言时长、自定义之类的快捷选择 */
@Composable
private fun PillChip(label: String, picked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (picked) Color(0xFFDBEAFE) else com.aurora.chat.ui.theme.appCardBg())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (picked) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextSecondary()
        )
    }
}

/** 单选行：左侧文字，右侧圆点（未选中是空心圈，不是灰块） */
@Composable
private fun ActionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(18.dp)
                .then(
                    if (selected) Modifier
                    else Modifier.border(1.5.dp, Color(0xFFD1D5DB), CircleShape)
                )
                .background(if (selected) Color(0xFF1E40AF) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(Modifier.size(6.dp).background(com.aurora.chat.ui.theme.appCardBg(), CircleShape))
            }
        }
    }
}
