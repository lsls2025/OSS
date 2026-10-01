package com.aurora.chat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private val MyBlue = Color(0xFF1E40AF)
private val MyAmber = Color(0xFFD97706)

/** 内置工具条目(「可用的」Tab 硬编码内容):AI 已自带、无需安装即可调用的能力 */
private data class BuiltinTool(val name: String, val desc: String, val color: Color, val bg: Color)

private val builtinTools = listOf(
    // ═══ 文件与工作区 ═══
    BuiltinTool("write_file", "在 AI 工作区新建/写入文件", Color(0xFF7C3AED), Color(0xFFF5F3FF)),
    BuiltinTool("read_file", "读取工作区文件内容", Color(0xFF7C3AED), Color(0xFFF5F3FF)),
    BuiltinTool("list_files", "列出工作区目录内容", Color(0xFF7C3AED), Color(0xFFF5F3FF)),
    BuiltinTool("append_file", "向文件末尾追加内容", Color(0xFF7C3AED), Color(0xFFF5F3FF)),
    BuiltinTool("delete_file", "删除工作区文件", Color(0xFFDC2626), Color(0xFFFEF2F2)),
    BuiltinTool("rename_file", "重命名/移动工作区文件", Color(0xFF7C3AED), Color(0xFFF5F3FF)),
    BuiltinTool("find_files", "按名称/通配符查找工作区文件", Color(0xFF7C3AED), Color(0xFFF5F3FF)),
    BuiltinTool("download_file", "下载网络文件到工作区", Color(0xFFD97706), Color(0xFFFFFBEB)),
    BuiltinTool("get_file", "把工作区文件以卡片形式发给用户", Color(0xFF7C3AED), Color(0xFFF5F3FF)),
    // ═══ 媒体与截图 ═══
    BuiltinTool("generate_media", "AI 生成图片/视频并直接发送", Color(0xFFDB2777), Color(0xFFFDF2F8)),
    BuiltinTool("capture_screen", "截屏当前手机画面并直接发给用户", Color(0xFF0D9488), Color(0xFFF0FDFA)),
    BuiltinTool("take_screenshot", "截屏保存到 AI 工作区,供后续查看处理", Color(0xFF0D9488), Color(0xFFF0FDFA)),
    BuiltinTool("install_apk", "安装 APK 文件", Color(0xFF16A34A), Color(0xFFF0FDF4)),
    // ═══ 网络与终端 ═══
    BuiltinTool("fetch_webpage", "抓取网页正文内容", Color(0xFF0D9488), Color(0xFFF0FDFA)),
    BuiltinTool("search_web", "联网搜索获取最新信息", Color(0xFF0D9488), Color(0xFFF0FDFA)),
    BuiltinTool("http_request", "发起自定义 HTTP 请求(GET/POST)", Color(0xFF0D9488), Color(0xFFF0FDFA)),
    BuiltinTool("ping_host", "Ping 网络主机,检测连通性", Color(0xFF0D9488), Color(0xFFF0FDFA)),
    BuiltinTool("process_list", "列出当前进程列表(root 可用)", Color(0xFF374151), Color(0xFFF3F4F6)),
    BuiltinTool("shell_exec", "在沙盒内执行 Shell 命令", Color(0xFF374151), Color(0xFFF3F4F6)),
    BuiltinTool("run_adb", "执行 ADB 命令(设备已 root 时可做系统级操作)", Color(0xFFDB2777), Color(0xFFFDF2F8)),
    BuiltinTool("git_command", "执行 git 命令(工作区内版本管理)", Color(0xFFF05032), Color(0xFFFFF7ED)),
    BuiltinTool("diff_text", "两段文本差异对比", Color(0xFFF05032), Color(0xFFFFF7ED)),
    // ═══ 脚本执行 ═══
    BuiltinTool("execute_python", "运行 Python 脚本做数据处理与计算", Color(0xFF2563EB), Color(0xFFEFF6FF)),
    BuiltinTool("execute_lua", "运行 Lua 脚本(轻量沙盒,适合批量/文件操作)", Color(0xFF1E40AF), Color(0xFFEFF6FF)),
    // ═══ 手机操控(无障碍) ═══
    BuiltinTool("phone_access", "引导开启无障碍服务", Color(0xFFDC2626), Color(0xFFFEF2F2)),
    BuiltinTool("phone_screen", "读取当前屏幕 UI 节点(无障碍)", Color(0xFFDC2626), Color(0xFFFEF2F2)),
    BuiltinTool("phone_tap", "点击屏幕元素或坐标(无障碍)", Color(0xFFDC2626), Color(0xFFFEF2F2)),
    BuiltinTool("phone_swipe", "滑动屏幕(无障碍)", Color(0xFFDC2626), Color(0xFFFEF2F2)),
    BuiltinTool("phone_type", "输入文本到当前焦点输入框(无障碍)", Color(0xFFDC2626), Color(0xFFFEF2F2)),
    BuiltinTool("phone_key", "模拟按键(返回/Home/通知栏等)", Color(0xFFDC2626), Color(0xFFFEF2F2)),
    // ═══ 应用与消息 ═══
    BuiltinTool("open_app", "打开手机上的任意应用", Color(0xFF059669), Color(0xFFECFDF5)),
    BuiltinTool("exit_app", "退出应用(可选仅退后台或彻底杀进程)", Color(0xFF059669), Color(0xFFECFDF5)),
    BuiltinTool("uninstall_app", "卸载手机上的应用(带确认与校验)", Color(0xFFDC2626), Color(0xFFFEF2F2)),
    BuiltinTool("send_message", "给指定好友发送消息", Color(0xFF1E40AF), Color(0xFFEFF6FF)),
    BuiltinTool("recall_message", "撤回已发送的消息", Color(0xFF1E40AF), Color(0xFFEFF6FF)),
    BuiltinTool("list_recent_messages", "读取最近的聊天消息", Color(0xFF1E40AF), Color(0xFFEFF6FF)),
    BuiltinTool("send_friend_request", "发送好友请求", Color(0xFF1E40AF), Color(0xFFEFF6FF)),
    BuiltinTool("create_community_post", "在社区发布帖子", Color(0xFF1E40AF), Color(0xFFEFF6FF)),
    BuiltinTool("list_my_contacts", "列出我的好友列表", Color(0xFF1E40AF), Color(0xFFEFF6FF)),
    BuiltinTool("list_my_groups", "列出我所在的群组", Color(0xFF1E40AF), Color(0xFFEFF6FF)),
    BuiltinTool("switch_tab", "切换主界面板块(聊天/我的/管理)", Color(0xFF1E40AF), Color(0xFFEFF6FF)),
    // ═══ 运行控制 ═══
    BuiltinTool("wait", "延迟等待一段时间(支持后台)", Color(0xFF374151), Color(0xFFF3F4F6)),
    BuiltinTool("keep_alive", "后台保活悬浮窗(长任务不被系统回收)", Color(0xFF374151), Color(0xFFF3F4F6)),
    BuiltinTool("ask_user", "向用户提问(结构化问答面板)", Color(0xFFF59E0B), Color(0xFFFFFBEB)),
    // ═══ 插件市场 ═══
    BuiltinTool("plugin_list", "列出可用插件", Color(0xFF8B5CF6), Color(0xFFF5F3FF)),
    BuiltinTool("plugin_submit", "提交新插件到市场", Color(0xFF8B5CF6), Color(0xFFF5F3FF)),
    BuiltinTool("plugin_call", "调用已安装插件", Color(0xFF8B5CF6), Color(0xFFF5F3FF)),
    // ═══ 代理/规划(多 Agent) ═══
    BuiltinTool("agent_plan", "生成执行计划(多步任务拆解)", Color(0xFF9333EA), Color(0xFFFAF5FF)),
    BuiltinTool("agent_fork", "分叉子 Agent 并行执行", Color(0xFF9333EA), Color(0xFFFAF5FF)),
    BuiltinTool("agent_review", "独立验收 Agent 工作成果", Color(0xFF9333EA), Color(0xFFFAF5FF)),
    // ═══ 数据处理 ═══
    BuiltinTool("json_process", "JSON 解析/格式化/取值", Color(0xFF4F46E5), Color(0xFFEEF2FF)),
    BuiltinTool("batch_loop", "批量循环执行同一工具 N 次(带序号占位符)", Color(0xFF0D9488), Color(0xFFF0FDFA)),
    BuiltinTool("regex_test", "正则表达式测试与匹配", Color(0xFF4F46E5), Color(0xFFEEF2FF)),
    BuiltinTool("encode_convert", "编码转换(Base64/URL/Unicode 等)", Color(0xFF4F46E5), Color(0xFFEEF2FF)),
    BuiltinTool("hash_digest", "计算哈希(MD5/SHA 系列)", Color(0xFF4F46E5), Color(0xFFEEF2FF)),
    BuiltinTool("timestamp_convert", "时间戳与日期互转", Color(0xFF4F46E5), Color(0xFFEEF2FF)),
    BuiltinTool("size_convert", "单位换算(字节/货币等)", Color(0xFF4F46E5), Color(0xFFEEF2FF)),
    BuiltinTool("text_stats", "文本统计(字数/行数/词频)", Color(0xFF4F46E5), Color(0xFFEEF2FF))
)

