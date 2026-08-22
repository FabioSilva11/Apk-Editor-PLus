package com.saas.apkeditorplus.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.R

data class ArchiveBrowserItem(val name: String, val fullPath: String, val directory: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveXmlBrowserScreen(
    title: String,
    path: String,
    items: List<ArchiveBrowserItem>,
    loading: Boolean,
    modifiedNames: Set<String>,
    onBack: () -> Unit,
    onItemClick: (ArchiveBrowserItem) -> Unit,
    onSave: () -> Unit,
    thumbnailLoader: (ArchiveBrowserItem) -> ImageBitmap?
) {
    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = { Column { Text(title); Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Voltar") } }
            )
        },
        bottomBar = {
            if (modifiedNames.isNotEmpty()) {
                Button(onClick = onSave, Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                    Text("Reconstruir com ${modifiedNames.size} alteração(ões)")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.fullPath + it.name }) { item ->
                    val kind = classifyFile(item.name, item.directory, item.name == "..")
                    UnifiedFileRow(
                        name = item.name,
                        detail = if (item.directory) "Pasta" else item.fullPath,
                        kind = kind,
                        thumbnailKey = item.fullPath.takeIf { kind == FileVisualKind.IMAGE },
                        thumbnailLoader = if (kind == FileVisualKind.IMAGE) {
                            { thumbnailLoader(item) }
                        } else null,
                        modified = item.fullPath in modifiedNames,
                        onClick = { onItemClick(item) }
                    )
                    HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
