package com.agit.app.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.navigation.NavHostController
import com.agit.app.util.decodeAvatarDataUrl
import com.agit.app.data.AppContainer
import com.agit.app.data.model.CommitRecord
import com.agit.app.data.model.RemoteRepo
import com.agit.app.data.model.Repo
import com.agit.app.ui.Routes
import com.agit.app.util.Format
import com.agit.app.util.uriToAvatarDataUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nc: NavHostController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val user = AppContainer.session.currentUser()
    var avatar by remember { mutableStateOf(user?.avatar ?: "") }
    var tab by remember { mutableIntStateOf(AppContainer.homeTab) }
    var showLogout by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val dataUrl = uriToAvatarDataUrl(ctx, uri) ?: run {
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "无法读取图片", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            val token = AppContainer.session.token() ?: return@launch
            AppContainer.authRepository.uploadAvatar(token, dataUrl)
                .onSuccess { r ->
                    val a = r.ifBlank { dataUrl }
                    avatar = a
                    user?.let { AppContainer.session.save(it.copy(avatar = a)) }
                    withContext(Dispatchers.Main) { Toast.makeText(ctx, "头像更新成功", Toast.LENGTH_SHORT).show() }
                }
                .onFailure { withContext(Dispatchers.Main) { Toast.makeText(ctx, "上传失败", Toast.LENGTH_SHORT).show() } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (user != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { picker.launch("image/*") }
                        ) {
                            AvatarImage(avatar, user.initial())
                            Spacer(Modifier.width(10.dp))
                            Text(user.displayNameOrUser(), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                },
                actions = {
                    TopBarActionButton(
                        onClick = { nc.navigate(Routes.MY_CONNECTIONS) },
                        icon = Icons.Outlined.Link,
                        iconTint = MaterialTheme.colorScheme.primary
                    )
                    TopBarActionButton(
                        onClick = { showLogout = true },
                        icon = Icons.AutoMirrored.Filled.Logout,
                        iconTint = MaterialTheme.colorScheme.error
                    )
                }
            )
        },
        bottomBar = { BottomNavBar(tab = tab, onTabChange = { tab = it; AppContainer.homeTab = it }) },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(
                    onClick = { nc.navigate(Routes.NEW_REPO) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) { Icon(Icons.Filled.Add, null) }
            }
        }
    ) { pad ->
        when (tab) {
            0 -> ReposTab(nc, pad)
            1 -> CommitsTab(nc, pad)
            2 -> DiscoverTab(nc, pad)
        }
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出当前账号吗？") },
            confirmButton = {
                TextButton(onClick = {
                    AppContainer.session.clear()
                    nc.navigate(Routes.LOGIN) { popUpTo(Routes.HOME) { inclusive = true } }
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TopBarActionButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color
) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AvatarFallback(fallback: Char) {
    Box(
        Modifier.size(36.dp).background(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(fallback.toString(), color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AvatarImage(avatar: String, fallback: Char) {
    if (avatar.isNotBlank()) {
        val bmp = decodeAvatarDataUrl(avatar)
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(CircleShape)
            )
            return
        }
    }
    AvatarFallback(fallback)
}

@Composable
private fun BottomNavBar(tab: Int, onTabChange: (Int) -> Unit) {
    val darkBg = MaterialTheme.colorScheme.background == Color(0xFF0D1117)
    val bgColor = if (darkBg) Color(0xFF010409) else Color(0xFFFFFFFF)
    val divider = if (darkBg) Color(0xFF30363D) else Color(0xFFD0D7DE)
    Column(Modifier.fillMaxWidth().background(bgColor)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(divider))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomTab(Icons.Outlined.Folder, "仓库", tab == 0) { onTabChange(0) }
            BottomTab(Icons.Outlined.History, "提交", tab == 1) { onTabChange(1) }
            BottomTab(Icons.Outlined.Code, "发现", tab == 2) { onTabChange(2) }
        }
    }
}

@Composable
private fun BottomTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tabColor"
    )
    Column(
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onClick() }.padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

// ========= 仓库 Tab =========

@Composable
private fun ReposTab(nc: NavHostController, pad: PaddingValues) {
    val user = AppContainer.session.currentUser()
    var repos by remember { mutableStateOf<List<Pair<Repo, Int>>>(emptyList()) }

    LaunchedEffect(Unit) {
        user?.let { u ->
            repos = withContext(Dispatchers.IO) {
                AppContainer.db.listMyRepos(u.id).map { it to AppContainer.db.countCommits(it.id) }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)) {
        Text("我的仓库", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        if (repos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("还没有仓库", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("点击右下角 + 创建", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(repos, key = { it.first.id }) { (repo, count) ->
                    RepoItem(repo, count) { nc.navigate(Routes.repo(repo.id)) }
                }
            }
        }
    }
}

@Composable
private fun RepoItem(repo: Repo, commitCount: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(repo.name, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                StatusTag(if (repo.isPrivate) "私有" else "公开",
                    if (repo.isPrivate) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.secondary)
            }
            if (repo.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(repo.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$commitCount 次提交", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("  ·  ", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
                Text(Format.relative(repo.updatedAt), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (repo.remoteRepoId.isNotEmpty()) {
                    Text("  ·  ", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                    StatusTag("已发布", MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.18f),
        contentColor = color,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

// ========= 提交 Tab =========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitsTab(nc: NavHostController, pad: PaddingValues) {
    val user = AppContainer.session.currentUser()
    var list by remember { mutableStateOf<List<Pair<Repo, CommitRecord>>>(emptyList()) }
    var selectedRepoId by remember { mutableStateOf<String?>(null) }
    var showSwitch by remember { mutableStateOf(false) }
    val repos = remember(user?.id) { AppContainer.db.listMyRepos(user?.id ?: "") }

    LaunchedEffect(Unit) {
        user?.let { u ->
            list = withContext(Dispatchers.IO) {
                AppContainer.db.listMyRepos(u.id).flatMap { repo ->
                    runCatching { AppContainer.db.listAllCommits(repo.id).map { repo to it } }
                        .getOrDefault(emptyList())
                }.sortedByDescending { it.second.timestamp }
            }
        }
    }

    val shown = if (selectedRepoId == null) list else list.filter { it.first.id == selectedRepoId }

    Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("提交历史", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f))
            Surface(
                onClick = { showSwitch = true },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    val currentName = repos.firstOrNull { it.id == selectedRepoId }?.name ?: "全部仓库"
                    Text(currentName, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    Icon(Icons.Outlined.ArrowDropDown, null,
                        Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.History, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(if (list.isEmpty()) "暂无提交" else "该仓库暂无提交",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.second.id }) { (repo, c) ->
                    CommitItem(repo, c) { nc.navigate(Routes.commitDetail(repo.id, c.id)) }
                }
            }
        }
    }

    if (showSwitch) {
        val sheetState = rememberModalBottomSheetState()
        var q by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { showSwitch = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("切换仓库", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                OutlinedTextField(value = q, onValueChange = { q = it },
                    label = { Text("搜索仓库") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                val filtered = repos.filter { it.name.contains(q.trim(), ignoreCase = true) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        TextButton(onClick = { selectedRepoId = null; showSwitch = false },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("全部仓库", color = if (selectedRepoId == null)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    items(filtered, key = { it.id }) { r ->
                        TextButton(onClick = { selectedRepoId = r.id; showSwitch = false },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(r.name, color = if (selectedRepoId == r.id)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1)
                        }
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center) {
                                Text("无匹配仓库", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitItem(repo: Repo, c: CommitRecord, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(c.message.ifBlank { "(无提交信息)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            Text("${repo.name} · ${c.authorName} · ${Format.relative(c.timestamp)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text("+${c.additions}  -${c.deletions}  ·  ${c.filesChanged} 个文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ========= 发现 Tab =========

@Composable
private fun DiscoverTab(nc: NavHostController, pad: PaddingValues) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val me = AppContainer.session.currentUser()
    var query by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<RemoteRepo>>(emptyList()) }
    var page by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val pageSize = 20

    fun load(reset: Boolean) {
        if (reset) { page = 0; repos = emptyList(); hasMore = true; err = null; loading = true }
        if (!hasMore || loadingMore) return
        loadingMore = true
        scope.launch(Dispatchers.IO) {
            runCatching { AppContainer.repoRepository.searchPublic(query.trim(), page, pageSize) }
                .onSuccess { new ->
                    withContext(Dispatchers.Main) {
                        repos = if (reset) new else repos + new
                        hasMore = new.size >= pageSize
                        page = page + 1
                        loading = false
                        loadingMore = false
                    }
                }
                .onFailure { e ->
                    withContext(Dispatchers.Main) {
                        if (reset) err = e.message
                        loading = false
                        loadingMore = false
                    }
                }
        }
    }

    LaunchedEffect(Unit) { load(true) }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size
        }.collect { last ->
            if (hasMore && !loadingMore && repos.isNotEmpty() && last >= repos.size - 2) {
                load(false)
            }
        }
    }

    var pwdTarget by remember { mutableStateOf<RemoteRepo?>(null) }
    var pwd by remember { mutableStateOf("") }
    var pwdErr by remember { mutableStateOf<String?>(null) }

    fun doConnect(target: RemoteRepo, password: String?) {
        scope.launch(Dispatchers.IO) {
            runCatching { AppContainer.repoRepository.connect(target.id, password) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "已连接", Toast.LENGTH_SHORT).show()
                        load(true)
                    }
                }
                .onFailure { e ->
                    val msg = e.message ?: "连接失败"
                    withContext(Dispatchers.Main) {
                        if (msg.contains("密码")) { pwdTarget = target; pwdErr = msg }
                        else Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    fun openRepo(r: RemoteRepo) {
        val me = AppContainer.session.currentUser()
        if (r.ownerId.toString() == me?.id) {
            val local = AppContainer.db.listRepos(me.id).firstOrNull { it.remoteRepoId == r.id }
            if (local != null) nc.navigate(Routes.repo(local.id))
            else Toast.makeText(ctx, "本地仓库未找到", Toast.LENGTH_SHORT).show()
        } else {
            nc.navigate(Routes.publicRepo(r.id))
        }
    }

    Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)) {
        Text("发现", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))

        Row(
            modifier = Modifier.height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("搜索仓库名称、描述或 ID") },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { load(true) }),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { load(true) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Icon(Icons.Outlined.Search, null)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (err != null && repos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(err ?: "加载失败", color = MaterialTheme.colorScheme.error)
            }
        } else if (repos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("还没有公开仓库", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("发布你的第一个仓库吧", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(repos, key = { it.id }) { r ->
                    val role = if (r.ownerId.toString() == me?.id) "owner" else null
                    RemoteRepoCard(r, role, { doConnect(r, null) }, { openRepo(r) })
                }
                if (hasMore) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }

    if (pwdTarget != null) {
        val target = pwdTarget!!
        AlertDialog(onDismissRequest = { pwdTarget = null },
            title = { Text("需要连接密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pwdErr != null) Text(pwdErr ?: "", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = pwd, onValueChange = { pwd = it }, label = { Text("连接密码") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = {
                pwdTarget = null
                val p = pwd.trim()
                pwd = ""
                if (p.isNotEmpty()) doConnect(target, p)
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { pwdTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun RemoteRepoCard(repo: RemoteRepo, role: String?, onConnect: () -> Unit, onOpen: () -> Unit) {
    Card(
        Modifier.clickable { onOpen() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Folder, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(repo.name, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        StatusTag(if (repo.isPrivate) "私有" else "公开",
                            if (repo.isPrivate) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.secondary)
                    }
                    if (repo.description.isNotBlank()) {
                        Text(repo.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ID: ${repo.id}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (!role.isNullOrBlank()) {
                    StatusTag(
                        when (role) {
                            "owner" -> "所有者"
                            "modify" -> "可修改"
                            else -> "只读"
                        },
                        MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (role != "owner") {
                    if (!repo.allowConnect) {
                        OutlinedButton(onClick = {}, enabled = false,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                            Text("未开放", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        Button(
                            onClick = { onConnect() },
                            enabled = role.isNullOrBlank(),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(if (role.isNullOrBlank()) "连接" else "已连接",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
