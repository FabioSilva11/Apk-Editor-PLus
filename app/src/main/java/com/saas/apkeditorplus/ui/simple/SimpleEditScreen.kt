package com.saas.apkeditorplus.ui.simple

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.viewpager2.widget.ViewPager2
import com.saas.apkeditorplus.R
import com.saas.apkeditorplus.ui.files.FileVisualKind
import com.saas.apkeditorplus.ui.files.UnifiedFileRow
import com.saas.apkeditorplus.ui.files.classifyFile

data class SimpleArchiveEntry(
    val displayName: String,
    val entryName: String,
    val detail: String = entryName,
    val isDirectory: Boolean = false,
    val relatedEntries: List<String> = listOf(entryName)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleEditScreen(
    title: String,
    subtitle: String,
    icon: ImageBitmap?,
    loading: Boolean,
    selectedTab: Int,
    itemCounts: List<Int>,
    modifiedCount: Int,
    onBack: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onPagerReady: (ViewPager2) -> Unit,
    onSave: () -> Unit
) {
    val labels = listOf("Arquivos", "Imagens", "Áudios")
    val icons = listOf(Icons.Rounded.Description, Icons.Rounded.Image, Icons.Rounded.AudioFile)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) Image(icon, null, Modifier.size(42.dp), contentScale = ContentScale.Fit)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Voltar") } }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${itemCounts.getOrElse(selectedTab) { 0 }} itens • $modifiedCount alterados",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onSave) {
                        Text(if (modifiedCount == 0) "Fechar" else "Salvar")
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                labels.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { onTabSelected(index) },
                        text = { Text(label) },
                        icon = { Icon(icons[index], null, Modifier.size(20.dp)) }
                    )
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                factory = { context -> ViewPager2(context).also(onPagerReady) },
                update = { pager -> if (pager.currentItem != selectedTab) pager.setCurrentItem(selectedTab, false) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun SimpleEditPage(
    path: String,
    loading: Boolean,
    entries: List<SimpleArchiveEntry>,
    modifiedEntries: Set<String>,
    playingEntryName: String?,
    onNavigateUp: (() -> Unit)?,
    onOpen: (SimpleArchiveEntry) -> Unit,
    onReplace: (SimpleArchiveEntry) -> Unit,
    onExport: (SimpleArchiveEntry) -> Unit,
    onClearReplacement: (SimpleArchiveEntry) -> Unit,
    thumbnailLoader: (SimpleArchiveEntry) -> ImageBitmap?
) {
    Column(Modifier.fillMaxSize()) {
        if (path.isNotBlank()) {
            Text(
                "/$path",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize()) {
            if (onNavigateUp != null) {
                item("parent") {
                    UnifiedFileRow(
                        name = "..",
                        detail = "Pasta anterior",
                        kind = classifyFile("..", parent = true),
                        onClick = onNavigateUp
                    )
                    HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            items(entries, key = { it.entryName }) { entry ->
                val modified = entry.relatedEntries.any { it in modifiedEntries }
                val kind = classifyFile(entry.displayName, directory = entry.isDirectory)
                val detail = when {
                    playingEntryName == entry.entryName -> "Reproduzindo • ${entry.detail}"
                    else -> entry.detail
                }
                UnifiedFileRow(
                    name = entry.displayName,
                    detail = detail,
                    kind = kind,
                    thumbnailKey = entry.entryName.takeIf { kind == FileVisualKind.IMAGE },
                    thumbnailLoader = if (kind == FileVisualKind.IMAGE) {
                        { thumbnailLoader(entry) }
                    } else null,
                    modified = modified,
                    onReplace = if (entry.isDirectory) null else ({ onReplace(entry) }),
                    onExport = if (entry.isDirectory) null else ({ onExport(entry) }),
                    onDelete = if (modified) ({ onClearReplacement(entry) }) else null,
                    onClick = { onOpen(entry) }
                )
                HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
