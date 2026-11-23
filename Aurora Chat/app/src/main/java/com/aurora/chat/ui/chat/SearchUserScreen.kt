package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.R
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.ui.components.blockPointerPassThrough
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults

// 蓝色高亮常量
private val HighlightBlue = Color(0xFF1E40AF)

enum class SearchView { SEARCH, PROFILE, GREETING }

data class SearchUserResult(val id: Long, val name: String, val email: String, val hideEmail: Int = 0, val qq: String = "", val hideQQ: Int = 0)

@Composable
fun SearchUserScreen(
    currentUserId: Long = 0,
    onDismiss: () -> Unit,
    visible: Boolean = true,  // 关闭时用于重置内部视图状态
    onOpenChat: (Long, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var currentView by remember { mutableStateOf(SearchView.SEARCH) }
    var selectedUser by remember { mutableStateOf<SearchUserResult?>(null) }
    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchUserResult>>(emptyList()) }
    var exitingResults by remember { mutableStateOf<List<SearchUserResult>?>(null) }
    var clearedFlags by remember { mutableStateOf(setOf<Int>()) }
    var isTransitioning by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var searchId by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // 关闭时重置内部视图（避免下次打开残留上一次的好友资料/打招呼面板）
    LaunchedEffect(visible) {
        if (!visible) {
            currentView = SearchView.SEARCH
            selectedUser = null
        }
    }
    // 优先关闭内部视图（资料/打招呼），而不是直接关闭整个搜索层
    BackHandler(enabled = currentView != SearchView.SEARCH) {
        if (currentView == SearchView.GREETING) currentView = SearchView.PROFILE
        else { currentView = SearchView.SEARCH; selectedUser = null }
    }

    Box(Modifier.fillMaxSize()) {
        com.aurora.chat.ui.components.EventBlocker()
        // ========== 搜索界面 ==========
        AnimatedVisibility(
            visible = currentView == SearchView.SEARCH,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .statusBarsPadding()
            ) {
                TopBar(title = "搜索用户", onBack = onDismiss)

                // 搜索栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(com.aurora.chat.ui.theme.appCardBg())
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        BasicTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()
                            ),
                            singleLine = true,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E40AF)),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (keyword.isEmpty()) {
                                        Text("输入QQ号或用户名或ID搜索",
                                            fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("搜索", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = Color(0xFF1E40AF),
                        modifier = Modifier
                            .clickable(enabled = !isTransitioning) {
                                hasSearched = true
                                if (keyword.isBlank()) { results = emptyList(); return@clickable }
                                // 触发交错过渡（旧结果滑出 → 新结果滑入）
                                searchId++
                                if (results.isNotEmpty()) {
                                    val old = results
                                    exitingResults = old
                                    clearedFlags = emptySet()
                                    isTransitioning = true
                                    scope.launch {
                                        old.indices.forEach { i ->
                                            launch {
                                                delay(i * 80L + 350L)
                                                clearedFlags = clearedFlags + i
                                            }
                                        }
                                        delay(old.size * 80L + 500L)
                                        exitingResults = null
                                        isTransitioning = false
                                    }
                                }
                                results = emptyList()
                                scope.launch {
                                    val result = ChatRepository.searchUsers(keyword)
                                    if (result.success) {
                                        val kw = keyword.trim()
                                        results = (result.data ?: emptyList()).map {
                                            SearchUserResult(id = it.id, name = it.username, email = it.email, hideEmail = it.hideEmail, qq = it.qqNumber, hideQQ = it.hideQQ)
                                        }.sortedByDescending { user ->
                                            var score = 0
                                            if (kw.isNotBlank()) {
                                                if (kw.equals(user.id.toString(), ignoreCase = true)) score++
                                                if (kw.equals(user.name, ignoreCase = true)) score++
                                                if (kw.equals(user.qq, ignoreCase = true)) score++
                                            }
                                            score
                                        }
                                    } else {
                                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp))
                }

                // 搜索结果（交错动画）
                if (hasSearched && results.isEmpty() && exitingResults == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("未找到相关用户", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                } else if (hasSearched || exitingResults != null) {
                    val maxCount = maxOf(exitingResults?.size ?: 0, results.size)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                    ) {
                        for (i in 0 until maxCount) {
                            val isOld = exitingResults != null && i < exitingResults!!.size && i !in clearedFlags
                            val isNew = i < results.size && (i in clearedFlags || exitingResults == null)

                            if (isOld) {
                                val exitKey = "exit-${exitingResults!![i].id}-$searchId-$i"
                                var startExit by remember(exitKey) { mutableStateOf(false) }
                                LaunchedEffect(exitKey) {
                                    delay(i * 80L + 50L)
                                    startExit = true
                                }
                                val offX by animateFloatAsState(
                                    targetValue = if (startExit) -800f else 0f,
                                    animationSpec = tween(300)
                                )
                                val itemA by animateFloatAsState(
                                    targetValue = if (startExit) 0f else 1f,
                                    animationSpec = tween(300)
                                )
                                SearchResultRow(
                                    user = exitingResults!![i],
                                    keyword = keyword,
                                    modifier = Modifier
                                        .graphicsLayer { translationX = offX; this.alpha = itemA },
                                    onView = {},
                                    
                                )
                            }

                            if (isNew && isOld) Spacer(Modifier.height(2.dp))

                            if (isNew) {
                                val enterKey = "enter-${results[i].id}-$searchId-$i"
                                var show by remember(enterKey) { mutableStateOf(false) }
                                LaunchedEffect(enterKey) {
                                    val delayMs = if (exitingResults != null) {
                                        i * 80L + 320L
                                    } else {
                                        i * 80L
                                    }
                                    delay(delayMs)
                                    show = true
                                }
                                val offX by animateFloatAsState(
                                    targetValue = if (show) 0f else 600f,
                                    animationSpec = tween(280)
                                )
                                val itemA by animateFloatAsState(
                                    targetValue = if (show) 1f else 0f,
                                    animationSpec = tween(250)
                                )
                                SearchResultRow(
                                    user = results[i],
                                    keyword = keyword,
                                    modifier = Modifier
                                        .graphicsLayer { translationX = offX; this.alpha = itemA },
                                    onView = {
                                        selectedUser = results[i]
                                        currentView = SearchView.PROFILE
                                    },
                                    
                                )
                            }
                        }
                    }
                }
            }
        }

        // ========== 用户资料界面 ==========
        // 复用全局 UserProfileCard（完整资料：头像/名字/ID/签名/邮箱/注册时间 + 添加好友/发消息），
        // 旧的 UserProfilePanel 排版残缺（Column 提前闭合、无背景无顶部栏），已删除。
        AnimatedVisibility(
            visible = selectedUser != null && currentView == SearchView.PROFILE,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it }
        ) {
            if (selectedUser != null) {
                UserProfileCard(
                    userId = selectedUser!!.id,
                    userName = selectedUser!!.name,
                    currentUserId = currentUserId,
                    onBack = { currentView = SearchView.SEARCH; selectedUser = null },
                    onDeleteFriend = {},
                    onOpenChat = onOpenChat
                )
            }
        }
    }
}

