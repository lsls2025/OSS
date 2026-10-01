package com.aurora.chat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.HttpClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private val MkBlue = Color(0xFF2563EB)
private val MkGreen = Color(0xFF059669)

/**
 * 插件制作器：从插件市场右上角 + 号进入的全新全屏界面。
 * 用户零代码制作一个「HTTP 工具插件」：声明名称/描述/请求方法/URL 模板/参数/响应取值路径,
 * 可当场填测试参数运行调试看结果;做完一键上传到插件市场,进入开发者审核。
 * 插件本体是一段 JSON(声明式),不涉及任何源码与编译。
 */
@Composable
fun PluginMakerScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val noRipple = remember { MutableInteractionSource() }

    fun toast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ── 基本信息 ──
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var description by remember { mutableStateOf(TextFieldValue("")) }
    // ── 接口定义 ──
    var method by remember { mutableStateOf("GET") }
    var urlTemplate by remember { mutableStateOf(TextFieldValue("")) }
    var responsePath by remember { mutableStateOf(TextFieldValue("")) }
    // ── 参数定义:每项 [参数名, 显示名, 默认值, 必填] ──
    var params by remember { mutableStateOf(listOf(listOf("", "", "", "false"))) }
    // ── 调试 ──
    var testValues by remember { mutableStateOf(listOf<String>()) }
    var debugRunning by remember { mutableStateOf(false) }
    var debugRaw by remember { mutableStateOf("") }
    var debugMapped by remember { mutableStateOf("") }
    // ── 上传 ──
    var uploading by remember { mutableStateOf(false) }

    BackHandler { if (!uploading) onBack() }

    // 参数名只允许字母/数字/下划线(要进 URL 模板与 JSON 字段)
    fun validParamName(n: String) = n.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,31}"))

    /** 生成插件 JSON 本体 */
    fun buildPluginJson(): String {
        val root = JSONObject()
            .put("type", "http_tool")
            .put("method", method)
            .put("url", urlTemplate.text.trim())
        if (responsePath.text.trim().isNotEmpty()) root.put("responsePath", responsePath.text.trim())
        val arr = JSONArray()
        params.forEach { p ->
            val o = JSONObject().put("name", p[0].trim()).put("label", p[1].trim().ifEmpty { p[0].trim() })
                .put("required", p[3] == "true")
            if (p[2].trim().isNotEmpty()) o.put("default", p[2].trim())
            arr.put(o)
        }
        root.put("params", arr)
        return root.toString()
    }

    /** 用测试值替换 URL 模板中的 {参数名} */
    fun substituteUrl(json: JSONObject, values: Map<String, String>): String {
        var u = json.optString("url")
        val arr = json.optJSONArray("params") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val key = p.optString("name")
            val raw = values[key] ?: p.optString("default", "")
            u = u.replace("{${key}}", URLEncoder.encode(raw, "UTF-8"))
        }
        return u
    }

    /** 按点分路径从响应 JSON 里取值(data.list / data.0.name) */
    fun extractPath(text: String, path: String): String {
        if (path.isBlank()) return ""
        return try {
            var cur: Any = JSONObject(text)
            for (seg in path.split('.', '/').filter { it.isNotBlank() }) {
                cur = when (cur) {
                    is JSONObject -> cur.opt(seg) ?: return ""
                    is JSONArray -> cur.opt(seg.toIntOrNull() ?: return "") ?: return ""
                    else -> return ""
                }
            }
            when (cur) {
                is JSONObject, is JSONArray -> cur.toString()
                else -> cur.toString()
            }
        } catch (_: Exception) { "" }
    }

    fun runDebug() {
        val urlTpl = urlTemplate.text.trim()
        if (!urlTpl.startsWith("http://") && !urlTpl.startsWith("https://")) { toast("URL 必须以 http:// 或 https:// 开头"); return }
        val values = params.mapIndexed { i, p -> p[0].trim() to (testValues.getOrNull(i) ?: p[2]) }.toMap()
        params.forEachIndexed { i, p ->
            if (p[3] == "true" && values[p[0].trim()].isNullOrBlank()) { toast("参数 ${p[0].trim()} 为必填"); return }
        }
        val json = try { JSONObject(buildPluginJson()) } catch (_: Exception) { toast("插件定义异常"); return }
        debugRunning = true
        debugRaw = ""; debugMapped = ""
        scope.launch {
            val out = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val finalUrl = substituteUrl(json, values)
                    val resp = if (method == "POST") {
                        val bodyObj = JSONObject()
                        params.forEach { p -> bodyObj.put(p[0].trim(), values[p[0].trim()] ?: p[2]) }
                        HttpClient.jsonRequestAllowError("POST", finalUrl, bodyObj.toString())
                    } else {
                        HttpClient.jsonRequestAllowError("GET", finalUrl)
                    }
                    resp
                } catch (e: Exception) {
                    "请求失败: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            debugRunning = false
            debugRaw = out.take(4000)
            debugMapped = if (responsePath.text.trim().isNotEmpty()) extractPath(out, responsePath.text.trim()) else ""
        }
    }

    fun upload() {
        val n = name.text.trim()
        if (n.isEmpty()) { toast("先给插件起个名字"); return }
        if (n.length > 40) { toast("名称不超过 40 字"); return }
        if (description.text.trim().isEmpty()) { toast("写一句描述,审核的人需要知道它干什么"); return }
        val urlTpl = urlTemplate.text.trim()
        if (!urlTpl.startsWith("http://") && !urlTpl.startsWith("https://")) { toast("URL 必须以 http:// 或 https:// 开头"); return }
        params.forEach { p ->
            val pn = p[0].trim()
            if (pn.isNotEmpty() && !validParamName(pn)) { toast("参数名 $pn 非法(字母开头,字母数字下划线)"); return }
        }
        uploading = true
        scope.launch {
            val res = AuroraApi.pluginSubmit(n, description.text.trim(), buildPluginJson())
            uploading = false
            if (res.success) {
                toast("已提交,等待开发者审核")
                onBack()
            } else {
                toast(res.message)
            }
        }
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
                    .clickable(interactionSource = noRipple, indication = null) { if (!uploading) onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937)) }
            Spacer(Modifier.width(6.dp))
            Text("制作插件", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937), modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // ── 基本信息 ──
            MkSectionCard("基本信息") {
                MkField("插件名称", name, { name = it }, "例:手机号归属地查询")
                Spacer(Modifier.height(10.dp))
                MkField("插件描述", description, { description = it }, "这个工具能干什么(300 字内)", minLines = 2)
            }
            Spacer(Modifier.height(12.dp))

            // ── 接口定义 ──
            MkSectionCard("接口定义") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("请求方法", fontSize = 12.sp, color = Color(0xFF6B7280), modifier = Modifier.width(64.dp))
                    listOf("GET", "POST").forEach { m ->
                        val selected = method == m
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (selected) MkBlue else Color(0xFFEFF3F8))
                                .clickable(interactionSource = noRipple, indication = null) { method = m }
                                .padding(horizontal = 16.dp, vertical = 7.dp)
                        ) { Text(m, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Color(0xFF6B7280)) }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                MkField("URL 模板", urlTemplate, { urlTemplate = it }, "https://api.example.com/query?kw={关键词}", singleLine = true)
                Spacer(Modifier.height(10.dp))
                MkField("响应取值路径(可选)", responsePath, { responsePath = it }, "例:data.result,留空=显示原始响应", singleLine = true)
                Spacer(Modifier.height(4.dp))
                Text("URL 里的 {参数名} 会在调用时被替换成实际参数值", fontSize = 11.sp, color = Color(0xFF9CA3AF))
            }
            Spacer(Modifier.height(12.dp))

            // ── 参数定义 ──
            MkSectionCard("参数(${params.size})") {
                if (params.isEmpty()) Text("无参数。URL 里写 {xxx} 后在这里登记对应参数", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                params.forEachIndexed { idx, p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        MkSmallField("参数名", p[0], { v -> params = params.toMutableList().also { it[idx] = listOf(v, p[1], p[2], p[3]) } }, Modifier.weight(1.1f))
                        Spacer(Modifier.width(6.dp))
                        MkSmallField("显示名", p[1], { v -> params = params.toMutableList().also { it[idx] = listOf(p[0], v, p[2], p[3]) } }, Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        MkSmallField("默认值", p[2], { v -> params = params.toMutableList().also { it[idx] = listOf(p[0], p[1], v, p[3]) } }, Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("必填", fontSize = 10.sp, color = Color(0xFF6B7280))
                            Checkbox(
                                checked = p[3] == "true",
                                onCheckedChange = { c -> params = params.toMutableList().also { it[idx] = listOf(p[0], p[1], p[2], if (c) "true" else "false") } },
                                colors = CheckboxDefaults.colors(checkedColor = MkBlue),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Box(
                            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                                .clickable(interactionSource = noRipple, indication = null) { params = params.filterIndexed { i, _ -> i != idx } },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Close, "删除参数", tint = Color(0xFFDC2626), modifier = Modifier.size(18.dp)) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFEFF3F8))
                        .clickable(interactionSource = noRipple, indication = null) {
                            params = params.toMutableList().apply { add(listOf("", "", "", "false")) }
                            testValues = testValues + ""
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, tint = MkBlue, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加参数", fontSize = 13.sp, color = MkBlue, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── 调试 ──
            MkSectionCard("调试") {
                if (params.isEmpty()) {
                    Text("没有参数,直接运行测试即可", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                } else {
                    params.forEachIndexed { idx, p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(p[0].trim().ifEmpty { "参数${idx + 1}" }, fontSize = 12.sp, color = Color(0xFF374151), modifier = Modifier.width(84.dp))
                            MkTestValue(
                                value = testValues.getOrNull(idx) ?: "",
                                onChange = { v -> testValues = testValues.toMutableList().also { while (it.size <= idx) it.add(""); it[idx] = v } }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(if (debugRunning) Color(0xFF93C5FD) else MkGreen)
                        .clickable(enabled = !debugRunning, interactionSource = noRipple, indication = null) { runDebug() }
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text(if (debugRunning) "请求中..." else "运行测试", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                if (debugRaw.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("原始响应", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F172A)).padding(10.dp)) {
                        Text(debugRaw, fontSize = 11.sp, color = Color(0xFFA5F3FC), lineHeight = 16.sp)
                    }
                    if (debugMapped.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("按「响应取值路径」取到的结果", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFECFDF5)).padding(10.dp)) {
                            Text(debugMapped, fontSize = 12.sp, color = MkGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── 上传 ──
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (uploading) Color(0xFF93C5FD) else MkBlue)
                    .clickable(enabled = !uploading, interactionSource = noRipple, indication = null) { upload() }
                    .padding(vertical = 13.dp), contentAlignment = Alignment.Center
            ) {
                Text(if (uploading) "上传中..." else "上传到插件市场", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.height(6.dp))
            Text("上传后进入待审核,由开发者审核通过后上架", fontSize = 11.sp, color = Color(0xFF9CA3AF), modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 制作器分区卡片:灰底上的白色圆角块 + 区块标题 */
@Composable
private fun MkSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** 普通输入框:标题 + 圆角边框输入区 */
@Composable
private fun MkField(label: String, value: TextFieldValue, onChange: (TextFieldValue) -> Unit, hint: String, minLines: Int = 1, singleLine: Boolean = false) {
    Text(label, fontSize = 12.sp, color = Color(0xFF6B7280))
    Spacer(Modifier.height(5.dp))
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, fontSize = 12.sp, color = Color(0xFFC4CBD4), maxLines = 1) },
        minLines = minLines,
        singleLine = singleLine,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color(0xFF1F2937)),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 参数行内的小输入框(带内嵌标题) */
@Composable
private fun MkSmallField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 10.sp, color = Color(0xFF9CA3AF))
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color(0xFF1F2937)),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 调试区的测试值输入框 */
@Composable
private fun MkTestValue(value: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("测试值", fontSize = 11.sp, color = Color(0xFFC4CBD4)) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color(0xFF1F2937)),
        modifier = Modifier.fillMaxWidth()
    )
}
