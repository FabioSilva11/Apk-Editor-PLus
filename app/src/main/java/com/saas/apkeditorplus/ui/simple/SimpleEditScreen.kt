package com.saas.apkeditorplus.ui.simple

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.R
import com.saas.apkeditorplus.ui.files.UnifiedFileRow
import com.saas.apkeditorplus.ui.files.classifyFile

data class SimpleArchiveEntry(val displayName: String, val entryName: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleEditScreen(
    title: String,
    apkPath: String,
    loading: Boolean,
    selectedTab: Int,
    entries: List<SimpleArchiveEntry>,
    modifiedNames: Set<String>,
    onBack: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onEntryClick: (SimpleArchiveEntry) -> Unit,
    onClearReplacement: (SimpleArchiveEntry) -> Unit,
    onSave: () -> Unit
) {
    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = { Column { Text(title); Text(apkPath, style = MaterialTheme.typography.labelSmall, maxLines = 1) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Voltar") } }
            )
        },
        bottomBar = {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(if (modifiedNames.isEmpty()) "Fechar" else "Salvar ${modifiedNames.size} alteração(ões)")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                val labels = listOf("Arquivos", "Imagens", "Áudios")
                val icons = listOf(Icons.Rounded.Description, Icons.Rounded.Image, Icons.Rounded.AudioFile)
                labels.forEachIndexed { index, label ->
                    FilterChip(
                        selected = selectedTab == index,
                        onClick = { onTabSelected(index) },
                        label = { Text(label) },
                        leadingIcon = { Icon(icons[index], null) },
                        modifier = Modifier.weight(1f).padding(horizontal = 3.dp)
                    )
                }
            }
            Text(
                "${entries.size} itens • ${modifiedNames.size} alterados",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.entryName }) { entry ->
                    val modified = entry.entryName in modifiedNames
                    UnifiedFileRow(
                        name = entry.displayName,
                        detail = entry.entryName,
                        kind = classifyFile(entry.entryName),
                        modified = modified,
                        onDelete = if (modified) ({ onClearReplacement(entry) }) else null,
                        onClick = { onEntryClick(entry) }
                    )
                    HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
