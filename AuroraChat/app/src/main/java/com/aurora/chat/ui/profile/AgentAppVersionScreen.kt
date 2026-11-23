package com.aurora.chat.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

private data class AgentAppVersionItem(
    val id: Long,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val forceUpdate: Boolean,
    val updateMessage: String,
    val isActive: Boolean,
    val createdAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject): AgentAppVersionItem = AgentAppVersionItem(
            id = o.optLong("id"),
            versionCode = o.optInt("versionCode"),
            versionName = o.optString("versionName"),
            apkUrl = o.optString("apkUrl"),
            forceUpdate = o.optBoolean("forceUpdate"),
            updateMessage = o.optString("updateMessage"),
            isActive = o.optBoolean("isActive"),
            createdAt = o.optLong("createdAt"),
        )
    }
}

private fun fmt(ts: Long): String =
    if (ts <= 0) "-" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts * 1000))

@Composable
fun AgentAppVersionScreen(onBack: () -> Unit = {}, showBack: Boolean = true) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<AgentAppVersionItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showPush by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) {
        toastMsg = msg
        scope.launch { kotlinx.coroutines.delay(2200); toastMsg = null }
    }
    fun refresh() {
        loading = true
        scope.launch {
            val res = AuroraApi.agentAppListVersions()
            list = if (res.success) res.data?.map { AgentAppVersionItem.fromJson(it) } ?: emptyList() else emptyList()
            if (!res.success) toast("加载失败: ${res.message}")
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                Box(
                    Modifier.size(28.dp).clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1E40AF))
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("Agent 应用版本管理", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { showPush = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("推送更新", color = Color.White, fontSize = 14.sp)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = com.aurora.chat.ui.theme.appDivider())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text("当前生效版本", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
            Spacer(Modifier.height(8.dp))
            val active = list.firstOrNull { it.isActive }
            if (active == null) {
                Text("暂无生效版本", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
            } else {
                Column(
                    Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    Text("${active.versionName}（code ${active.versionCode}）", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
                    Spacer(Modifier.height(4.dp))
                    Text("下载地址: ${active.apkUrl}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (active.forceUpdate) {
                        Spacer(Modifier.height(4.dp))
                        Text("强制更新已开启", fontSize = 12.sp, color = Color(0xFFDC2626))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (loading) "版本历史（加载中…）" else "版本历史（${list.size}）",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29),
            )
            Spacer(Modifier.height(8.dp))
            list.forEach { item ->
                Column(
                    Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.versionName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D29))
                            Spacer(Modifier.height(3.dp))
                            Text("code ${item.versionCode} · ${fmt(item.createdAt)}", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        }
                        if (item.isActive) {
                            Text("生效中", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF16A34A))
                        } else if (item.forceUpdate) {
                            Text("强制", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFDC2626))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(item.apkUrl, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (!item.isActive) {
                            Text(
                                "设为当前",
                                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        val r = AuroraApi.agentAppActivateVersion(item.id)
                                        if (r.success) { toast("已切换为当前版本"); refresh() } else toast("切换失败: ${r.message}")
                                    }
                                }.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            "删除",
                            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextHint(),
                            modifier = Modifier.clickable {
                                scope.launch {
                                    val r = AuroraApi.agentAppDeleteVersion(item.id)
                                    if (r.success) { toast("已删除"); refresh() } else toast("删除失败: ${r.message}")
                                }
                            }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (showPush) {
        PushVersionDialog(
            onDismiss = { showPush = false },
            onPush = { code, name, url, force, msg ->
                scope.launch {
                    val r = AuroraApi.agentAppPushVersion(code, name, url, force, msg)
                    if (r.success) { showPush = false; toast("已推送新版本"); refresh() } else toast("推送失败: ${r.message}")
                }
            },
        )
    }
    if (toastMsg != null) {
        Box(Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
            Text(toastMsg!!, fontSize = 12.sp, color = Color.White, modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun PushVersionDialog(
    onDismiss: () -> Unit,
    onPush: (versionCode: Int, versionName: String, apkUrl: String, forceUpdate: Boolean, updateMessage: String) -> Unit,
) {
    var versionCode by remember { mutableStateOf("1") }
    var versionName by remember { mutableStateOf("1.0") }
    var apkUrl by remember { mutableStateOf("https://www.example.com/backend/data/tools/AuroraAgent.apk") }
    var forceUpdate by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("发现新版本，请更新后继续使用。") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("推送新版本", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = versionCode, onValueChange = { versionCode = it.filter { c -> c.isDigit() } }, label = { Text("版本号 versionCode（整数）*") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = versionName, onValueChange = { versionName = it }, label = { Text("版本名 versionName *") }, placeholder = { Text("例如 1.0") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = apkUrl, onValueChange = { apkUrl = it }, label = { Text("APK 下载地址（默认已填，可改）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = forceUpdate, onCheckedChange = { forceUpdate = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1E40AF)))
                    Text("强制更新（用户必须更新才能继续使用）", fontSize = 13.sp, color = Color(0xFF1A1D29))
                }
                OutlinedTextField(value = updateMessage, onValueChange = { updateMessage = it }, label = { Text("更新说明") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (error != null) Text(error!!, fontSize = 12.sp, color = Color(0xFFF04438))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val code = versionCode.toIntOrNull()
                if (code == null || code <= 0) { error = "请填写有效的 versionCode"; return@TextButton }
                if (versionName.isBlank()) { error = "请填写 versionName"; return@TextButton }
                onPush(code, versionName.trim(), apkUrl.trim(), forceUpdate, updateMessage.trim())
            }) { Text("推送") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
