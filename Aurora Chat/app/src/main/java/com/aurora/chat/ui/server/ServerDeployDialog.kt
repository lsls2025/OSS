package com.aurora.chat.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ServerDeployDialog(
    onDismiss: () -> Unit,
    onProjectChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var projects by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("node") }
    var errorMsg by remember { mutableStateOf("") }
    var logPid by remember { mutableStateOf<Long?>(null) }
    var logText by remember { mutableStateOf("") }
    var deploying by remember { mutableStateOf(false) }

    var showFileBrowserPid by remember { mutableStateOf<Long?>(null) }

    // 跟踪某个项目正在执行什么操作：null=无, "start"/"stop"/"delete"
    var operatingAction by remember { mutableStateOf<Pair<Long, String>?>(null) }

    val types = listOf("node" to "Node.js", "python" to "Python", "go" to "Go", "nginx" to "Nginx", "java" to "Java")
    val typeMap = types.toMap()

    fun load() {
        scope.launch { isLoading = true; errorMsg = ""
            val r = AuroraApi.getSandboxList()
            projects = if (r.success && r.data != null) {
                val lst = mutableListOf<JSONObject>()
                for (i in 0 until r.data.length()) lst.add(r.data.getJSONObject(i))
                lst
            } else { if (!r.success) errorMsg = r.message; emptyList() }; isLoading = false }
    }

    // 轮询等待某个项目不再处于 starting/deploying 状态
    fun pollUntilReady(pid: Long) {
        scope.launch {
            var attempts = 0
            while (attempts < 40) { // 最多等 2 分钟
                kotlinx.coroutines.delay(3000)
                val listR = AuroraApi.getSandboxList()
                if (listR.success && listR.data != null) {
                    for (i in 0 until listR.data.length()) {
                        val obj = listR.data.getJSONObject(i)
                        if (obj.optLong("id", 0) == pid) {
                            val s = obj.optString("status")
                            if (s != "starting" && s != "deploying") {
                                load()
                                return@launch
                            }
                            break
                        }
                    }
                }
                attempts++
            }
            load() // 超时后也刷新一次
        }
    }

    LaunchedEffect(Unit) { load() }

    // 部署中阻止返回
    if (deploying) {
        BackHandler(enabled = true) { android.widget.Toast.makeText(ctx, "部署中，请稍候...", android.widget.Toast.LENGTH_SHORT).show() }
    }

    Dialog(onDismissRequest = { if (!deploying) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !deploying)) {
        Box(Modifier.fillMaxSize().background(Color.White)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF3F4F6))
                        .clickable(enabled = !deploying, indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }, contentAlignment = Alignment.Center) {
                        Text("←", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("部署管理", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (deploying) Color(0xFF9CA3AF) else Color(0xFF7C3AED))
                        .clickable(enabled = !deploying, indication = null, interactionSource = remember { MutableInteractionSource() }) { showCreate = true; name = ""; type = "node" }.padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text("+ 新建", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (showCreate) {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF5F3FF)).padding(16.dp)) {
                            Column {
                                Text("新建部署", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)); Spacer(Modifier.height(10.dp))
                                androidx.compose.foundation.text.BasicTextField(value = name, onValueChange = { name = it },
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).padding(12.dp),
                                    decorationBox = { itf -> Box { if (name.isEmpty()) Text("项目名称", fontSize = 13.sp, color = Color(0xFF9CA3AF)); itf() } })
                                Spacer(Modifier.height(10.dp))
                                Row { types.forEach { (k, l) -> val sel = type == k
                                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (sel) Color(0xFF7C3AED) else Color.White).clickable(enabled = !deploying, indication = null, interactionSource = remember { MutableInteractionSource() }) { type = k }.padding(horizontal = 10.dp, vertical = 5.dp)) { Text(l, fontSize = 11.sp, color = if (sel) Color.White else Color(0xFF4B5563), fontWeight = FontWeight.Medium) }
                                    Spacer(Modifier.width(6.dp)) } }
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE5E7EB)).clickable(enabled = !deploying, indication = null, interactionSource = remember { MutableInteractionSource() }) { showCreate = false; name = "" }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Text("取消", fontSize = 13.sp, color = Color(0xFF6B7280), fontWeight = FontWeight.Medium) }
                                    Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (deploying) Color(0xFF9CA3AF) else Color(0xFF7C3AED)).clickable(enabled = name.isNotBlank() && !deploying, indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        deploying = true
                                        scope.launch {
                                            try {
                                                val r = AuroraApi.deploySandbox(name, type, null, "")
                                                if (r.success) { showCreate = false; name = ""; android.widget.Toast.makeText(ctx, "部署成功", android.widget.Toast.LENGTH_SHORT).show(); load() }
                                                else { android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show(); load() }
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(ctx, if (e.message?.contains("timeout") == true) "部署超时，请重试" else (e.message ?: "部署失败"), android.widget.Toast.LENGTH_SHORT).show()
                                                load()
                                            } finally { deploying = false }
                                        }
                                    }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Text(if (deploying) "部署中..." else "部署", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium) }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    val maxProjects = 5
                    Text("我的项目（${projects.size}/$maxProjects）", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)); Spacer(Modifier.height(10.dp))
                    if (isLoading && projects.isEmpty()) { Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { Text("加载中...", fontSize = 13.sp, color = Color(0xFF9CA3AF)) } }
                    else if (projects.isEmpty()) { Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { Text("暂无项目", fontSize = 13.sp, color = Color(0xFF9CA3AF)) } }
                    else {
                        projects.forEach { p ->
                            val pid = p.optLong("id", 0); val pname = p.optString("name", "未命名"); val rawType = p.optString("project_type", "未知")
                            val pstatus = p.optString("status", "stopped"); val pport = p.optInt("port", 0)
                            val isRunning = pstatus == "running"
                            val isStarting = pstatus == "starting"
                            val isDeploying = pstatus == "deploying"
                            val displayType = typeMap[rawType] ?: rawType
                            val isThisOperating = operatingAction?.first == pid
                            val isPending = isStarting || isDeploying
                            val btnEnabled = !isThisOperating && !isPending
                            val btnAlpha = if (isThisOperating || isPending) 0.5f else 1f
                            val statusText = when (pstatus) {
                                "running" -> "运行中"
                                "starting" -> "启动中"
                                "deploying" -> "部署中"
                                "stopped" -> "已停止"
                                else -> pstatus
                            }
                            val sc = when (pstatus) {
                                "running" -> Color(0xFF059669)
                                "starting" -> Color(0xFF3B82F6)
                                "deploying" -> Color(0xFF7C3AED)
                                else -> Color(0xFF9CA3AF)
                            }
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF9FAFB)).padding(14.dp)) {
                                Column {
                                    Box(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        showFileBrowserPid = pid
                                    }) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(pname, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937), modifier = Modifier.weight(1f))
                                                Text(statusText, fontSize = 11.sp, color = sc, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(sc.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 2.dp))
                                            }
                                            Spacer(Modifier.height(4.dp)); Text("$displayType | 端口: $pport", fontSize = 12.sp, color = Color(0xFF6B7280))
                                            if (isRunning) { Spacer(Modifier.height(2.dp)); Text("http://www.YOUR_SERVER_DOMAIN:$pport", fontSize = 11.sp, color = Color(0xFF7C3AED)) }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp)); Row {
                                        if (isRunning) {
                                            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (btnEnabled) Color(0xFFFEF3C2) else Color(0xFFFEF3C2).copy(alpha = btnAlpha)).clickable(enabled = btnEnabled, indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                                operatingAction = pid to "stop"; scope.launch {
                                                    AuroraApi.stopSandbox(pid)
                                                    operatingAction = null; load()
                                                }
                                            }.padding(horizontal = 10.dp, vertical = 4.dp)) { Text(if (isThisOperating && operatingAction?.second == "stop") "停止中..." else "停止", fontSize = 11.sp, color = Color(0xFF92400E)) }; Spacer(Modifier.width(6.dp))
                                        } else {
                                            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (btnEnabled) Color(0xFFDCFCE7) else Color(0xFFDCFCE7).copy(alpha = btnAlpha)).clickable(enabled = btnEnabled, indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                                operatingAction = pid to "start"
                                                scope.launch {
                                                    AuroraApi.startSandbox(pid)
                                                    operatingAction = null; load()
                                                    pollUntilReady(pid)
                                                }
                                            }.padding(horizontal = 10.dp, vertical = 4.dp)) { Text(if (isThisOperating && operatingAction?.second == "start") "启动中..." else "启动", fontSize = 11.sp, color = Color(0xFF15803D)) }; Spacer(Modifier.width(6.dp))
                                        }
                                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE0F2FE)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { scope.launch {
                                            val r = AuroraApi.getSandboxLogs(pid)
                                            logText = if (r.success && r.data != null) r.data.optString("logs", "无日志") else (r.message)
                                            logPid = pid
                                        } }.padding(horizontal = 10.dp, vertical = 4.dp)) { Text("日志", fontSize = 11.sp, color = Color(0xFF0369A1)) }
                                        Spacer(Modifier.width(6.dp))
                                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (btnEnabled) Color(0xFFFEE2E2) else Color(0xFFFEE2E2).copy(alpha = btnAlpha)).clickable(enabled = btnEnabled, indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                            operatingAction = pid to "delete"; scope.launch {
                                                AuroraApi.deleteSandbox(pid)
                                                operatingAction = null; load()
                                            }
                                        }.padding(horizontal = 10.dp, vertical = 4.dp)) { Text(if (isThisOperating && operatingAction?.second == "delete") "删除中..." else "删除", fontSize = 11.sp, color = Color(0xFF991B1B)) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (errorMsg.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(errorMsg, fontSize = 11.sp, color = Color(0xFFEF4444)) }
                }
            }
            if (logPid != null) {
                Dialog(onDismissRequest = { logPid = null }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true)) {
                    Box(Modifier.fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(20.dp)) {
                        Column {
                            Text("运行日志", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)); Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1F2937)).padding(12.dp)) { Text(logText.ifEmpty { "暂无日志" }, fontSize = 11.sp, color = Color(0xFF9CA3AF), lineHeight = 16.sp) }
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E40AF)).clickable { logPid = null }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Text("关闭", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
            }

            if (showFileBrowserPid != null) {
                val browserPid = showFileBrowserPid!!
                val browserName = projects.find { it.optLong("id", 0) == browserPid }?.optString("name", "未命名") ?: "未命名"
                SandboxFileBrowserDialog(
                    projectId = browserPid,
                    onDismiss = { showFileBrowserPid = null }
                )
            }
        }
    }
}
