package com.agit.app.ui.screen

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.agit.app.data.AppContainer
import com.agit.app.data.model.CommitDiff
import com.agit.app.data.model.DiffLineKind
import com.agit.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitDetailScreen(nc: NavHostController) {
    val repoId = nc.currentBackStackEntry?.arguments?.getString("repoId") ?: ""
    val commitId = nc.currentBackStackEntry?.arguments?.getString("commitId") ?: ""
    var diff by remember { mutableStateOf<CommitDiff?>(null) }

    LaunchedEffect(repoId, commitId) {
        runCatching {
            withContext(Dispatchers.IO) { AppContainer.vcs(repoId).diff(commitId) }
        }.onSuccess { diff = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(diff?.commit?.shortId ?: "提交详情") },
                navigationIcon = {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null,
                        Modifier.clickable { nc.popBackStack() }.padding(12.dp))
                }
            )
        }
    ) { pad ->
        val d = diff
        if (d != null) {
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(d.commit.message.ifBlank { "(无提交信息)" },
                                style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text("${d.commit.authorName} · ${Format.relative(d.commit.timestamp)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("+${d.additions}  -${d.deletions}  ·  ${d.filesChanged} 个文件",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                items(d.files) { file ->
                    Card(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(file.path, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                            if (file.isBinary) {
                                Text("（二进制文件）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Column(Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                    .padding(8.dp)) {
                                    file.lines.forEach { l ->
                                        val bg = when (l.kind) {
                                            DiffLineKind.ADD -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                                            DiffLineKind.REMOVE -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                            DiffLineKind.CONTEXT -> Color.Transparent
                                        }
                                        val fg = when (l.kind) {
                                            DiffLineKind.ADD -> MaterialTheme.colorScheme.secondary
                                            DiffLineKind.REMOVE -> MaterialTheme.colorScheme.error
                                            DiffLineKind.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Row(Modifier.fillMaxWidth().background(bg)
                                            .padding(horizontal = 4.dp, vertical = 1.dp)) {
                                            Text("${l.newNumber}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(40.dp))
                                            Text(l.text, style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace, color = fg,
                                                modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}


