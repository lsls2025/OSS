package com.agit.app.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.agit.app.data.AppContainer
import com.agit.app.data.model.BranchInfo
import com.agit.app.data.model.CommitRecord
import com.agit.app.data.model.Repo
import com.agit.app.ui.Routes
import com.agit.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(nc: NavHostController) {
    val repoId = nc.currentBackStackEntry?.arguments?.getString("repoId") ?: ""
    var repo by remember { mutableStateOf<Repo?>(null) }
    var commits by remember { mutableStateOf<List<CommitRecord>>(emptyList()) }
    var branches by remember { mutableStateOf<List<BranchInfo>>(emptyList()) }
    var currentBranch by remember { mutableStateOf("main") }
    var showBranches by remember { mutableStateOf(false) }

    fun loadData() {
        repo = AppContainer.db.getRepo(repoId)
        currentBranch = repo?.defaultBranch ?: "main"
    }

    LaunchedEffect(repoId) {
        loadData()
        runCatching {
            val a = withContext(Dispatchers.IO) { AppContainer.vcs(repoId).logAll(repoId) }
            val b = withContext(Dispatchers.IO) { AppContainer.vcs(repoId).branches(repoId) }
            a to b
        }.onSuccess { commits = it.first; branches = it.second }
    }

    if (repo == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("仓库不存在", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val curRepo = repo!!
    val me = AppContainer.session.currentUser()
    val isOwner = me != null && curRepo.ownerId == me.id.toString()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(curRepo.name, maxLines = 1) },
                navigationIcon = {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null,
                        Modifier.clickable { nc.popBackStack() }.padding(12.dp))
                },
                actions = {
                    if (isOwner) {
                        Icon(Icons.Outlined.Settings, null,
                            Modifier.clickable { nc.navigate(Routes.repoSettings(curRepo.id)) }.padding(12.dp))
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { HeaderCard(curRepo, commits.size, branches.size, currentBranch) }
            item {
                EntryCard(
                    icon = Icons.Outlined.Folder,
                    title = "代码浏览",
                    subtitle = "文件树、新建/导入、提交更改",
                    onClick = { nc.navigate(Routes.fileView(curRepo.id, "")) }
                )
            }

            item {
                EntryCard(
                    icon = Icons.Outlined.History,
                    title = "分支管理",
                    subtitle = "${branches.size} 个分支 · 当前 $currentBranch",
                    onClick = { showBranches = true }
                )
            }
            if (isOwner) {
                item {
                    EntryCard(
                        icon = Icons.Outlined.Settings,
                        title = if (curRepo.remoteRepoId.isEmpty()) "发布到云端" else "协作与远程",
                        subtitle = if (curRepo.remoteRepoId.isEmpty()) "让他人可以连接" else "管理连接者、权限与拉黑",
                        onClick = { nc.navigate(Routes.repoSettings(curRepo.id)) }
                    )
                }
                item { Spacer(Modifier.height(88.dp)) }
            } else {
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }

    if (showBranches) {
        BranchesDialog(
            repoId = curRepo.id,
            current = currentBranch,
            branches = branches,
            onClose = { showBranches = false },
            onDataChanged = { newCurrent, newBranches ->
                currentBranch = newCurrent
                branches = newBranches
            }
        )
    }
}

// ============= Header =============

@Composable
private fun HeaderCard(repo: Repo, commitCount: Int, branchCount: Int, currentBranch: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(repo.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (repo.remoteRepoId.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    val clipboard = LocalClipboardManager.current
                    val idCtx = LocalContext.current
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            clipboard.setText(AnnotatedString(repo.remoteRepoId))
                            Toast.makeText(idCtx, "已复制仓库 ID", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text(repo.remoteRepoId, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                Tag(if (repo.isPrivate) "私有" else "公开",
                    if (repo.isPrivate) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.secondary)
            }
            if (repo.description.isNotBlank()) {
                Text(repo.description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$commitCount 次提交 · $branchCount 个分支",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" · 分支 $currentBranch", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                if (repo.remoteRepoId.isNotEmpty()) {
                    Text(" · ", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Tag("已发布", MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CommitsPreview(commits: List<CommitRecord>, onAll: () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("最近提交", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onAll) { Text("全部") }
            }
            if (commits.isEmpty()) {
                Text("还没有任何提交", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    commits.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.message.ifBlank { "(无提交信息)" },
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                modifier = Modifier.weight(1f))
                            Text(Format.relative(c.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(c.shortId, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), contentColor = color,
        shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

// ============= 分支管理对话框 =============

@Composable
private fun BranchesDialog(
    repoId: String,
    current: String,
    branches: List<BranchInfo>,
    onClose: () -> Unit,
    onDataChanged: (String, List<BranchInfo>) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    fun refreshBranches(workingDir: String, newCurrent: String? = null) {
        scope.launch(Dispatchers.IO) {
            val refreshed = runCatching { AppContainer.vcs(workingDir).branches(workingDir) }
                .getOrDefault(emptyList())
            val cur = newCurrent ?: AppContainer.db.getRepo(workingDir)?.defaultBranch ?: current
            withContext(Dispatchers.Main) {
                onDataChanged(cur, refreshed)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text("分支管理") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("当前分支：$current", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("新建分支名称") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val n = newName.trim()
                            if (n.isEmpty() || working) return@Button
                            working = true
                            scope.launch(Dispatchers.IO) {
                                runCatching { AppContainer.vcs(repoId).createBranch(repoId, n) }
                                    .onSuccess { success ->
                                        withContext(Dispatchers.Main) {
                                            working = false
                                            if (success) {
                                                Toast.makeText(ctx, "分支 $n 已创建", Toast.LENGTH_SHORT).show()
                                                newName = ""
                                                refreshBranches(repoId)
                                            } else {
                                                Toast.makeText(ctx, "分支已存在", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    .onFailure { e -> withContext(Dispatchers.Main) {
                                        working = false
                                        Toast.makeText(ctx, e.message ?: "创建失败", Toast.LENGTH_SHORT).show()
                                    } }
                            }
                        },
                        enabled = !working,
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("新建") }
                }

                Text("已有分支（共 ${branches.size} 个）", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    branches.forEach { b ->
                        Card(shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        b.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (b.name == current) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        if (b.name == current) "当前分支 · ${b.commitCount} 次提交"
                                        else "${b.commitCount} 次提交" + if (b.isDefault) " · 默认" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (b.name != current) {
                                    OutlinedButton(
                                        onClick = {
                                            if (working) return@OutlinedButton
                                            working = true
                                            scope.launch(Dispatchers.IO) {
                                                runCatching { AppContainer.vcs(repoId).checkout(repoId, b.name) }
                                                    .onSuccess { success ->
                                                        if (success) {
                                                            AppContainer.db.updateDefaultBranch(repoId, b.name)
                                                        }
                                                        withContext(Dispatchers.Main) {
                                                            working = false
                                                            if (success) {
                                                                Toast.makeText(ctx, "已切换到 ${b.name}", Toast.LENGTH_SHORT).show()
                                                                refreshBranches(repoId, b.name)
                                                            } else {
                                                                Toast.makeText(ctx, "切换失败", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                    .onFailure { e -> withContext(Dispatchers.Main) {
                                                        working = false
                                                        Toast.makeText(ctx, e.message ?: "切换失败", Toast.LENGTH_SHORT).show()
                                                    } }
                                            }
                                        },
                                        enabled = !working,
                                        shape = RoundedCornerShape(8.dp)
                                    ) { Text("切换") }
                                    if (!b.isDefault) {
                                        Spacer(Modifier.width(6.dp))
                                        TextButton(
                                            onClick = {
                                                if (working) return@TextButton
                                                working = true
                                                scope.launch(Dispatchers.IO) {
                                                    runCatching { AppContainer.vcs(repoId).deleteBranch(repoId, b.name) }
                                                        .onSuccess { success ->
                                                            withContext(Dispatchers.Main) {
                                                                working = false
                                                                if (success) {
                                                                    Toast.makeText(ctx, "分支已删除", Toast.LENGTH_SHORT).show()
                                                                    refreshBranches(repoId)
                                                                } else {
                                                                    Toast.makeText(ctx, "无法删除", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        }
                                                        .onFailure { e -> withContext(Dispatchers.Main) {
                                                            working = false
                                                            Toast.makeText(ctx, e.message ?: "删除失败", Toast.LENGTH_SHORT).show()
                                                        } }
                                                }
                                            },
                                            enabled = !working
                                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                    }
                                } else {
                                    Tag("当前", MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { if (!working) onClose() }, enabled = !working) { Text("关闭") } }
    )
}


