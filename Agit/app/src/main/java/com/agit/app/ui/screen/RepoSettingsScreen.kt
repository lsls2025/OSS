package com.agit.app.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.agit.app.data.AppContainer
import com.agit.app.data.model.Connection
import com.agit.app.data.model.RepoUpdatePayload
import com.agit.app.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoSettingsScreen(nc: NavHostController) {
    val repoId = nc.currentBackStackEntry?.arguments?.getString("repoId") ?: ""
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = AppContainer.session.currentUser()

    val repo = remember { AppContainer.db.getRepo(repoId) }
    if (repo == null) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("仓库设置") }, navigationIcon = {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null,
                    Modifier.clickable { nc.popBackStack() }.padding(12.dp))
            })
        }) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("仓库不存在") } }
        return
    }
    val curRepo = repo
    val isOwner = me != null && curRepo.ownerId == me.id.toString()

    var name by remember { mutableStateOf(curRepo.name) }
    var desc by remember { mutableStateOf(curRepo.description) }
    var isPrivate by remember { mutableStateOf(curRepo.isPrivate) }
    var nameErr by remember { mutableStateOf("") }

    var allowConnect by remember { mutableStateOf(true) }
    var connectPassword by remember { mutableStateOf("") }

    var publishMsg by remember { mutableStateOf<String?>(null) }
    var publishLoading by remember { mutableStateOf(false) }
    var savingConn by remember { mutableStateOf(false) }
    var connMsg by remember { mutableStateOf<String?>(null) }

    var connections by remember { mutableStateOf<List<Connection>>(emptyList()) }
    var connErr by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var pendingBlock by remember { mutableStateOf<Connection?>(null) }

    fun loadConnections(remoteId: String) {
        if (remoteId.isEmpty() || !isOwner) return
        scope.launch(Dispatchers.IO) {
            runCatching { AppContainer.repoRepository.listConnections(remoteId) }
                .onSuccess { r -> withContext(Dispatchers.Main) { connections = r; connErr = null } }
                .onFailure { e -> withContext(Dispatchers.Main) { connErr = e.message } }
        }
    }

    LaunchedEffect(curRepo.remoteRepoId) {
        if (curRepo.remoteRepoId.isNotEmpty() && isOwner) {
            runCatching { AppContainer.repoRepository.getRepoSettings(curRepo.remoteRepoId) }
                .onSuccess { s -> withContext(Dispatchers.Main) {
                    allowConnect = s.allowConnect
                    connectPassword = s.connectPassword
                } }
                .onFailure { }
        }
    }
    LaunchedEffect(curRepo.remoteRepoId) { loadConnections(curRepo.remoteRepoId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("仓库设置") },
                navigationIcon = {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null,
                        Modifier.clickable { nc.popBackStack() }.padding(12.dp))
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (!isOwner) {
                Text("你不是仓库拥有者，无法修改设置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SectionTitle("基础信息")
                OutlinedTextField(value = name, onValueChange = { name = it; nameErr = "" },
                    label = { Text("仓库名称") }, singleLine = true, isError = nameErr.isNotBlank(),
                    supportingText = if (nameErr.isNotBlank()) ({ Text(nameErr) }) else null,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = desc, onValueChange = { desc = it },
                    label = { Text("描述") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("私有仓库", style = MaterialTheme.typography.bodyLarge)
                        Text("仅自己可见，不出现在发现中", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
                Button(onClick = {
                    val n = name.trim()
                    if (n.isEmpty()) { nameErr = "名称不能为空"; return@Button }
                    scope.launch(Dispatchers.IO) {
                        if (AppContainer.db.repoNameExists(n, curRepo.ownerId, curRepo.id)) {
                            withContext(Dispatchers.Main) { nameErr = "该名称已存在" }
                            return@launch
                        }
                        AppContainer.db.updateRepoMeta(curRepo.id, n, desc.trim(), isPrivate)
                        AppContainer.db.touchRepo(curRepo.id)
                        if (curRepo.remoteRepoId.isNotEmpty()) {
                            runCatching {
                                AppContainer.repoRepository.updateRepo(
                                    curRepo.remoteRepoId,
                                    RepoUpdatePayload(name = n, description = desc.trim(), isPrivate = isPrivate)
                                )
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text("保存修改") }

                SectionTitle("连接设置")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开放连接", style = MaterialTheme.typography.bodyLarge)
                        Text(if (allowConnect) "他人可连接此仓库" else "他人无法连接此仓库",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = allowConnect, onCheckedChange = { allowConnect = it })
                }
                OutlinedTextField(value = connectPassword, onValueChange = { connectPassword = it },
                    label = { Text("连接密码") }, singleLine = true,
                    placeholder = { Text("留空表示无需密码") },
                    modifier = Modifier.fillMaxWidth())
                if (connMsg != null)
                    Text(connMsg ?: "", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    savingConn = true; connMsg = null
                    scope.launch(Dispatchers.IO) {
                        val res = runCatching {
                            AppContainer.repoRepository.updateRepo(
                                curRepo.remoteRepoId,
                                RepoUpdatePayload(allowConnect = allowConnect, connectPassword = connectPassword)
                            )
                        }
                        withContext(Dispatchers.Main) {
                            savingConn = false
                            connMsg = if (res.isSuccess) "连接设置已保存"
                            else "保存失败：${res.exceptionOrNull()?.message}"
                        }
                    }
                }, enabled = !savingConn && curRepo.remoteRepoId.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                    Text(if (curRepo.remoteRepoId.isEmpty()) "请先发布到云端" else if (savingConn) "保存中..." else "保存连接设置")
                }

                SectionTitle("远程与协作")
                val currentRemoteId = curRepo.remoteRepoId
                if (currentRemoteId.isEmpty()) {
                    Button(onClick = {
                        publishLoading = true; publishMsg = null
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                AppContainer.repoRepository.createRemoteRepo(curRepo.name, curRepo.description, isPrivate)
                            }
                                .onSuccess { id ->
                                    AppContainer.db.updateRemoteRepoId(curRepo.id, id)
                                    withContext(Dispatchers.Main) {
                                        publishMsg = "发布成功，远程仓库 ID：$id"
                                        publishLoading = false
                                        loadConnections(id)
                                    }
                                }
                                .onFailure { e -> withContext(Dispatchers.Main) {
                                    publishMsg = "发布失败：${e.message}"
                                    publishLoading = false
                                } }
                        }
                    }, enabled = !publishLoading, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)) {
                        Text(if (publishLoading) "发布中..." else "发布到云端")
                    }
                    if (publishMsg != null)
                        Text(publishMsg ?: "", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("远程仓库 ID：$currentRemoteId", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (connErr != null)
                        Text(connErr ?: "", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    if (connections.isEmpty() && connErr == null) {
                        Text("还没有人连接这个仓库", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        connections.forEach { c ->
                            Card(shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                                Column(Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(36.dp)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                RoundedCornerShape(18.dp)),
                                            contentAlignment = Alignment.Center) {
                                            Text(c.user.initial().toString(), color = MaterialTheme.colorScheme.primary)
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(c.user.username, style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f))
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        listOf("read" to "只读", "modify" to "可修改").forEach { (v, l) ->
                                            val sel = c.permission == v
                                            Button(onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    runCatching {
                                                        AppContainer.repoRepository.setPermission(currentRemoteId, c.user.id, v)
                                                    }
                                                        .onSuccess { withContext(Dispatchers.Main) { loadConnections(currentRemoteId) } }
                                                        .onFailure { e -> withContext(Dispatchers.Main) { connErr = e.message } }
                                                }
                                            }, colors = ButtonDefaults.buttonColors(
                                                containerColor = if (sel) MaterialTheme.colorScheme.primary
                                                                 else MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor = if (sel) MaterialTheme.colorScheme.onPrimary
                                                                       else MaterialTheme.colorScheme.onSurfaceVariant
                                            ), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                shape = RoundedCornerShape(8.dp)) {
                                                Text(l, style = MaterialTheme.typography.labelMedium)
                                            }
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Icon(Icons.Outlined.Block, null,
                                            Modifier.clickable { pendingBlock = c },
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                    OutlinedButton(onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            runCatching {
                                                AppContainer.repoRepository.removeConnection(currentRemoteId, c.user.id)
                                            }
                                                .onSuccess { withContext(Dispatchers.Main) { loadConnections(currentRemoteId) } }
                                                .onFailure { e -> withContext(Dispatchers.Main) { connErr = e.message } }
                                        }
                                    }, modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)) { Text("取消连接") }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { nc.navigate(Routes.CONNECT_REPO) },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                        Text("连接更多仓库")
                    }
                }

                SectionTitle("危险操作")
                OutlinedButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)) {
                    Text("删除仓库", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showDelete) {
        AlertDialog(onDismissRequest = { showDelete = false },
            title = { Text("删除仓库") },
            text = { Text("此操作不可恢复，将同时删除所有本地数据。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        AppContainer.repoRoot(curRepo.id).deleteRecursively()
                        AppContainer.db.deleteRepo(curRepo.id)
                        withContext(Dispatchers.Main) {
                            nc.popBackStack()
                            nc.popBackStack()
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }

    if (pendingBlock != null) {
        val c = pendingBlock!!
        AlertDialog(onDismissRequest = { pendingBlock = null },
            title = { Text("拉黑用户") },
            text = { Text("拉黑后 ${c.user.username} 将无法再连接你的仓库。") },
            confirmButton = {
                TextButton(onClick = {
                    val t = c; pendingBlock = null
                    val rid = curRepo.remoteRepoId
                    scope.launch(Dispatchers.IO) {
                        runCatching { AppContainer.repoRepository.block(rid, t.user.id) }
                            .onSuccess { withContext(Dispatchers.Main) { loadConnections(rid) } }
                            .onFailure { e -> withContext(Dispatchers.Main) { connErr = e.message } }
                    }
                }) { Text("确认拉黑", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingBlock = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
