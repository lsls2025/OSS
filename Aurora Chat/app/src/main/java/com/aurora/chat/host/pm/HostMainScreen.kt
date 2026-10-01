package com.aurora.chat.host.pm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/** 虚拟主机主界面：照搬「项目管理」MainScreen 的四 tab 结构（文件/终端/备份/设置）。 */
@Composable
fun HostMainScreen(onExit: () -> Unit, onBackToWorkspace: () -> Unit = {}) {
    val c = LocalAppColors.current
    var destName by remember { mutableStateOf("BROWSER") }

    if (destName == "TERMINAL") { HostTerminalScreen(onClose = { destName = "BROWSER" }); return }
    if (destName == "BACKUP") { HostBackupScreen(onClose = { destName = "BROWSER" }); return }
    if (destName == "SETTINGS") { HostSettingsScreen(onLogout = onExit, onClose = { destName = "BROWSER" }); return }

    Scaffold(
        containerColor = Color.White,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = c.cardBg) {
                listOf(
                    Triple("文件", Icons.Rounded.Folder, "BROWSER"),
                    Triple("终端", Icons.Rounded.Terminal, "TERMINAL"),
                    Triple("备份", Icons.Rounded.Backup, "BACKUP"),
                    Triple("设置", Icons.Rounded.Settings, "SETTINGS"),
                ).forEach { (label, icon, d) ->
                    NavigationBarItem(
                        selected = destName == d,
                        onClick = { destName = d },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = c.teal,
                            selectedTextColor = c.teal,
                            indicatorColor = c.tealSoft,
                        )
                    )
                }
            }
        }
    ) { pad ->
        HostFileBrowser(onBack = onExit, onBackToWorkspace = onBackToWorkspace, modifier = Modifier.fillMaxSize().background(c.appBg).padding(pad))
    }
}
