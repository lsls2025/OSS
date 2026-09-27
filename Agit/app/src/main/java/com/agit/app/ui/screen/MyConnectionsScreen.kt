package com.agit.app.ui.screen

import android.widget.Toast
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.agit.app.data.AppContainer
import com.agit.app.data.model.BlockUser
import com.agit.app.data.model.Connection
import com.agit.app.data.model.Connector
import com.agit.app.data.model.RemoteRepo
import com.agit.app.ui.Routes
import com.agit.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MyConnectionsScreen(nc: NavHostController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var repos by remember { mutableStateOf<List<RemoteRepo>>(emptyList()) }
    var connectors by remember { mutableStateOf<List<Connector>>(emptyList()) }
    var blocks by remember { mutableStateOf<List<BlockUser>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var pendingBlock by remember { mutableStateOf<Connector?>(null) }
    var pendingUnblock by remember { mutableStateOf<BlockUser?>(null) }

    fun load() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val a = AppContainer.repoRepository.listMyRepos()
                val b = AppContainer.repoRepository.myConnectors()
                val c = AppContainer.repoRepository.listBlocks()
                Triple(a, b, c)
            }.onSuccess { (a, b, c) -> withContext(Dispatchers.Main) { repos = a; connectors = b; blocks = c; err = null } }
             .onFailure { e -> withContext(Dispatchers.Main) { err = e.message } }
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("连接管理") },
                navigationIcon = {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null,
                        Modifier.clickable { nc.popBackStack() }.padding(12.dp))
                },
                actions = {
                    Icon(Icons.Filled.Add, null,
                        Modifier.clickable { nc.navigate(Routes.CONNECT_REPO) }.padding(12.dp))
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (err != null) Text(err ?: "", color = MaterialTheme.colorScheme.error)

            Text("我连接的仓库", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp))
            if (repos.isEmpty() && err == null) {
                Text("还没有连接任何仓库", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(repos) { r ->
                        Card(shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                            Row(Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("ID: ${r.id}", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (!r.role.isNullOrBlank()) {
                                            Text(" · ${if (r.role == "modify") "可修改" else "只读"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Text("连接我的人", style = MaterialTheme.typography.titleMedium)
            if (connectors.isEmpty() && err == null) {
                Text("还没有人连接你的仓库", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(connectors) { c ->
                        Card(shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                            Column(Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        RoundedCornerShape(20.dp)),
                                        contentAlignment = Alignment.Center) {
                                        Text(c.user.initial().toString(), color = MaterialTheme.colorScheme.primary)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(c.user.username, style = MaterialTheme.typography.titleMedium)
                                        Text("连接了 ${c.repoName}", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    listOf("read" to "只读", "modify" to "可修改").forEach { (v, l) ->
                                        val sel = c.permission == v
                                        Button(onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                runCatching { AppContainer.repoRepository.setPermission(c.repoId, c.user.id, v) }
                                                    .onSuccess { load() }
                                                    .onFailure { e -> withContext(Dispatchers.Main) { Toast.makeText(ctx, e.message ?: "失败", Toast.LENGTH_SHORT).show() } }
                                            }
                                        }, colors = ButtonDefaults.buttonColors(
                                            containerColor = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                        ), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
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
                                        runCatching { AppContainer.repoRepository.removeConnection(c.repoId, c.user.id) }
                                            .onSuccess { load() }
                                            .onFailure { e -> withContext(Dispatchers.Main) { Toast.makeText(ctx, e.message ?: "失败", Toast.LENGTH_SHORT).show() } }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("取消连接") }
                            }
                        }
                    }
                }
            }

            Text("黑名单", style = MaterialTheme.typography.titleMedium)
            if (blocks.isEmpty() && err == null) {
                Text("没有黑名单用户", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(blocks) { b ->
                        Card(shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                            Row(Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).background(
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                    RoundedCornerShape(20.dp)),
                                    contentAlignment = Alignment.Center) {
                                    Text(b.username.firstOrNull()?.toString()?.uppercase() ?: "#",
                                        color = MaterialTheme.colorScheme.error)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(b.username, style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = { pendingUnblock = b }) { Text("解除") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(88.dp))
        }
    }

    if (pendingBlock != null) {
        val c = pendingBlock!!
        AlertDialog(onDismissRequest = { pendingBlock = null },
            title = { Text("拉黑用户") },
            text = { Text("拉黑后 ${c.user.username} 将无法再连接你的仓库。") },
            confirmButton = { TextButton(onClick = {
                val t = c; pendingBlock = null
                scope.launch(Dispatchers.IO) {
                    runCatching { AppContainer.repoRepository.block(t.repoId, t.user.id) }
                        .onSuccess { withContext(Dispatchers.Main) { load() } }
                        .onFailure { e -> withContext(Dispatchers.Main) { Toast.makeText(ctx, e.message ?: "失败", Toast.LENGTH_SHORT).show() } }
                }
            }) { Text("拉黑", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingBlock = null }) { Text("取消") } }
        )
    }

    if (pendingUnblock != null) {
        val b = pendingUnblock!!
        AlertDialog(onDismissRequest = { pendingUnblock = null },
            title = { Text("解除拉黑") },
            text = { Text("解除后 ${b.username} 可以重新连接你的仓库。") },
            confirmButton = { TextButton(onClick = {
                val t = b; pendingUnblock = null
                scope.launch(Dispatchers.IO) {
                    runCatching { AppContainer.repoRepository.unblock(t.id) }
                        .onSuccess { withContext(Dispatchers.Main) { load() } }
                        .onFailure { e -> withContext(Dispatchers.Main) { Toast.makeText(ctx, e.message ?: "失败", Toast.LENGTH_SHORT).show() } }
                }
            }) { Text("解除") } },
            dismissButton = { TextButton(onClick = { pendingUnblock = null }) { Text("取消") } }
        )
    }
}


