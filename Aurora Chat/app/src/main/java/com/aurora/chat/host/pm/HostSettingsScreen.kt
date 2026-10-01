package com.aurora.chat.host.pm

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.host.HostConfig
import kotlinx.coroutines.launch

private fun toast(ctx: Context, msg: String, long: Boolean = false) =
    Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

/** 设置：照搬「项目管理」SettingsScreen（去掉反向代理/管理后台，保留外观/排序/编辑器/关于/退出）。 */
@Composable
fun HostSettingsScreen(onLogout: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    val s by HostPmPrefs.settings
    var themeMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.appBg)) {
        TopBar(title = "设置", onBack = onClose)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {

            SectionHeader("外观")
            SettingRow("主题模式", s.themeMode.label) { themeMenu = true }
            SettingRow("列表视图", if (s.viewMode == ViewMode.LIST) "列表" else "网格") { viewMenu = true }
            SettingToggle("显示隐藏文件", s.showHidden) { HostPmPrefs.setShowHidden(it) }
            SettingToggle("网格缩略图", s.gridThumbnails) { HostPmPrefs.setGridThumbnails(it) }

            SectionHeader("文件排序")
            SettingRow("默认排序", "${s.sortField.label} · ${if (s.sortAsc) "升序" else "降序"}") { sortMenu = true }
            SettingToggle("文件夹置顶", s.dirsFirst) { HostPmPrefs.setDirsFirst(it) }

            SectionHeader("文本编辑器")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("编辑器字号 ${s.editorFontSize}sp", color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.width(130.dp))
                androidx.compose.material3.Slider(
                    value = s.editorFontSize.toFloat(),
                    onValueChange = { HostPmPrefs.setEditorFontSize(it.toInt()) },
                    valueRange = 10f..24f, steps = 14, modifier = Modifier.weight(1f)
                )
            }
            SettingToggle("自动换行", s.editorWordWrap) { HostPmPrefs.setEditorWordWrap(it) }

            SectionHeader("关于")
            SettingRow("权限等级", if (HostConfig.isAdmin) "管理员 (A)" else "站点用户 (${HostConfig.perm})") {}
            SettingRow("服务器", HostConfig.BASE_URL) {}
            SettingRow("绑定卡密", if (HostConfig.cardKey.isBlank()) "未登录" else HostConfig.cardKey) {}

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    HostConfig.logout(context)
                    onLogout()
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c.danger),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
            ) { Text("退出登录", color = Color.White, fontSize = 15.sp) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (themeMenu) {
        ChoiceDialog(title = "主题模式", options = ThemeMode.values().map { tm ->
            ChoiceItem(tm.label, if (s.themeMode == tm) Icons.Rounded.Check else null) { HostPmPrefs.setThemeMode(tm) }
        }) { themeMenu = false }
    }
    if (viewMenu) {
        ChoiceDialog(title = "列表视图", options = listOf(
            ChoiceItem("列表", if (s.viewMode == ViewMode.LIST) Icons.Rounded.Check else null) { HostPmPrefs.setViewMode(ViewMode.LIST) },
            ChoiceItem("网格", if (s.viewMode == ViewMode.GRID) Icons.Rounded.Check else null) { HostPmPrefs.setViewMode(ViewMode.GRID) }
        )) { viewMenu = false }
    }
    if (sortMenu) {
        ChoiceDialog(title = "默认排序", options = SortField.values().map { sf ->
            ChoiceItem(sf.label, if (s.sortField == sf) Icons.Rounded.Check else null) { HostPmPrefs.setSort(sf, true) }
        } + ChoiceItem("切换升/降序", Icons.Rounded.SwapVert) { HostPmPrefs.setSort(s.sortField, !s.sortAsc) }) { sortMenu = false }
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
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
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
        Switch(checked = value, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedTrackColor = c.teal))
    }
    Spacer(Modifier.height(2.dp))
}
