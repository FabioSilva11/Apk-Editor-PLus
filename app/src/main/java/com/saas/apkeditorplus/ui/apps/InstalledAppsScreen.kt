package com.saas.apkeditorplus.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.saas.apkeditorplus.AppInfo
import com.saas.apkeditorplus.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppsScreen(
    apps: List<AppInfo>,
    loading: Boolean,
    showSystemApps: Boolean,
    onBack: () -> Unit,
    onShowUserApps: () -> Unit,
    onShowSystemApps: () -> Unit,
    onAppClick: (AppInfo) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(if (showSystemApps) "Aplicativos do sistema" else "Aplicativos do usuário")
                        Text("${apps.size} encontrados", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(painterResource(R.drawable.ic_search), "Filtrar")
                    }
                    DropdownMenu(menuExpanded, { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Aplicativos do usuário") },
                            onClick = { menuExpanded = false; onShowUserApps() }
                        )
                        DropdownMenuItem(
                            text = { Text("Aplicativos do sistema") },
                            onClick = { menuExpanded = false; onShowSystemApps() }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(apps, key = { it.packageName }) { app ->
                    Card(
                        onClick = { onAppClick(app) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            app.icon?.let {
                                Image(it.toBitmap(96, 96).asImageBitmap(), null, Modifier.size(48.dp))
                            } ?: Icon(
                                painterResource(R.drawable.ic_app_shortcut),
                                null,
                                Modifier.size(48.dp)
                            )
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
