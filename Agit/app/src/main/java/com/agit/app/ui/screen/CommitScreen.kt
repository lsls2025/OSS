package com.agit.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.agit.app.data.AppContainer
import com.agit.app.data.model.CommitRecord
import com.agit.app.ui.Routes
import com.agit.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CommitScreen(nc: NavHostController) {
    val repoId = nc.currentBackStackEntry?.arguments?.getString("repoId") ?: ""
    var commits by remember { mutableStateOf<List<CommitRecord>>(emptyList()) }

    LaunchedEffect(repoId) {
        runCatching {
            withContext(Dispatchers.IO) { AppContainer.vcs(repoId).logAll(repoId) }
        }.onSuccess { commits = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提交历史") },
                navigationIcon = {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null,
                        Modifier.clickable { nc.popBackStack() }.padding(12.dp))
                },
                actions = {
                    val me = AppContainer.session.currentUser()
                    var expanded by remember { mutableStateOf(false) }
                    val curName = remember(repoId) { AppContainer.db.getRepo(repoId)?.name ?: repoId }
                    val others = remember(me?.id) {
                        AppContainer.db.listRepos(me?.id ?: "").filter { it.id != repoId }
                    }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(curName, maxLines = 1)
                            Icon(Icons.Outlined.ArrowDropDown, null, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            if (others.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("没有其他仓库", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    enabled = false, onClick = {})
                            } else {
                                others.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text(r.name) },
                                        onClick = {
                                            expanded = false
                                            nc.navigate(Routes.commitList(r.id)) {
                                                popUpTo(Routes.commitList(repoId)) { inclusive = true }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(commits, key = { it.id }) { c ->
                Card(
                    onClick = { nc.navigate(Routes.commitDetail(repoId, c.id)) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(c.message.ifBlank { "(无提交信息)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.History, null,
                                Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(4.dp))
                            Text("${c.authorName} · ${Format.relative(c.timestamp)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text(c.shortId, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Text("+${c.additions}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary)
                            Text(" -${c.deletions}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                            Text("  ${c.filesChanged} 文件", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}


