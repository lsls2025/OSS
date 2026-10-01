package com.aurora.chat.ui.chat

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.GroupAvatar
import kotlinx.coroutines.launch
import org.json.JSONObject

data class GroupSearchResult(val id: Long, val name: String, val displayId: Long, val signature: String)

@Composable
fun SearchGroupScreen(
    currentUserId: Long = 0,
    onDismiss: () -> Unit,
    onOpenGroupChat: (groupConvId: Long, groupName: String) -> Unit,
    initialGroupId: Long = 0,  // 非 0 时自动打开该群的详情面板
    visible: Boolean = true     // 关闭时用于重置内部面板状态
) {
    val context = LocalContext.current
    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GroupSearchResult>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    fun hideKeyboard() { imm.hideSoftInputFromWindow(view.windowToken, 0) }

    // 群详情面板状态
    var showInfoPanel by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<GroupSearchResult?>(null) }

    // 如果有 initialGroupId，自动加载群信息并打开详情面板
    LaunchedEffect(initialGroupId) {
        if (initialGroupId > 0) {
            val r = AuroraApi.getGroupInfo(initialGroupId)
            if (r.success && r.data != null) {
                val data = r.data!!
                val gName = data.optString("name", "")
                val gDisplayId = data.optLong("display_id", 0)
                val gSignature = data.optString("signature", "")
                selectedGroup = GroupSearchResult(initialGroupId, gName, gDisplayId, gSignature)
                showInfoPanel = true
            }
        }
    }

    // 关闭时重置内部面板状态（避免下次打开残留上一次的群详情）
    LaunchedEffect(visible) {
        if (!visible) {
            selectedGroup = null
            showInfoPanel = false
        }
    }
    // 优先关闭内部面板，而不是直接关闭整个搜索层（修复：查看群详情后返回会直接退出搜索）
    BackHandler(enabled = showInfoPanel) {
        if (initialGroupId > 0) onDismiss() // 从分享卡片进入的详情：一步退回卡片所在对话，不经过搜索列表
        else showInfoPanel = false
    }

    Box(Modifier.fillMaxSize()) {
        // ===== 主搜索界面 =====
            Box(Modifier.fillMaxSize()) {
                // 透明拦截层：阻止事件穿透到下层，但不干扰子组件
                com.aurora.chat.ui.components.EventBlocker()
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.White)
                ) {
                // 顶部栏
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
                        Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("搜索群聊", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                }
                // 搜索栏
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F6))
                        .padding(horizontal = 12.dp, vertical = 10.dp)) {
                        BasicTextField(value = keyword, onValueChange = { keyword = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box { if (keyword.isEmpty()) Text("输入群ID搜索", fontSize = 13.sp, color = Color(0xFF9CA3AF)); inner() }
                            })
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("搜索", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                        modifier = Modifier.clickable(enabled = !isSearching) {
                            hasSearched = true
                            if (keyword.isBlank()) { results = emptyList(); return@clickable }
                            isSearching = true
                            scope.launch {
                                val result = ChatRepository.searchGroups(keyword)
                                isSearching = false
                                if (result.success) {
                                    results = mutableListOf()
                                    val arr = result.data ?: return@launch
                                    for (i in 0 until arr.length()) {
                                        val obj = arr.getJSONObject(i)
                                        results = results + GroupSearchResult(
                                            id = obj.optLong("id"), name = obj.optString("name"),
                                            displayId = obj.optLong("display_id"), signature = obj.optString("signature", ""))
                                    }
                                } else { Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show() }
                            }
                        }.padding(horizontal = 12.dp, vertical = 10.dp))
                }
                // 搜索结果
                if (isSearching) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("搜索中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                    }
                } else if (hasSearched && results.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("未找到相关群聊", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        items(results) { group ->
                            Box(Modifier.fillMaxWidth().height(60.dp).padding(vertical = 8.dp)
                                .clickable { selectedGroup = group; showInfoPanel = true; hideKeyboard() },
                                contentAlignment = Alignment.CenterStart) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    GroupAvatar(internalGroupId = group.id, groupName = group.name, size = 48.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(highlightText(group.name, keyword), fontSize = 15.sp, color = Color(0xFF1F2937))
                                        Text("群ID: ${group.displayId}", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                                    }
                                    Text("查看", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ===== 群详情面板（从右侧滑入，复用独立 GroupDetailScreen） =====
        AnimatedVisibility(
            visible = showInfoPanel && selectedGroup != null,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            selectedGroup?.let { group ->
                GroupDetailScreen(
                    groupId = group.id,
                    groupName = group.name,
                    groupDisplayId = group.displayId,
                    groupSignature = group.signature,
                    fromShareCard = initialGroupId > 0,
                    onBack = { showInfoPanel = false },
                    onOpenGroupChat = onOpenGroupChat,
                    onDismissSelf = onDismiss
                )
            }
        }
    }
}

/** 构建带关键字蓝高亮的 AnnotatedString */
private fun highlightText(text: String, keyword: String): androidx.compose.ui.text.AnnotatedString {
    if (keyword.isBlank()) return buildAnnotatedString { append(text) }
    return buildAnnotatedString {
        var start = 0
        while (true) {
            val index = text.indexOf(keyword, start, ignoreCase = true)
            if (index < 0) { append(text.substring(start)); break }
            if (index > start) append(text.substring(start, index))
            withStyle(SpanStyle(color = Color(0xFF1E40AF), fontWeight = FontWeight.Bold)) { append(text.substring(index, index + keyword.length)) }
            start = index + keyword.length
        }
    }
}
