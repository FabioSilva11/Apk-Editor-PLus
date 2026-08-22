package com.saas.apkeditorplus.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saas.apkeditorplus.full.DiffChangeKind
import com.saas.apkeditorplus.full.DiffLine
import com.saas.apkeditorplus.full.DiffLineKind
import com.saas.apkeditorplus.full.FileDiff

@Composable
fun DiffScreen(
    diffs: List<FileDiff>,
    loading: Boolean,
    onDiscard: (String) -> Unit
) {
    var expandedEntries by remember(diffs.map { it.entryName }) {
        mutableStateOf(diffs.firstOrNull()?.entryName?.let(::setOf).orEmpty())
    }
    var discardTarget by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Histórico de modificações", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${diffs.size} arquivo(s) com alterações pendentes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (loading) CircularProgressIndicator(Modifier.width(24.dp))
        }

        if (!loading && diffs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.Difference,
                        null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(54.dp)
                    )
                    Text("Nenhuma modificação", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "As alterações feitas em Strings, Arquivos e Manifest aparecerão aqui.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(diffs, key = FileDiff::entryName) { diff ->
                    val expanded = diff.entryName in expandedEntries
                    DiffFileCard(
                        diff = diff,
                        expanded = expanded,
                        onToggle = {
                            expandedEntries = if (expanded) expandedEntries - diff.entryName
                            else expandedEntries + diff.entryName
                        },
                        onDiscard = { discardTarget = diff.entryName }
                    )
                }
                item { Box(Modifier.padding(bottom = 10.dp)) }
            }
        }
    }

    discardTarget?.let { entryName ->
        AlertDialog(
            onDismissRequest = { discardTarget = null },
            title = { Text("Descartar modificação?") },
            text = { Text("Somente as alterações de “$entryName” serão descartadas.") },
            confirmButton = {
                TextButton(onClick = { onDiscard(entryName); discardTarget = null }) {
                    Text("Descartar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { discardTarget = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun DiffFileCard(
    diff: FileDiff,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDiscard: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(
                    diff.entryName.substringAfterLast('/').ifBlank { diff.entryName },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    diff.entryName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChangeBadge(diff.kind)
                    Text(
                        diff.summary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDiscard) {
                Icon(Icons.Rounded.DeleteOutline, "Descartar este arquivo", tint = MaterialTheme.colorScheme.error)
            }
        }
        if (expanded) {
            if (diff.lines.isEmpty()) {
                Text(
                    binaryStatusText(diff),
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Column(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    diff.lines.forEach { line -> DiffLineRow(line) }
                }
            }
        }
    }
}

@Composable
private fun ChangeBadge(kind: DiffChangeKind) {
    val (label, color) = when (kind) {
        DiffChangeKind.ADDED -> "Adicionado" to MaterialTheme.colorScheme.primary
        DiffChangeKind.MODIFIED -> "Modificado" to MaterialTheme.colorScheme.tertiary
        DiffChangeKind.DELETED -> "Excluído" to MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val background = when (line.kind) {
        DiffLineKind.ADDED -> Color(0xFFE7F6EB)
        DiffLineKind.REMOVED -> Color(0xFFFFE9E9)
        DiffLineKind.SEPARATOR -> Color(0xFFE8F0FE)
        DiffLineKind.CONTEXT -> MaterialTheme.colorScheme.surface
    }
    val prefix = when (line.kind) {
        DiffLineKind.ADDED -> "+"
        DiffLineKind.REMOVED -> "−"
        DiffLineKind.SEPARATOR -> ""
        DiffLineKind.CONTEXT -> " "
    }
    if (line.kind == DiffLineKind.SEPARATOR) {
        Text(
            line.text,
            modifier = Modifier.width(1_012.dp).background(background).padding(horizontal = 12.dp, vertical = 4.dp),
            color = Color(0xFF26364D),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        return
    }
    Row(
        Modifier.width(1_012.dp).background(background).padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(line.oldNumber?.toString().orEmpty(), Modifier.width(40.dp), color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(line.newNumber?.toString().orEmpty(), Modifier.width(40.dp), color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(prefix, Modifier.width(24.dp), color = lineMarkerColor(line.kind), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(line.text.ifEmpty { " " }, Modifier.width(908.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun lineMarkerColor(kind: DiffLineKind): Color = when (kind) {
    DiffLineKind.ADDED -> Color(0xFF1B7F3A)
    DiffLineKind.REMOVED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

private fun binaryStatusText(diff: FileDiff): String = when (diff.kind) {
    DiffChangeKind.ADDED -> "Arquivo binário adicionado\n${diff.summary}"
    DiffChangeKind.MODIFIED -> "Arquivo binário modificado\n${diff.summary}"
    DiffChangeKind.DELETED -> "Arquivo binário removido\n${diff.summary}"
}
