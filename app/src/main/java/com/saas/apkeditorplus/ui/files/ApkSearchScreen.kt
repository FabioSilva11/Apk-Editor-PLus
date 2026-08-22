package com.saas.apkeditorplus.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.R
import java.io.File

data class ApkSearchItem(
    val file: File,
    val label: String,
    val packageName: String,
    val icon: ImageBitmap?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkSearchScreen(
    keyword: String,
    items: List<ApkSearchItem>,
    searching: Boolean,
    onBack: () -> Unit,
    onItemClick: (ApkSearchItem) -> Unit
) {
    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = {
                    Column {
                        Text("APKs encontrados: ${items.size}")
                        if (keyword.isNotBlank()) Text("Busca: $keyword", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Voltar") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searching) LinearProgressIndicator()
            if (!searching && items.isEmpty()) {
                Text("Nenhum APK encontrado", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.file.absolutePath }) { item ->
                    UnifiedFileRow(
                        name = item.label.ifBlank { item.file.nameWithoutExtension },
                        detail = item.packageName.ifBlank { item.file.absolutePath },
                        kind = FileVisualKind.APK,
                        thumbnail = item.icon,
                        onClick = { onItemClick(item) }
                    )
                    HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
