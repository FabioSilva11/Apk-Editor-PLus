package com.saas.apkeditorplus.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolInputView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    fileName: String,
    editor: CodeEditor,
    symbolInput: SymbolInputView,
    showSymbolInput: Boolean,
    searchVisible: Boolean,
    searchQuery: String,
    replacement: String,
    position: String,
    canUndo: Boolean,
    canRedo: Boolean,
    wordWrap: Boolean,
    lineNumbers: Boolean,
    regex: Boolean,
    matchCase: Boolean,
    wholeWord: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearchChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onWordWrapChange: (Boolean) -> Unit,
    onLineNumbersChange: (Boolean) -> Unit,
    onRegexChange: (Boolean) -> Unit,
    onMatchCaseChange: (Boolean) -> Unit,
    onWholeWordChange: (Boolean) -> Unit,
    onChooseLanguage: () -> Unit,
    onChooseTheme: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = { Text(fileName, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Voltar") } },
                actions = {
                    IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.Rounded.Undo, "Desfazer") }
                    IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.Rounded.Redo, "Refazer") }
                    IconButton(onClick = onToggleSearch) { Icon(Icons.Rounded.Search, "Pesquisar") }
                    IconButton(onClick = onSave) { Icon(Icons.Rounded.Save, "Salvar") }
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                    DropdownMenu(menuExpanded, { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Quebra automática de linha") },
                            trailingIcon = { Switch(wordWrap, onWordWrapChange) },
                            onClick = { onWordWrapChange(!wordWrap) }
                        )
                        DropdownMenuItem(
                            text = { Text("Números de linha") },
                            trailingIcon = { Switch(lineNumbers, onLineNumbersChange) },
                            onClick = { onLineNumbersChange(!lineNumbers) }
                        )
                        DropdownMenuItem(text = { Text("Linguagem") }, onClick = { menuExpanded = false; onChooseLanguage() })
                        DropdownMenuItem(text = { Text("Tema do editor") }, onClick = { menuExpanded = false; onChooseTheme() })
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchVisible) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row {
                        OutlinedTextField(searchQuery, onSearchChange, Modifier.weight(1f), label = { Text("Pesquisar") }, singleLine = true)
                        OutlinedTextField(replacement, onReplacementChange, Modifier.weight(1f).padding(start = 6.dp), label = { Text("Substituir") }, singleLine = true)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Button(onClick = onPrevious, modifier = Modifier.padding(end = 4.dp)) { Text("Anterior") }
                        Button(onClick = onNext, modifier = Modifier.padding(end = 4.dp)) { Text("Próximo") }
                        Button(onClick = onReplace, modifier = Modifier.padding(end = 4.dp)) { Text("Substituir") }
                        Button(onClick = onReplaceAll, modifier = Modifier.padding(end = 8.dp)) { Text("Todos") }
                        SearchOption("Regex", regex, onRegexChange)
                        SearchOption("Maiúsculas", matchCase, onMatchCaseChange)
                        SearchOption("Palavra", wholeWord, onWholeWordChange)
                    }
                }
            }
            AndroidView(factory = { editor }, modifier = Modifier.weight(1f).fillMaxWidth())
            if (showSymbolInput) {
                AndroidView(factory = { symbolInput }, modifier = Modifier.fillMaxWidth().height(42.dp))
            }
            Text(position, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun SearchOption(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label) },
        modifier = Modifier.padding(end = 4.dp)
    )
}
