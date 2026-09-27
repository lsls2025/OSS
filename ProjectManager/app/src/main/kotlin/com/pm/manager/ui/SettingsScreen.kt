package com.pm.manager.ui
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pm.manager.core.AppPrefs
import com.pm.manager.core.SortField
import com.pm.manager.core.ThemeMode
import com.pm.manager.core.ViewMode
import com.pm.manager.core.ToastBus
import com.pm.manager.terminal.SiteConfig
import com.pm.manager.terminal.SiteFiles
import com.pm.manager.terminal.ProxyApi
import com.pm.manager.terminal.ProxyOwnerState
import com.pm.manager.ui.components.AppButton
import com.pm.manager.ui.components.AppOutlinedField
import com.pm.manager.ui.components.ChoiceDialog
import com.pm.manager.ui.components.ChoiceItem
import com.pm.manager.ui.components.SectionHeader
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.pm.manager.ui.components.TopBar
import com.pm.manager.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onLogout: () -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val c = LocalAppColors.current
    val s by AppPrefs.settings.collectAsStateWithLifecycle()
    var themeMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var defaultPath by remember { mutableStateOf(s.defaultPath) }
    // 反向代理子页导航：""=设置主列表，"proxy"=反向代理，"admin"=管理后台
    var dest by remember { mutableStateOf("") }
    // 反向代理/管理后台入口：以服务端实时确认的 perm/is_owner 为准，
    // 不依赖本地可能过期的 perm 存档（否则旧卡存了 B，重装/换包后入口会一直不显示）。
    var ownerInfo by remember { mutableStateOf<ProxyOwnerState?>(null) }
    LaunchedEffect(Unit) {
        runCatching { ProxyApi.ownerState() }.onSuccess { ownerInfo = it }
    }

    if (dest == "proxy") { ProxyScreen(onClose = { dest = "" }); return }
    if (dest == "admin") { AdminProxyScreen(onClose = { dest = "" }); return }

    Column(Modifier.fillMaxSize().background(c.appBg)) {
        TopBar(title = "设置", onBack = onClose)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {

            SectionHeader("外观")
            SettingRow("主题模式", s.themeMode.label) { themeMenu = true }
            SettingRow("列表视图", if (s.viewMode == ViewMode.LIST) "列表" else "网格") { viewMenu = true }
            SettingToggle("显示隐藏文件", s.showHidden) { AppPrefs.setShowHidden(it) }
            SettingToggle("网格缩略图", s.gridThumbnails) { AppPrefs.setGridThumbnails(it) }

            SectionHeader("文件排序")
            SettingRow("默认排序", "${s.sortField.label} · ${if (s.sortAsc) "升序" else "降序"}") { sortMenu = true }
            SettingToggle("文件夹置顶", s.dirsFirst) { AppPrefs.setDirsFirst(it) }

            SectionHeader("文本编辑器")
            SettingRow("字号", "${s.editorFontSize} sp") {
                // 用滑块就地调整
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("编辑器字号 ${s.editorFontSize}sp", color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.width(130.dp))
                Slider(value = s.editorFontSize.toFloat(), onValueChange = { AppPrefs.setEditorFontSize(it.toInt()) }, valueRange = 10f..24f, steps = 14, modifier = Modifier.weight(1f))
            }
            SettingToggle("自动换行", s.editorWordWrap) { AppPrefs.setEditorWordWrap(it) }

            if (SiteConfig.isAdmin) {
                SectionHeader("默认启动目录（管理员）")
                AppOutlinedField(value = defaultPath, onValueChange = { defaultPath = it }, label = "相对路径，如 sub/site（留空为根目录）", singleLine = true)
                Spacer(Modifier.height(10.dp))
                AppButton(text = "保存默认目录", onClick = {
                    scope.launch {
                        val ok = runCatching { SiteFiles.pushDefaultPath(defaultPath.trim().trimStart('/')) }.getOrDefault(false)
                        ToastBus.show(context, if (ok) "已保存" else "保存失败", long = true)
                    }
                })
            }

            // 反向代理入口：A 级显示；管理后台仅 owner 显示。
            // 用服务端实时结果，但服务端查询万一失败/暂时没回来时，回退到本地 A 级判定，
            // 避免两个入口因一次网络抖动一起消失。
            val confirmedAdmin = (ownerInfo?.isAdmin == true) || SiteConfig.isAdmin
            if (confirmedAdmin) {
                SectionHeader("反代管理")
                SettingRow("反向代理", "管理本站点反代") { dest = "proxy" }
                if (ownerInfo?.isOwner == true) {
                    SettingRow("管理后台", "审核放行待审反代") { dest = "admin" }
                }
            }

            SectionHeader("关于")
            SettingRow("权限等级", when {
                ownerInfo?.isOwner == true -> "平台管理员 (A)"
                confirmedAdmin -> "管理员 (A)"
                else -> "站点用户 (${SiteConfig.perm})"
            }) {}
            SettingRow("版本", "1.0") {}
            SettingRow("绑定域名", SiteConfig.WS_HOST) {}

            Spacer(Modifier.height(16.dp))
            AppButton(text = "退出登录", onClick = onLogout, danger = true)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (themeMenu) {
        ChoiceDialog(title = "主题模式", options = ThemeMode.values().map { tm ->
            ChoiceItem(tm.label, if (s.themeMode == tm) androidx.compose.material.icons.Icons.Rounded.Check else null) { AppPrefs.setThemeMode(tm) }
        }) { themeMenu = false }
    }
    if (viewMenu) {
        ChoiceDialog(title = "列表视图", options = listOf(
            ChoiceItem("列表", if (s.viewMode == ViewMode.LIST) androidx.compose.material.icons.Icons.Rounded.Check else null) { AppPrefs.setViewMode(ViewMode.LIST) },
            ChoiceItem("网格", if (s.viewMode == ViewMode.GRID) androidx.compose.material.icons.Icons.Rounded.Check else null) { AppPrefs.setViewMode(ViewMode.GRID) }
        )) { viewMenu = false }
    }
    if (sortMenu) {
        ChoiceDialog(title = "默认排序", options = SortField.values().map { sf ->
            ChoiceItem(sf.label, if (s.sortField == sf) androidx.compose.material.icons.Icons.Rounded.Check else null) { AppPrefs.setSort(sf, true) }
        } + ChoiceItem("切换升/降序", androidx.compose.material.icons.Icons.Rounded.SwapVert) { AppPrefs.setSort(s.sortField, !s.sortAsc) }) { sortMenu = false }
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.cardBg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(6.dp))
        Icon(androidx.compose.material.icons.Icons.Rounded.ChevronRight, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun SettingToggle(title: String, value: Boolean, onToggle: (Boolean) -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.cardBg).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = value, onCheckedChange = onToggle, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = c.teal))
    }
    Spacer(Modifier.height(2.dp))
}
