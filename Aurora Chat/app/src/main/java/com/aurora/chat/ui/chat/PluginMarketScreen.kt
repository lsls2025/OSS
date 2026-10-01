package com.aurora.chat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.launch
import org.json.JSONObject

private val PmAmber = Color(0xFFD97706)

/**
 * 插件市场：从右侧滑入的全屏面板（排版与模板同 WorkspaceFileManagerScreen）。
 * 结构为 Box 双层:底层=市场本体(顶栏+列表);上层=全屏覆盖层(制作器/我的插件),
 * 与会话页挂工作区/市场面板同一模板,保证切入时是完全盖住整个屏幕的全屏界面。
 * 右上角:人形图标=我的插件,+ 号=制作插件。市场数据来自服务端(仅审核通过的插件)。
 */
@Composable
fun PluginMarketScreen(userId: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val noRipple = remember { MutableInteractionSource() }
    var showMaker by remember { mutableStateOf(false) }
    var showMine by remember { mutableStateOf(false) }
    var plugins by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // 搜索:按名称/描述/作者过滤(不区分大小写)
    val shown = if (query.isBlank()) plugins else plugins.filter { p ->
        p.optString("name").contains(query.trim(), true) ||
            p.optString("description").contains(query.trim(), true) ||
            p.optString("uploader").contains(query.trim(), true)
    }

    fun refresh() {
        loading = true
        scope.launch {
            val res = AuroraApi.pluginMarket()
            loading = false
            if (res.success) {
                plugins = res.data ?: emptyList()
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // 返回键:子面板开着先关它(覆盖层自己的 BackHandler 更晚注册会优先生效,此处兜底),最后关市场
    BackHandler(enabled = !showMaker && !showMine) {
        onBack()
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFFF3F4F6))
            .clickable(interactionSource = noRipple, indication = null) {}
    ) {
        // ═══ 底层:市场本体(顶栏 + 已上架插件列表) ═══
        Column(Modifier.fillMaxSize()) {
            // 顶栏(与工作区同模板:白底铺满状态栏 + 返回 + 标题 + 我的 + 新建)
            Row(
                Modifier.fillMaxWidth().background(Color.White).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(interactionSource = noRipple, indication = null) { onBack() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937)) }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("插件市场", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                }
                // 我的插件:加号左侧「我的」图标,查看我创建的与可用的工具
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(interactionSource = noRipple, indication = null) { showMine = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Person, contentDescription = "我的插件", tint = Color(0xFF1E40AF), modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(2.dp))
                // 制作插件:右上角 + 号,切入全新制作界面
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(interactionSource = noRipple, indication = null) { showMaker = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Add, contentDescription = "制作插件", tint = PmAmber, modifier = Modifier.size(24.dp)) }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

            // 搜索框(白底条,与顶栏连为一体)
            Row(
                Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索插件", fontSize = 13.sp, color = Color(0xFF9CA3AF)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF9CA3AF), modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Box(
                                Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable(interactionSource = noRipple, indication = null) { query = "" },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.Close, contentDescription = "清空", tint = Color(0xFF9CA3AF), modifier = Modifier.size(16.dp)) }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

            // 内容区:已上架插件列表(按搜索词过滤)
            if (plugins.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFFFEF3C7)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Extension, contentDescription = null, tint = PmAmber, modifier = Modifier.size(36.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(if (loading) "加载中..." else "暂无插件", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151))
                        if (!loading) {
                            Spacer(Modifier.height(4.dp))
                            Text("点右上角 + 号制作一个", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                        }
                    }
                }
            } else if (shown.isEmpty()) {
                // 搜索无匹配
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("无匹配插件", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    items(shown, key = { it.optLong("id") }) { p ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
                                .clickable(interactionSource = noRipple, indication = null) { }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFFEF3C7)),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Filled.Extension, null, tint = PmAmber, modifier = Modifier.size(20.dp)) }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.optString("name"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                                    Text("作者:${p.optString("uploader")}", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                                }
                            }
                            if (p.optString("description").isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(p.optString("description"), fontSize = 12.sp, color = Color(0xFF6B7280), maxLines = 2)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }

        // ═══ 覆盖层:制作插件(全屏,从右侧滑入,盖住含顶栏在内的整个市场) ═══
        AnimatedVisibility(
            visible = showMaker,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            PluginMakerScreen(onBack = { showMaker = false })
        }

        // ═══ 覆盖层:我的插件(全屏,后声明压在制作器之上) ═══
        AnimatedVisibility(
            visible = showMine,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            PluginMyScreen(userId = userId, onBack = { showMine = false })
        }
    }
}
