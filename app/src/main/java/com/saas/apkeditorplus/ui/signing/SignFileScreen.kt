package com.saas.apkeditorplus.ui.signing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.R
import com.saas.apkeditorplus.ui.files.UnifiedFileRow
import com.saas.apkeditorplus.ui.files.classifyFile
import com.saas.apkeditorplus.ui.files.decodeImageThumbnail
import com.saas.apkeditorplus.ui.files.FileVisualKind
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignFileScreen(
    currentPath: String,
    files: List<File>,
    onBack: () -> Unit,
    onFileClick: (File) -> Unit
) {
    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = {
                    Column {
                        Text("Assinar APK")
                        Text(currentPath, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Voltar") }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(files, key = { it.absolutePath }) { file ->
                val kind = classifyFile(file, file.name == "..")
                UnifiedFileRow(
                    name = file.name,
                    detail = if (file.isDirectory || file.name == "..") "Pasta" else formatSize(file.length()),
                    kind = kind,
                    thumbnailKey = file.absolutePath.takeIf { kind == FileVisualKind.IMAGE },
                    thumbnailLoader = if (kind == FileVisualKind.IMAGE) {
                        { decodeImageThumbnail(file) }
                    } else null,
                    onClick = { onFileClick(file) }
                )
                HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private fun formatSize(size: Long): String {
    if (size <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val group = (kotlin.math.ln(size.toDouble()) / kotlin.math.ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, group.toDouble()), units[group])
}
