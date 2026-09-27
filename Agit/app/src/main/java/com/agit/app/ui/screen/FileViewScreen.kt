package com.agit.app.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.agit.app.data.AppContainer
import com.agit.app.data.model.RepoStatus
import com.agit.app.data.model.TreeEntryRecord
import com.agit.app.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewScreen(nc: NavHostController) {
    val repoId = nc.currentBackStackEntry?.arguments?.getString("repoId") ?: ""
    val pathEnc = nc.currentBackStackEntry?.arguments?.getString("path") ?: ""
    val currentPath = Uri.decode(pathEnc)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<TreeEntryRecord>>(emptyList()) }
    var status by remember { mutableStateOf<RepoStatus?>(null) }
    var commitMsg by remember { mutableStateOf("") }
    var committing by remember { mutableStateOf(false) }

    fun load() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val all = AppContainer.vcs(repoId).treeAtHead(repoId)
                val cur = currentPath.trim('/')
                val filtered = if (cur.isBlank()) {
                    all.filter { !it.path.contains('/') }
                } else {
                    val prefix = "$cur/"
                    all.filter { it.path.startsWith(prefix) &&
                        it.path.removePrefix(prefix).count { c -> c == '/' } == 0 }
                }
                val s = AppContainer.vcs(repoId).status(repoId)
                filtered to s
            }.onSuccess { (e, s) ->
                withContext(Dispatchers.Main) { entries = e; status = s }
            }
        }
    }

    LaunchedEffect(repoId, currentPath) { load() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val fileName = uri.lastPathSegment ?: return@runCatching
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching
                    AppContainer.vcs(repoId).writeFileBytes(
                        "$currentPath/$fileName".trim('/'), bytes)
                }
                withContext(Dispatchers.Main) { load() }
            }
        }
    }

    fun doCommit() {
        committing = true
        scope.launch(Dispatchers.IO) {
            val user = AppContainer.session.currentUser() ?: run {
                withContext(Dispatchers.Main) { committing = false }
                return@launch
            }
            runCatching {
                AppContainer.vcs(repoId).commit(repoId, user.id, user.displayNameOrUser(),
                    commitMsg.trim().ifBlank { "更新代码" }, null)
            }.onSuccess { rec ->
                withContext(Dispatchers.Main) {
                    committing = false
                    if (rec == null) {
                        Toast.makeText(ctx, "没有可提交的更改", Toast.LENGTH_SHORT).show()
                    } else {
                        commitMsg = ""
                        Toast.makeText(ctx, "提交成功", Toast.LENGTH_SHORT).show()
                        load()
                    }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    committing = false
                    Toast.makeText(ctx, e.message ?: "提交失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentPath.ifBlank { "根目录" }, maxLines = 1) },
                navigationIcon = {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null,
                        Modifier.clickable { nc.popBackStack() }.padding(12.dp))
                },
                actions = {
                    Icon(Icons.Filled.Add, null,
                        Modifier.clickable { importLauncher.launch(arrayOf("*/*")) }.padding(12.dp))
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            status?.let { st ->
                if (st.hasChanges) {
                    Card(
                        Modifier.fillMaxWidth().padding(16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${st.changes.size} 个文件待提交",
                                style = MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("+${st.additions}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary)
                                Text("  -${st.deletions}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            OutlinedTextField(
                                value = commitMsg, onValueChange = { commitMsg = it },
                                label = { Text("提交信息") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            Button(onClick = { doCommit() }, enabled = !committing,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(if (committing) "提交中..." else "提交更改")
                            }
                        }
                    }
                }
            }

            if (entries.isEmpty() && status?.hasChanges != true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(entries.sortedWith(compareBy({ it.kind == "blob" }, { it.path }))) { e ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                nc.navigate(Routes.fileView(repoId,
                                    "$currentPath/${e.path}".trim('/')))
                            }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (e.kind == "tree") Icons.Outlined.Folder
                                else Icons.Outlined.Check,
                                null, modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(e.path.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            Text("${e.size} B", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
