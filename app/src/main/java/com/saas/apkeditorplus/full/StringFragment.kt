package com.saas.apkeditorplus.full

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
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
        val label: String
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
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                IconButton(onClick = ::showAddLanguageDialog) { Icon(Icons.Rounded.Add, "Adicionar idioma") }
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    androidx.compose.material3.TextButton(onClick = { languageMenu = true }, Modifier.fillMaxWidth()) {
                        Text(languageOptions.firstOrNull { it.qualifier == selectedQualifier }?.label ?: "Idioma padrão", Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Rounded.ArrowDropDown, null)
                    }
                    DropdownMenu(languageMenu, { languageMenu = false }) {
                        languageOptions.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                languageMenu = false
                                if (selectedQualifier != option.qualifier) {
                                    selectedQualifier = option.qualifier
                                    loadStrings()
                                }
                            })
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
                    ListItem(
                        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(item.value.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth().clickable { showEditValueDialog(item) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
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
                    LanguageOption(
                        qualifier = qualifier,
                        label = FullEditLanguageCatalog.labelForQualifier(qualifier)
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

        AlertDialog.Builder(context)
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

    private fun showAddLanguageDialog() {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 4)
        }
        val spinner = Spinner(context)
        val codeInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(FullEditLanguageCatalog.codeAt(FullEditLanguageCatalog.indexOfBestMatch(selectedQualifier)))
            hint = "-pt-rBR"
        }
        val codeLabel = TextView(context).apply {
            text = "Qualifier"
            setPadding(0, 20, 0, 8)
        }
        val dialogAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            FullEditLanguageCatalog.languageNames()
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = dialogAdapter
        spinner.setSelection(FullEditLanguageCatalog.indexOfBestMatch(selectedQualifier), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                codeInput.setText(FullEditLanguageCatalog.codeAt(position))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        container.addView(spinner)
        container.addView(codeLabel)
        container.addView(
            codeInput,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.add_a_language)
            .setView(container)
            .setNegativeButton(R.string.colormixer_cancel, null)
            .setPositiveButton(R.string.add, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val qualifier = codeInput.text?.toString().orEmpty().trim()
                if (!qualifier.matches(Regex("^-([A-Za-z]{2,3})(-r[A-Za-z]{2})?$"))) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.invalid_lang_code),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                createLanguage(qualifier)
            }
        }
        dialog.show()
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