/**
 * 我的插件:插件市场右上角「我的」图标进入的全屏面板(右侧滑入,模板同插件市场)。
 * 两个 Tab:
 * - 我创建的:当前账号提交过的全部插件(含 AI 以创作模式代做的),带审核状态;
 * - 可用的:内置硬编码工具(AI 自带能力) + 社区已上架插件(含我自己已上架的)。
 */
@Composable
fun PluginMyScreen(userId: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val noRipple = remember { MutableInteractionSource() }
    var tab by remember { mutableStateOf(0) } // 0=我创建的 1=可用的
    var mine by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var market by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long>(-1L) }
    var deleteTarget by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf(false) }

    fun doDelete() {
        val target = deleteTarget ?: return
        deleting = true
        scope.launch {
            val res = AuroraApi.pluginDelete(target.optLong("id"))
            deleting = false
            deleteTarget = null
            if (res.success) {
                mine = mine.filter { it.optLong("id") != target.optLong("id") }
                market = market.filter { it.optLong("id") != target.optLong("id") }
            }
        }
    }

    fun refresh() {
        loading = true
        scope.launch {
            val m = AuroraApi.pluginMine()
            // 可用的 = 已上架 + 本人自建的全部(自建无需审核即可自用),由后端 mine=1 参数合并返回
            val mk = AuroraApi.pluginMarket(mine = true)
            mine = if (m.success) m.data ?: emptyList() else mine
            market = if (mk.success) mk.data ?: emptyList() else market
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    BackHandler { onBack() }

    fun statusLabel(s: String): String = when (s) {
        "pending" -> "待审核"
        "approved" -> "已上架"
        "rejected" -> "未通过"
        "removed" -> "已下架"
        else -> s
    }
    fun statusColor(s: String): Color = when (s) {
        "pending" -> Color(0xFFD97706)
        "approved" -> Color(0xFF059669)
        "rejected" -> Color(0xFFDC2626)
        else -> Color(0xFF6B7280)
    }
    fun statusBg(s: String): Color = when (s) {
        "pending" -> Color(0xFFFFFBEB)
        "approved" -> Color(0xFFECFDF5)
        "rejected" -> Color(0xFFFEF2F2)
        else -> Color(0xFFF3F4F6)
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFFF3F4F6))
            .clickable(interactionSource = noRipple, indication = null) {}
    ) {
        // ═══ 顶栏(与插件市场同模板) ═══
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
            Text("我的插件", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937), modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

        // ═══ Tab 行 ═══
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("我创建的", "可用的").forEachIndexed { i, t ->
                val sel = tab == i
                Column(
                    Modifier.clickable(interactionSource = noRipple, indication = null) { tab = i }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        t, fontSize = 14.sp,
                        color = if (sel) MyBlue else Color(0xFF6B7280),
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.width(28.dp).height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (sel) MyBlue else Color.Transparent)
                    )
                }
                if (i == 0) Spacer(Modifier.width(28.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

        if (tab == 0) {
            // ═══ Tab 1:我创建的(含 AI 创建的;展示审核状态) ═══
            if (mine.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFFEF3C7)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.FolderSpecial, contentDescription = null, tint = MyAmber, modifier = Modifier.size(36.dp)) }
                        Spacer(Modifier.height(14.dp))
                        Text(if (loading) "加载中..." else "暂无创建记录", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151))
                        if (!loading) {
                            Spacer(Modifier.height(4.dp))
                            Text("点右上角 + 号制作,或让 AI 用创作模式帮你做", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    items(mine, key = { it.optLong("id") }) { p ->
                        val pid = p.optLong("id")
                        val st = p.optString("status")
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
                                .clickable(interactionSource = noRipple, indication = null) { expandedId = if (expandedId == pid) -1L else pid }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFFEF3C7)),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Filled.Extension, null, tint = MyAmber, modifier = Modifier.size(20.dp)) }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.optString("name"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                                    Text("创建于 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(p.optLong("createdAt") * 1000))}", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                                }
                                Text(
                                    statusLabel(st), fontSize = 11.sp, color = statusColor(st), fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(statusBg(st))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                                        .clickable(interactionSource = noRipple, indication = null) { if (!deleting) deleteTarget = p },
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Filled.DeleteOutline, contentDescription = "删除插件", tint = Color(0xFFDC2626), modifier = Modifier.size(18.dp)) }
                            }
                            if (p.optString("description").isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(p.optString("description"), fontSize = 12.sp, color = Color(0xFF6B7280), maxLines = 2)
                            }
                            if (st == "rejected" && p.optString("reviewReason").isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text("拒绝原因:${p.optString("reviewReason")}", fontSize = 12.sp, color = Color(0xFFDC2626))
                            }
                            if (expandedId == pid) {
                                Spacer(Modifier.height(10.dp))
                                Text("插件定义(JSON)", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F172A)).padding(10.dp)) {
                                    Text(p.optString("pluginJson"), fontSize = 11.sp, color = Color(0xFFA5F3FC), lineHeight = 16.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        } else {
            // ═══ Tab 2:可用的 = 内置工具(硬编码) + 社区已上架插件(含我自己已上架的) ═══
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                item {
                    Text("内置工具", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(10.dp))
                }
                items(builtinTools, key = { "b_${it.name}" }) { t ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(t.bg),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Build, null, tint = t.color, modifier = Modifier.size(17.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                            Text(t.desc, fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        }
                        Text(
                            "自带", fontSize = 10.sp, color = Color(0xFF6B7280),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFF3F4F6)).padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                item {
                    Spacer(Modifier.height(6.dp))
                    Text("社区插件(已上架 + 我创建的)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(10.dp))
                }
                if (market.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(if (loading) "加载中..." else "暂无已上架插件", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                } else {
                    items(market, key = { "m_${it.optLong("id")}" }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFFFEF3C7)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.Extension, null, tint = MyAmber, modifier = Modifier.size(17.dp)) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.optString("name"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                                Text(
                                    "作者:${p.optString("uploader")}" + (if (p.optLong("uploaderId") == userId) " (你)" else ""),
                                    fontSize = 11.sp, color = Color(0xFF9CA3AF)
                                )
                                if (p.optString("description").isNotEmpty()) {
                                    Text(p.optString("description"), fontSize = 11.sp, color = Color(0xFF6B7280), maxLines = 1)
                                }
                            }
                            if (p.optLong("uploaderId") == userId) {
                                if (p.optString("status") != "approved") {
                                    Text(
                                        statusLabel(p.optString("status")), fontSize = 10.sp,
                                        color = statusColor(p.optString("status")), fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(statusBg(p.optString("status"))).padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    "我的", fontSize = 10.sp, color = MyBlue, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFEFF6FF)).padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    // ═══ 删除确认弹窗 ═══
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            title = { Text("删除插件", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)) },
            text = {
                Text(
                    "确定删除「${target.optString("name")}」吗?\n\n" + when (target.optString("status")) {
                        "approved" -> "该插件已上架,删除后将立即从插件市场下架并移除全部记录,其他用户也无法再使用。"
                        else -> "删除后该记录与插件定义将永久消失,不可恢复。"
                    },
                    fontSize = 13.sp, color = Color(0xFF6B7280), lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { doDelete() }) {
                    Text(if (deleting) "删除中..." else "删除", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消", color = Color(0xFF6B7280))
                }
            },
            containerColor = Color.White
        )
    }
}