// ==================== 搜索结果行（仅"查看"按钮触发，整行不触发） ====================

@Composable
private fun SearchResultRow(user: SearchUserResult, keyword: String, modifier: Modifier = Modifier, onView: () -> Unit, ) {
    // 计算吻合标签（必须完全一致，一个字都不能少）
    val matchLabel = remember(keyword, user) {
        when {
            keyword.isBlank() -> ""
            keyword.equals(user.id.toString(), ignoreCase = true) -> "ID吻合"
            keyword.equals(user.name, ignoreCase = true) -> "名字吻合"
            keyword.equals(user.qq, ignoreCase = true) -> "QQ号吻合"
            else -> ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(user.id, user.name)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(highlightText(user.name, keyword), fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val displayQQ = when {
                user.qq.isBlank() -> "未填写QQ号"
                user.hideQQ == 1 -> "QQ: ${com.aurora.chat.util.MaskUtil.qq(user.qq)}"
                else -> "QQ: ${user.qq}"
            }
            Text(highlightText(displayQQ, keyword), fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // 吻合标签
        if (matchLabel.isNotEmpty()) {
            Text(matchLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF16A34A),
                modifier = Modifier.padding(end = 4.dp))
        }
        Text("查看", fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = Color(0xFF1E40AF),
            modifier = Modifier
                .clickable { onView() }
                .padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** 构建带关键字蓝高亮的 AnnotatedString */
private fun highlightText(text: String, keyword: String): androidx.compose.ui.text.AnnotatedString {
    if (keyword.isBlank()) return buildAnnotatedString { append(text) }
    return buildAnnotatedString {
        var start = 0
        while (true) {
            val index = text.indexOf(keyword, start, ignoreCase = true)
            if (index < 0) {
                append(text.substring(start))
                break
            }
            // 未匹配部分
            if (index > start) append(text.substring(start, index))
            // 匹配部分（标蓝）
            withStyle(SpanStyle(color = HighlightBlue, fontWeight = FontWeight.Bold)) {
                append(text.substring(index, index + keyword.length))
            }
            start = index + keyword.length
        }
    }
}

// ==================== 通用顶部栏 ====================

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold,
            color = Color(0xFF1E40AF), modifier = Modifier.clickable { onBack() })
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
    }
}

// ==================== 打招呼发送面板（发送好友申请，跨模块复用，勿改可见性） ====================

@Composable
fun SendGreetingPanel(
    userName: String,
    onSend: (String) -> Unit,
    onBack: () -> Unit
) {
    var greeting by remember { mutableStateOf("你好，交个朋友吧！") }

    // blockPointerPassThrough：本面板是浮在最上层的全屏页，
    // 必须吃掉自身空白区域的触摸，否则会穿透点到下层界面（列表/索引栏/灰底）。
    Column(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding()
        .blockPointerPassThrough()) {
        TopBar(title = "添加好友", onBack = onBack)

        Spacer(Modifier.height(20.dp))
        Text("发送好友申请给 $userName", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth().padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appCardBg())
                .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            BasicTextField(
                value = greeting, onValueChange = { greeting = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = false, minLines = 3, maxLines = 6,
                decorationBox = { innerTextField ->
                    Box {
                        if (greeting.isEmpty()) Text("请输入打招呼内容…", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        innerTextField()
                    }
                }
            )
        }

        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth().padding(24.dp)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E40AF))
                .clickable { onSend(greeting) }.padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("发送", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}
