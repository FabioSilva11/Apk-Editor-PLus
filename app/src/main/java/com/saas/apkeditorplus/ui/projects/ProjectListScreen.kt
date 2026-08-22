package com.saas.apkeditorplus.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.ProjectStore
import com.saas.apkeditorplus.R
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    projects: List<ProjectStore.Project>,
    onBack: () -> Unit,
    onOpen: (ProjectStore.Project) -> Unit,
    onDelete: (ProjectStore.Project) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<ProjectStore.Project?>(null) }
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("Projetos")
                        Text(
                            "Edições salvas automaticamente",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_close), "Voltar")
                    }
                }
            )
        }
    ) { padding ->
        if (projects.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(painterResource(R.drawable.ic_project_shortcut), null)
                Text("Nenhum projeto salvo", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "As mudanças feitas no Full Edit aparecerão aqui.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    Card(
                        onClick = { onOpen(project) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(project.displayName, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    project.apkPath,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${project.modifiedFiles.size} alterados • ${project.deletedEntries.size} excluídos • " +
                                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                            .format(Date(project.updatedAt)),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                val sourceLabel = when (project.sourceStatus) {
                                    ProjectStore.SourceStatus.VALID -> "APK original verificado"
                                    ProjectStore.SourceStatus.MISSING -> "APK original não encontrado"
                                    ProjectStore.SourceStatus.CHANGED -> "APK original foi alterado"
                                    ProjectStore.SourceStatus.UNVERIFIED -> "APK original ainda não validado"
                                }
                                Text(
                                    sourceLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (project.sourceStatus == ProjectStore.SourceStatus.VALID) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                            IconButton(onClick = { pendingDelete = project }) {
                                Icon(painterResource(R.drawable.ic_close), "Excluir")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Excluir projeto?") },
            text = { Text("O APK original não será removido. Somente as alterações salvas serão apagadas.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(project)
                    pendingDelete = null
                }) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
            }
        )
    }
}
