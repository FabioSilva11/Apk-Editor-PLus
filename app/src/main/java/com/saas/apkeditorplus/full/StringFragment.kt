package com.saas.apkeditorplus.full

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.saas.apkeditorplus.FullEditActivity
import com.saas.apkeditorplus.R
import com.saas.apkeditorplus.TextEditBigActivity
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StringFragment : Fragment() {

    private data class LanguageOption(
        val qualifier: String,
        val label: String,
        val symbol: String
    )

    private var languageOptions by mutableStateOf<List<LanguageOption>>(emptyList())
    private var selectedQualifier by mutableStateOf("")
    private var allItems = emptyList<FullEditRepository.StringResourceItem>()
    private var visibleItems by mutableStateOf<List<FullEditRepository.StringResourceItem>>(emptyList())
    private var query by mutableStateOf("")
    private var loading by mutableStateOf(true)
    private var emptyMessage by mutableStateOf("")
    private var pendingEditorFile: File? = null

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val editedFile = pendingEditorFile ?: return@registerForActivityResult
        pendingEditorFile = null
        if (result.resultCode == Activity.RESULT_OK) {
            applyEditedStringsFile(editedFile)
        }
    }

    companion object {
        private const val ARG_APK_PATH = "apk_path"

        fun newInstance(apkPath: String): StringFragment {
            return StringFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_APK_PATH, apkPath)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ApkEditorTheme { StringsContent() } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadLanguages()
    }

    @Composable
    private fun StringsContent() {
        var languageMenu by remember { mutableStateOf(false) }
        var addLanguageDialog by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                IconButton(onClick = { addLanguageDialog = true }) { Icon(Icons.Rounded.Add, "Adicionar idioma") }
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    androidx.compose.material3.TextButton(onClick = { languageMenu = true }, Modifier.fillMaxWidth()) {
                        val selected = languageOptions.firstOrNull { it.qualifier == selectedQualifier }
                        Text(selected?.symbol.orEmpty(), Modifier.padding(end = 8.dp))
                        Text(selected?.label ?: "Idioma padrão", Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Rounded.ArrowDropDown, null)
                    }
                    DropdownMenu(languageMenu, { languageMenu = false }) {
                        languageOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = { Text(option.symbol) },
                                onClick = {
                                    languageMenu = false
                                    if (selectedQualifier != option.qualifier) {
                                        selectedQualifier = option.qualifier
                                        loadStrings()
                                    }
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = ::openCurrentLanguageInEditor) { Icon(Icons.Rounded.Edit, "Editar arquivo do idioma") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; applyFilter(it) },
                placeholder = { Text("Pesquisar chave ou valor") },
                trailingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && visibleItems.isEmpty()) Text(emptyMessage, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                items(visibleItems, key = { it.name }) { item ->
                    val itemColor = if (item.needsTranslation) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    ListItem(
                        headlineContent = { Text(item.name, color = itemColor, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text(item.value.orEmpty(), color = itemColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (item.needsTranslation) {
                                    Text(
                                        "Precisa de tradução",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().clickable { showEditValueDialog(item) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        if (addLanguageDialog) {
            AddLanguageDialog(
                existingQualifiers = languageOptions.map(LanguageOption::qualifier),
                onDismiss = { addLanguageDialog = false },
                onAdd = { qualifier ->
                    addLanguageDialog = false
                    createLanguage(qualifier)
                }
            )
        }
    }

    @Composable
    private fun AddLanguageDialog(
        existingQualifiers: List<String>,
        onDismiss: () -> Unit,
        onAdd: (String) -> Unit
    ) {
        var search by remember { mutableStateOf("") }
        val available = FullEditLanguageCatalog.missingLanguages(existingQualifiers)
            .filter { option ->
                search.isBlank() || option.label.contains(search, ignoreCase = true) ||
                    option.qualifier.contains(search, ignoreCase = true)
            }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Adicionar idioma") },
            text = {
                Column {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Pesquisar idioma") },
                        trailingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    if (available.isEmpty()) {
                        Text(
                            if (search.isBlank()) "Todos os idiomas disponíveis já foram adicionados."
                            else "Nenhum idioma encontrado.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                            items(available, key = { it.qualifier }) { option ->
                                ListItem(
                                    headlineContent = { Text(option.label) },
                                    supportingContent = { Text(option.qualifier) },
                                    leadingContent = { Text(option.symbol, Modifier.width(32.dp)) },
                                    modifier = Modifier.fillMaxWidth().clickable { onAdd(option.qualifier) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
        )
    }

    private fun host(): FullEditActivity = requireActivity() as FullEditActivity

    private fun apkPath(): String = arguments?.getString(ARG_APK_PATH).orEmpty()

    private fun loadLanguages(targetQualifier: String? = selectedQualifier) {
        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true
        emptyMessage = ""

        viewLifecycleOwner.lifecycleScope.launch {
            val qualifiers = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditWorkspaceManager.listStringLocales(context, apkPath)
                }
            }.getOrElse { error ->
                loading = false
                emptyMessage = error.message ?: getString(R.string.failed)
                return@launch
            }

            languageOptions = qualifiers
                .ifEmpty { listOf("") }
                .map { qualifier ->
                    val catalogEntry = FullEditLanguageCatalog.entryForQualifier(qualifier)
                    LanguageOption(
                        qualifier = qualifier,
                        label = catalogEntry.label,
                        symbol = catalogEntry.symbol
                    )
                }

            val targetIndex = languageOptions.indexOfFirst { it.qualifier == targetQualifier }
                .takeIf { it >= 0 }
                ?: 0
            selectedQualifier = languageOptions.getOrNull(targetIndex)?.qualifier.orEmpty()

            loadStrings()
        }
    }

    private fun loadStrings() {
        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true
        emptyMessage = ""

        viewLifecycleOwner.lifecycleScope.launch {
            val items = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditWorkspaceManager.readStringResources(context, apkPath, selectedQualifier)
                }
            }.getOrElse { error ->
                allItems = emptyList()
                visibleItems = emptyList()
                loading = false
                emptyMessage = error.message ?: getString(R.string.failed)
                return@launch
            }

            loading = false
            allItems = items
            applyFilter(query)
        }
    }

    private fun applyFilter(query: String) {
        val normalizedQuery = query.trim()
        visibleItems = if (normalizedQuery.isEmpty()) {
            allItems
        } else {
            allItems.filter { item ->
                item.name.contains(normalizedQuery, ignoreCase = true) ||
                    item.value.orEmpty().contains(normalizedQuery, ignoreCase = true)
            }
        }

        emptyMessage = if (allItems.isEmpty()) {
            getString(R.string.full_edit_no_string_resources)
        } else {
            getString(R.string.not_found)
        }
    }

    private fun showEditValueDialog(item: FullEditRepository.StringResourceItem) {
        val context = requireContext()
        val dialogView = layoutInflater.inflate(R.layout.dialog_string_value, null, false)
        val keyView = dialogView.findViewById<TextView>(R.id.key)
        val valueInput = dialogView.findViewById<EditText>(R.id.value)
        keyView.text = item.name
        valueInput.setText(item.value.orEmpty())
        valueInput.setSelection(valueInput.text?.length ?: 0)
        valueInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        AppCompatAlertDialog.Builder(context)
            .setTitle(R.string.edit_string_value)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                saveSingleValue(item.name, valueInput.text?.toString().orEmpty())
            }
            .setNeutralButton(R.string.copy_file_path) { _, _ ->
                copyKey(item.name)
            }
            .setNegativeButton(R.string.colormixer_cancel, null)
            .show()
    }

    private fun copyKey(key: String) {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(key, key))
        Toast.makeText(
            requireContext(),
            getString(R.string.copied_to_clipboard, key),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun saveSingleValue(name: String, newValue: String) {
        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val compiledFile = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditWorkspaceManager.saveSingleStringOverride(
                        context = context,
                        apkPath = apkPath,
                        localeQualifier = selectedQualifier,
                        name = name,
                        newValue = newValue
                    )
                }
            }.getOrElse { error ->
                loading = false
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            host().registerModifiedEntry(FullEditRepository.RESOURCES_ENTRY, compiledFile)
            Toast.makeText(
                requireContext(),
                getString(R.string.save_succeed_tip, 1),
                Toast.LENGTH_SHORT
            ).show()
            loadLanguages(selectedQualifier)
        }
    }

    private fun createLanguage(rawQualifier: String) {
        val qualifier = rawQualifier.trim()
        if (!qualifier.matches(Regex("^-([A-Za-z]{2,3})(-r[A-Za-z]{2})?$"))) {
            Toast.makeText(requireContext(), getString(R.string.invalid_lang_code), Toast.LENGTH_SHORT).show()
            return
        }

        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val compiledFile = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditWorkspaceManager.addLanguageLikeOriginal(context, apkPath, qualifier)
                }
            }.getOrElse { error ->
                loading = false
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.lang_exist),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            host().registerModifiedEntry(FullEditRepository.RESOURCES_ENTRY, compiledFile)
            Toast.makeText(
                requireContext(),
                getString(R.string.file_added, qualifier),
                Toast.LENGTH_SHORT
            ).show()
            loadLanguages(qualifier)
        }
    }

    private fun openCurrentLanguageInEditor() {
        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val editorFile = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditWorkspaceManager.exportStringEditorFile(
                        context = context,
                        apkPath = apkPath,
                        localeQualifier = selectedQualifier
                    )
                }
            }.getOrElse { error ->
                loading = false
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            loading = false
            pendingEditorFile = editorFile
            editorLauncher.launch(
                Intent(requireContext(), TextEditBigActivity::class.java).apply {
                    putExtra("filePath", editorFile.absolutePath)
                    putExtra("fileName", editorFile.name)
                }
            )
        }
    }

    private fun applyEditedStringsFile(editedFile: File) {
        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val compiledFile = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditWorkspaceManager.applyEditedStringsFile(
                        context = context,
                        apkPath = apkPath,
                        localeQualifier = selectedQualifier,
                        editedStringsXml = editedFile
                    )
                }
            }.getOrElse { error ->
                loading = false
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            host().registerModifiedEntry(FullEditRepository.RESOURCES_ENTRY, compiledFile)
            Toast.makeText(requireContext(), getString(R.string.file_saved), Toast.LENGTH_SHORT).show()
            loadLanguages(selectedQualifier)
        }
    }

}
