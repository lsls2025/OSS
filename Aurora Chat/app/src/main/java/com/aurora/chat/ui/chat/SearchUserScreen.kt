package com.aurora.chat.ui.chat

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
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 蓝色高亮常量
private val HighlightBlue = Color(0xFF1E40AF)

enum class SearchView { SEARCH, PROFILE, GREETING }

data class SearchUserResult(val id: Long, val name: String, val email: String, val hideEmail: Int = 0, val qq: String = "", val hideQQ: Int = 0)

@Composable
fun SearchUserScreen(
    currentUserId: Long = 0,
    onDismiss: () -> Unit,
    visible: Boolean = true  // 关闭时用于重置内部视图状态
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
                    .background(Color.White)
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
                            .background(Color(0xFFF3F4F6))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        BasicTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp, color = Color(0xFF1F2937)
                            ),
                            singleLine = true,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E40AF)),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (keyword.isEmpty()) {
                                        Text("输入QQ号或用户名或ID搜索",
                                            fontSize = 13.sp, color = Color(0xFF9CA3AF))
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
                        Text("未找到相关用户", fontSize = 14.sp, color = Color(0xFF9CA3AF))
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
        AnimatedVisibility(
            visible = selectedUser != null && currentView == SearchView.PROFILE,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it }
        ) {
            if (selectedUser != null) {
                UserProfilePanel(
                    user = selectedUser!!,
                    onAddFriend = { currentView = SearchView.GREETING },
                    onBack = { currentView = SearchView.SEARCH },
                    
                )
            }
        }

        // ========== 发送打招呼界面 ==========
        AnimatedVisibility(
            visible = currentView == SearchView.GREETING,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it }
        ) {
            SendGreetingPanel(
                userName = selectedUser?.name ?: "",
                onSend = { greeting ->
                    scope.launch {
                        val result = ChatRepository.sendFriendRequest(
                            toEmail = selectedUser?.email ?: "",
                            greeting = greeting
                        )
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        if (result.success) onDismiss()
                    }
                },
                onBack = { currentView = SearchView.PROFILE }
            )
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
                Text(highlightText(user.name, keyword), fontSize = 15.sp, color = Color(0xFF1F2937),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val displayQQ = if (user.hideQQ == 1) "QQ: 该用户已隐藏" else user.qq.ifBlank { "未填写QQ号" }
            Text(highlightText(displayQQ, keyword), fontSize = 12.sp, color = Color(0xFF9CA3AF),
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
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
    }
}

// ==================== 用户资料面板 ====================

@Composable
private fun UserProfilePanel(
    user: SearchUserResult,
    onAddFriend: () -> Unit,
    onBack: () -> Unit,
    
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
    ) {
        TopBar(title = "用户资料", onBack = onBack)

        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(user.id, user.name, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(user.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                }
                Spacer(Modifier.height(4.dp))
                val displayQQ2 = if (user.hideQQ == 1) "QQ: 该用户已隐藏" else user.qq.ifBlank { "未填写QQ号" }
                Text(displayQQ2, fontSize = 14.sp, color = Color(0xFF9CA3AF))
            }
        }

        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth().padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F6))
                .clickable { onAddFriend() }.padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("添加好友", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
        }
    }
}

// ==================== 打招呼发送面板 ====================

@Composable
private fun SendGreetingPanel(
    userName: String,
    onSend: (String) -> Unit,
    onBack: () -> Unit
) {
    var greeting by remember { mutableStateOf("你好，交个朋友吧！") }

    Column(modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
        TopBar(title = "添加好友", onBack = onBack)

        Spacer(Modifier.height(20.dp))
        Text("发送好友申请给 $userName", fontSize = 14.sp, color = Color(0xFF6B7280),
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth().padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xFFF9FAFB))
                .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            BasicTextField(
                value = greeting, onValueChange = { greeting = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                singleLine = false, minLines = 3, maxLines = 6,
                decorationBox = { innerTextField ->
                    Box {
                        if (greeting.isEmpty()) Text("请输入打招呼内容…", fontSize = 14.sp, color = Color(0xFF9CA3AF))
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
