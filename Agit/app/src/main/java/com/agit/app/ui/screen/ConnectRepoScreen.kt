package com.agit.app.ui.screen

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.navigation.NavHostController
import com.agit.app.data.AppContainer
import com.agit.app.data.model.RemoteRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ConnectRepoScreen(nc: NavHostController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RemoteRepo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun search() {
        val query = q.trim()
        if (query.isEmpty()) return
        loading = true; err = null
        scope.launch(Dispatchers.IO) {
            runCatching { AppContainer.repoRepository.searchPublic(query) }
                .onSuccess { r -> withContext(Dispatchers.Main) { results = r; loading = false } }
                .onFailure { e -> withContext(Dispatchers.Main) { err = e.message; loading = false } }
        }
    }

    fun connectById() {
        val target = id.trim()
        if (target.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runCatching { AppContainer.repoRepository.connect(target) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "已连接", Toast.LENGTH_SHORT).show()
                        nc.popBackStack()
                    }
                }
                .onFailure { e ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, e.message ?: "失败", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("连接仓库") },
                navigationIcon = {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null,
                        Modifier.clickable { nc.popBackStack() }.padding(12.dp))
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("按 ID 直接连接", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = id, onValueChange = { id = it },
                label = { Text("仓库 ID") },
                placeholder = { Text("如 a1b2c3d4") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { connectById() },
                modifier = Modifier.fillMaxWidth(),
                enabled = id.isNotBlank()
            ) { Text("连接") }

            Spacer(Modifier.height(8.dp))
            Text("或搜索公开仓库", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it },
                    label = { Text("关键词") },
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { search() }, enabled = q.isNotBlank(),
                    shape = RoundedCornerShape(10.dp)
                ) { Icon(Icons.Outlined.Search, null) }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (err != null) {
                Text(err ?: "", color = MaterialTheme.colorScheme.error)
            } else if (results.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results) { r ->
                        Card(shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )) {
                            Row(Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Folder, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                                    if (r.description.isNotBlank()) Text(r.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("ID: ${r.id}", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { AppContainer.repoRepository.connect(r.id) }
                                            .onSuccess { withContext(Dispatchers.Main) {
                                                Toast.makeText(ctx, "已连接", Toast.LENGTH_SHORT).show()
                                                results = results.map { it.copy(role = "read") }
                                            } }
                                            .onFailure { e -> withContext(Dispatchers.Main) {
                                                Toast.makeText(ctx, e.message ?: "失败", Toast.LENGTH_SHORT).show()
                                            } }
                                    }
                                }, enabled = r.role.isNullOrBlank()) {
                                    Text(if (r.role.isNullOrBlank()) "连接" else "已连接")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


