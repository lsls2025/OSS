package com.agit.app.ui.screen

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.agit.app.data.AppContainer
import com.agit.app.data.model.PublicRepoInfo
import com.agit.app.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicRepoScreen(nc: NavHostController) {
    val rid = nc.currentBackStackEntry?.arguments?.getString("repoId") ?: ""
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<PublicRepoInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var showPwd by remember { mutableStateOf(false) }
    var pwd by remember { mutableStateOf("") }
    var pwdMsg by remember { mutableStateOf<String?>(null) }

    val me = AppContainer.session.currentUser()
    val isOwner = info != null && me != null && info!!.ownerId.toString() == me.id

    fun doConnect(rid: String, password: String?) {
        connecting = true
        scope.launch(Dispatchers.IO) {
            runCatching { AppContainer.repoRepository.connect(rid, password) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        connecting = false
                        Toast.makeText(ctx, "已连接", Toast.LENGTH_SHORT).show()
                        nc.popBackStack()
                    }
                }
                .onFailure { e ->
                    val msg = e.message ?: "连接失败"
                    withContext(Dispatchers.Main) {
                        connecting = false
                        if (msg.contains("密码")) {
                            showPwd = true
                            pwdMsg = msg
                        } else {
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
        }
    }

    LaunchedEffect(rid) {
        runCatching { AppContainer.repoRepository.getRepoInfo(rid) }
            .onSuccess { withContext(Dispatchers.Main) { info = it; loading = false } }
            .onFailure { e -> withContext(Dispatchers.Main) { err = e.message; loading = false } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info?.name ?: "仓库") },
                navigationIcon = {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null,
                        Modifier.clickable { nc.popBackStack() }.padding(12.dp))
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (err != null) {
                Text(err ?: "", color = MaterialTheme.colorScheme.error)
            } else if (info != null) {
                val cur = info!!
                Card(shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))) {
                    Column(Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(cur.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            StatusTag(if (cur.isPrivate) "私有" else "公开",
                                if (cur.isPrivate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary)
                        }
                        if (cur.description.isNotBlank())
                            Text(cur.description, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("由 ${cur.ownerUsername} 拥有", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val clipboard = LocalClipboardManager.current
                        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                clipboard.setText(AnnotatedString(cur.id))
                                Toast.makeText(ctx, "已复制仓库 ID", Toast.LENGTH_SHORT).show()
                            }) {
                            Text("ID: ${cur.id}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }

                if (isOwner) {
                    val local = AppContainer.db.listRepos(me!!.id).firstOrNull { it.remoteRepoId == cur.id }
                    Button(onClick = {
                        if (local != null) nc.navigate(Routes.repo(local.id))
                        else Toast.makeText(ctx, "本地仓库未找到", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                        Text("在本地打开")
                    }
                    Text("这是你发布的仓库，可直接在本地查看完整内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (!cur.allowConnect) {
                        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)) {
                            Text("未开放连接")
                        }
                        Text("该仓库拥有者未开放连接，暂时无法连接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Button(onClick = { doConnect(cur.id, null) }, enabled = !connecting,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Text(if (connecting) "连接中..." else "连接此仓库")
                        }
                        Text("连接后可接收作者更新通知（文件内容同步功能将在后续版本提供）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(88.dp))
            }
        }

        if (showPwd) {
            AlertDialog(onDismissRequest = { showPwd = false },
                title = { Text("需要连接密码") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pwdMsg != null) Text(pwdMsg ?: "", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = pwd, onValueChange = { pwd = it }, label = { Text("连接密码") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showPwd = false
                        val p = pwd.trim()
                        pwd = ""
                        if (p.isNotEmpty()) doConnect(rid, p)
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { showPwd = false }) { Text("取消") } }
            )
        }
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), contentColor = color, shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
