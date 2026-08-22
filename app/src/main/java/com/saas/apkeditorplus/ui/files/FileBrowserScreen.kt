package com.saas.apkeditorplus.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.FileBrowserItem
import com.saas.apkeditorplus.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    path: String,
    items: List<FileBrowserItem>,
    keyword: String,
    showingExternal: Boolean,
    onKeywordChange: (String) -> Unit,
    onBack: () -> Unit,
    onItemClick: (FileBrowserItem) -> Unit,
    onSearch: () -> Unit,
    onPrimaryStorage: () -> Unit,
    onExternalStorage: () -> Unit,
    onAppStorage: () -> Unit
) {
    var storageMenu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = { Column { Text("Selecionar APK"); Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Voltar") } },
                actions = {
                    IconButton(onClick = { storageMenu = true }) {
                        Icon(painterResource(R.drawable.ic_sdcard_ext), "Armazenamento", tint = if (showingExternal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    DropdownMenu(expanded = storageMenu, onDismissRequest = { storageMenu = false }) {
                        DropdownMenuItem(text = { Text("Armazenamento interno") }, onClick = { storageMenu = false; onPrimaryStorage() })
                        DropdownMenuItem(text = { Text("Cartão SD") }, onClick = { storageMenu = false; onExternalStorage() })
                        DropdownMenuItem(text = { Text("Arquivos do app") }, onClick = { storageMenu = false; onAppStorage() })
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                label = { Text("Pesquisar APKs nesta pasta") },
                trailingIcon = { IconButton(onClick = onSearch) { Icon(painterResource(R.drawable.ic_search), "Pesquisar") } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { (if (it.parent) "parent:" else "file:") + it.file.absolutePath }) { item ->
                    UnifiedFileRow(
                        name = if (item.parent) ".." else item.file.name,
                        detail = if (item.parent) "Pasta anterior" else item.detail,
                        kind = classifyFile(item.file, item.parent),
                        thumbnail = item.icon,
                        onClick = { onItemClick(item) }
                    )
                    HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
