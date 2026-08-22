package com.saas.apkeditorplus.full

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.saas.apkeditorplus.FullEditActivity
import com.saas.apkeditorplus.R
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TypedResourcesFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ApkEditorTheme { ResourceEditor() } }
        }

    @Composable
    private fun ResourceEditor() {
        var kind by remember { mutableStateOf(FullEditWorkspaceManager.TypedResourceKind.COLOR) }
        var resources by remember { mutableStateOf<List<FullEditWorkspaceManager.TypedResourceItem>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var editing by remember { mutableStateOf<FullEditWorkspaceManager.TypedResourceItem?>(null) }
        var revision by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(kind, revision, host().changesRevision) {
            loading = true
            resources = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditWorkspaceManager.readTypedResources(requireContext().applicationContext, apkPath(), kind)
                }
            }.getOrElse {
                Toast.makeText(requireContext(), it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show()
                emptyList()
            }
            loading = false
        }

        Column(Modifier.fillMaxSize()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(FullEditWorkspaceManager.TypedResourceKind.entries) { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = option },
                        label = { Text(option.title) }
                    )
                }
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (resources.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhum recurso do tipo ${kind.title.lowercase()} neste APK")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(resources, key = { "${it.kind}:${it.name}" }) { item ->
                        ElevatedCard(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable { editing = item }
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (item.kind == FullEditWorkspaceManager.TypedResourceKind.COLOR) {
                                    colorOrNull(item.value)?.let { color ->
                                        Box(Modifier.size(36.dp).background(color, MaterialTheme.shapes.small))
                                    }
                                }
                                Column(Modifier.padding(start = if (item.kind == FullEditWorkspaceManager.TypedResourceKind.COLOR) 12.dp else 0.dp)) {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                                    Text(item.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(item.sourceFile, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    item { Box(Modifier.padding(bottom = 12.dp)) }
                }
            }
        }

        editing?.let { item ->
            var value by remember(item) { mutableStateOf(item.value) }
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text(item.name) },
                text = {
                    Column {
                        if (item.kind == FullEditWorkspaceManager.TypedResourceKind.COLOR) {
                            colorOrNull(value)?.let { color ->
                                Box(Modifier.fillMaxWidth().size(54.dp).background(color, MaterialTheme.shapes.medium))
                            }
                        }
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            minLines = if (item.kind in setOf(
                                    FullEditWorkspaceManager.TypedResourceKind.PLURALS,
                                    FullEditWorkspaceManager.TypedResourceKind.STRING_ARRAY
                                )) 4 else 1,
                            label = { Text(item.kind.title) }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            loading = true
                            val compiled = runCatching {
                                withContext(Dispatchers.IO) {
                                    FullEditWorkspaceManager.saveTypedResource(
                                        requireContext().applicationContext,
                                        apkPath(),
                                        item,
                                        value
                                    )
                                }
                            }
                            compiled.onSuccess {
                                host().registerModifiedEntry(FullEditRepository.RESOURCES_ENTRY, it)
                                editing = null
                                revision++
                            }.onFailure {
                                Toast.makeText(requireContext(), it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show()
                            }
                            loading = false
                        }
                    }) { Text("Salvar") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } }
            )
        }
    }

    private fun colorOrNull(value: String): Color? = runCatching {
        Color(android.graphics.Color.parseColor(value.trim()))
    }.getOrNull()

    private fun host() = requireActivity() as FullEditActivity
    private fun apkPath() = requireArguments().getString(ARG_APK_PATH).orEmpty()

    companion object {
        private const val ARG_APK_PATH = "apk_path"
        fun newInstance(apkPath: String) = TypedResourcesFragment().apply {
            arguments = Bundle().apply { putString(ARG_APK_PATH, apkPath) }
        }
    }
}
