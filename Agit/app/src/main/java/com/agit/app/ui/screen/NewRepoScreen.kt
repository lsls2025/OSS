package com.agit.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.navigation.NavHostController
import com.agit.app.data.AppContainer
import com.agit.app.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NewRepoScreen(nc: NavHostController) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建仓库") },
                navigationIcon = {
                    TextButton(onClick = { nc.popBackStack() }) { Text("取消") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it; err = "" },
                label = { Text("仓库名称") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = err.isNotBlank(),
                supportingText = if (err.isNotBlank()) ({ Text(err) }) else null
            )
            OutlinedTextField(
                value = desc, onValueChange = { desc = it },
                label = { Text("描述（可选）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("私有仓库", style = MaterialTheme.typography.bodyLarge)
                    Text("仅自己可见", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val n = name.trim()
                    if (n.isEmpty()) { err = "名称不能为空"; return@Button }
                    val user = AppContainer.session.currentUser() ?: return@Button
                    scope.launch(Dispatchers.IO) {
                        if (AppContainer.db.repoNameExists(n, user.id, "")) {
                            withContext(Dispatchers.Main) { err = "该名称已存在" }
                            return@launch
                        }
                        // 本地创建（含 git 初始化）
                        val repo = AppContainer.createRepo(user, n, desc.trim(), isPrivate)
                        // 同时发布到服务器，保证仓库持久化在服务器上（本地可视为缓存）
                        runCatching { AppContainer.repoRepository.createRemoteRepo(n, desc.trim(), isPrivate) }
                            .onSuccess { rid -> AppContainer.db.updateRemoteRepoId(repo.id, rid) }
                            .onFailure { e ->
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        nc.context,
                                        "已本地创建，但发布到服务器失败：${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        withContext(Dispatchers.Main) {
                            nc.navigate(Routes.repo(repo.id)) { popUpTo(Routes.HOME) }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("创建仓库") }
        }
    }
}
