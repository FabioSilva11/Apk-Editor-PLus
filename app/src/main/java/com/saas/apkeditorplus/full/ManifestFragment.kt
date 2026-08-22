package com.saas.apkeditorplus.full

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.saas.apkeditorplus.AppSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.saas.apkeditorplus.FullEditActivity
import com.saas.apkeditorplus.R
import com.saas.apkeditorplus.TextEditBigActivity
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import com.saas.apkeditorplus.utils.AxmlDecoder
import java.io.File
import java.util.Stack
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManifestFragment : Fragment() {

    private data class ManifestLineRecord(
        val lineIndex: Int,
        var rawLine: String
    ) {
        var indent: Int = 0
        var text: String = rawLine
        var sectionStart: Int = lineIndex
        var sectionEnd: Int = lineIndex
        var collapsed: Boolean = false
        var deleted: Boolean = false

        init {
            refreshFromRawLine()
        }

        fun refreshFromRawLine() {
            indent = 0
            var working = rawLine
            while (working.startsWith("\t")) {
                indent += 1
                working = working.substring(1)
            }
            while (working.startsWith("    ")) {
                indent += 1
                working = working.substring(4)
            }
            text = working.trim()
            sectionStart = lineIndex
            sectionEnd = lineIndex
        }

        fun sectionTag(): String? {
            val source = text
            val startPos = when {
                lineIndex != sectionStart -> source.indexOf("</").takeIf { it >= 0 }?.plus(2)
                else -> source.indexOf('<').takeIf { it >= 0 }?.plus(1)
            } ?: return null
            var endPos = source.indexOf(' ', startPos)
            if (endPos == -1) {
                endPos = when {
                    text.endsWith("/>") -> source.indexOf("/>", startPos).takeIf { it >= 0 }
                    else -> source.indexOf('>', startPos).takeIf { it >= 0 }
                } ?: source.length
            }
            return source.substring(startPos, endPos).trim().takeIf { it.isNotBlank() }
        }

        fun nameAttribute(): String? {
            val matcher = Pattern.compile("android:name=\"([^\"]+)\"").matcher(text)
            return if (matcher.find()) matcher.group(1) else null
        }
    }

    private data class ParsedLine(
        val tag: String?,
        val attributes: LinkedHashMap<String, String>,
        val selfClosed: Boolean,
        val extraContent: String,
        val rawEditable: Boolean
    )

    private var manifestFile: File? = null
    private var allLines = mutableListOf<ManifestLineRecord>()
    private var visibleLines by mutableStateOf<List<ManifestLineRecord>>(emptyList())
    private var query by mutableStateOf("")
    private var loading by mutableStateOf(true)
    private var emptyMessage by mutableStateOf("")

    private val manifestDescriptions = mapOf(
        "action" to "Adds an action to an intent filter.",
        "activity" to "Declares an activity that implements part of the application's visual user interface.",
        "activity-alias" to "An alias for an activity.",
        "application" to "The declaration of the application and its components.",
        "category" to "Adds a category name to an intent filter.",
        "compatible-screens" to "Specifies each screen configuration with which the application is compatible.",
        "data" to "Adds a data specification to an intent filter.",
        "grant-uri-permission" to "Specifies which data subsets can be granted.",
        "instrumentation" to "Declares an instrumentation class.",
        "intent-filter" to "Specifies the types of intents that a component can respond to.",
        "manifest" to "The root element of the AndroidManifest.xml file.",
        "meta-data" to "A name-value pair for additional arbitrary data.",
        "path-permission" to "Defines a path and required permissions for a specific subset of provider data.",
        "permission" to "Declares a security permission.",
        "permission-group" to "Declares a logical grouping of related permissions.",
        "permission-tree" to "Declares the base name for a tree of permissions.",
        "provider" to "Declares a content provider component.",
        "receiver" to "Declares a broadcast receiver component.",
        "service" to "Declares a service component.",
        "supports-gl-texture" to "Declares a single GL texture compression format supported by the app.",
        "supports-screens" to "Lets you specify the screen sizes your application supports.",
        "uses-configuration" to "Indicates which hardware and software features the application requires.",
        "uses-feature" to "Declares a single hardware or software feature used by the application.",
        "uses-library" to "Specifies a shared library that the application must be linked against.",
        "uses-permission" to "Requests a permission that the application needs to operate correctly.",
        "uses-sdk" to "Declares compatibility with one or more Android API levels."
    )

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            compileAndRegisterManifest(reload = true)
        }
    }

    private val replaceManifestLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            replaceManifest(uri)
        }
    }

    companion object {
        private const val ARG_APK_PATH = "apk_path"

        fun newInstance(apkPath: String): ManifestFragment {
            return ManifestFragment().apply {
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
            setContent { ApkEditorTheme { ManifestContent() } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadManifest()
    }

    @Composable
    private fun ManifestContent() {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text("AndroidManifest.xml", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp, top = 12.dp))
                IconButton(onClick = ::openManifestInEditor) { Icon(Icons.Rounded.Edit, "Abrir editor de texto") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; refreshVisibleLines(it) },
                placeholder = { Text("Pesquisar no Manifest") },
                trailingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && visibleLines.isEmpty()) Text(emptyMessage, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                items(visibleLines, key = { it.lineIndex }) { record ->
                    Row(
                        Modifier.fillMaxWidth().clickable { showLineDialog(record) }
                            .padding(start = (8 + record.indent.coerceAtMost(8) * 12).dp, end = 4.dp, top = 7.dp, bottom = 7.dp)
                    ) {
                        val collapsable = record.sectionEnd > record.lineIndex && record.indent > 0
                        if (collapsable) {
                            IconButton(onClick = {
                                record.collapsed = !record.collapsed
                                refreshVisibleLines(query)
                            }) { Icon(if (record.collapsed) Icons.Rounded.ChevronRight else Icons.Rounded.ExpandMore, "Expandir seção") }
                        } else {
                            Box(Modifier.size(40.dp))
                        }
                        Text(
                            record.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(top = 10.dp)
                        )
                        IconButton(onClick = { showLineActions(record) }) { Icon(Icons.Rounded.MoreVert, "Ações") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    private fun host(): FullEditActivity = requireActivity() as FullEditActivity

    private fun apkPath(): String = arguments?.getString(ARG_APK_PATH).orEmpty()

    private fun loadManifest() {
        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true
        emptyMessage = ""

        viewLifecycleOwner.lifecycleScope.launch {
            val manifest = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditWorkspaceManager.getManifestFile(context, apkPath)
                }
            }.getOrElse { error ->
                manifestFile = null
                loading = false
                emptyMessage = error.message ?: getString(R.string.failed)
                return@launch
            }

            manifestFile = manifest
            val lines = runCatching {
                withContext(Dispatchers.IO) {
                    manifest.readLines()
                }
            }.getOrElse { error ->
                loading = false
                emptyMessage = error.message ?: getString(R.string.failed)
                return@launch
            }

            allLines = lines.mapIndexed { index, line -> ManifestLineRecord(index, line) }.toMutableList()
            initializeSections()
            loading = false
            refreshVisibleLines(query)
        }
    }

    private fun initializeSections() {
        allLines.forEach { record ->
            record.refreshFromRawLine()
            record.deleted = false
            record.collapsed = false
        }
        val stack = Stack<ManifestLineRecord>()
        try {
            allLines.forEach { record ->
                if (record.indent <= 0) {
                    return@forEach
                }
                when {
                    record.text.endsWith("/>") -> {
                        record.sectionStart = record.lineIndex
                        record.sectionEnd = record.lineIndex
                    }

                    record.text.startsWith("</") -> {
                        if (stack.isNotEmpty()) {
                            val startLine = stack.pop()
                            startLine.sectionEnd = record.lineIndex
                            record.sectionStart = startLine.lineIndex
                            record.sectionEnd = record.lineIndex
                        }
                    }

                    else -> {
                        record.sectionStart = record.lineIndex
                        stack.push(record)
                    }
                }
            }
        } catch (_: Throwable) {
            allLines.forEach { it.indent = 0 }
        }
    }

    private fun refreshVisibleLines(query: String) {
        val normalized = query.trim()
        visibleLines = if (normalized.isNotEmpty()) {
            allLines.filter { !it.deleted && it.rawLine.contains(normalized, ignoreCase = true) }
                .toList()
        } else {
            buildDisplayLines()
        }
        emptyMessage = if (allLines.isEmpty()) {
            getString(R.string.notfound_in_manifest)
        } else {
            getString(R.string.not_found)
        }
    }

    private fun buildDisplayLines(): List<ManifestLineRecord> {
        val result = mutableListOf<ManifestLineRecord>()
        var index = 0
        while (index < allLines.size) {
            val record = allLines[index]
            if (!record.deleted) {
                result += record
                if (record.collapsed && record.sectionEnd > record.lineIndex) {
                    index = record.sectionEnd
                }
            }
            index += 1
        }
        return result
    }

    private fun showLineActions(record: ManifestLineRecord) {
        val options = mutableListOf(
            getString(R.string.open_in_editor),
            getString(R.string.replace),
            getString(R.string.extract)
        )
        if (isSectionDeletable(record)) {
            options.add(getString(R.string.delete))
        }
        options.add(getString(R.string.copy_file_path))
        val title = record.nameAttribute() ?: record.sectionTag() ?: FullEditRepository.MANIFEST_ENTRY
        val description = manifestDescriptions[record.sectionTag()].orEmpty()
        val message = buildString {
            if (description.isNotBlank()) {
                append(description)
                append("\n\n")
            }
            append(record.rawLine)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.open_in_editor) -> openManifestInEditor()
                    getString(R.string.replace) -> replaceManifestLauncher.launch(
                        arrayOf("text/xml", "application/xml", "*/*")
                    )
                    getString(R.string.extract) -> exportManifest()
                    getString(R.string.delete) -> deleteSection(record)
                    getString(R.string.copy_file_path) -> copyManifestLine(record)
                }
            }
            .show()
    }

    private fun copyManifestLine(record: ManifestLineRecord) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("manifest_line", record.rawLine))
        Toast.makeText(
            requireContext(),
            getString(R.string.copied_to_clipboard, record.rawLine),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun deleteSection(record: ManifestLineRecord) {
        if (!isSectionDeletable(record)) {
            Toast.makeText(requireContext(), getString(R.string.section_undeletable), Toast.LENGTH_SHORT).show()
            return
        }
        for (index in record.sectionStart..record.sectionEnd) {
            allLines.getOrNull(index)?.deleted = true
        }
        persistManifest()
    }

    private fun isSectionDeletable(record: ManifestLineRecord): Boolean {
        val tag = record.sectionTag()
        return when (tag) {
            "manifest", "application" -> false
            "activity", "intent-filter" -> !containsMainAction(record)
            "action" -> !record.text.contains("android.intent.action.MAIN")
            "category" -> !record.text.contains("android.intent.category.LAUNCHER")
            else -> true
        }
    }

    private fun containsMainAction(record: ManifestLineRecord): Boolean {
        for (index in record.sectionStart..record.sectionEnd) {
            val line = allLines.getOrNull(index) ?: continue
            if (line.sectionTag() == "action" &&
                line.text.contains("android.intent.action.MAIN")
            ) {
                return true
            }
        }
        return false
    }

    private fun showLineDialog(record: ManifestLineRecord) {
        val parsed = parseLine(record.rawLine)
        val dialogView = layoutInflater.inflate(R.layout.dialog_manifest_line, null, false)
        val originalLineView = dialogView.findViewById<TextView>(R.id.line_data)
        val attributeContainer = dialogView.findViewById<LinearLayout>(R.id.attribute_container)
        val lineValueInput = dialogView.findViewById<EditText>(R.id.line_value)
        val addAttributeButton = dialogView.findViewById<Button>(R.id.add_attribute_button)

        originalLineView.text = record.rawLine
        val attributeFields = mutableListOf<Pair<EditText, EditText>>()

        if (parsed.rawEditable) {
            lineValueInput.isVisible = true
            lineValueInput.setText(record.rawLine)
        } else {
            parsed.attributes.forEach { (key, value) ->
                attributeFields += addAttributeRow(attributeContainer, key, value)
            }
            addAttributeButton.isVisible = true
            addAttributeButton.setOnClickListener {
                showAddAttributeDialog { key, value ->
                    attributeFields += addAttributeRow(attributeContainer, key, value)
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btn_line_cancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.btn_line_ok).setOnClickListener {
            val newLine = if (parsed.rawEditable) {
                lineValueInput.text?.toString().orEmpty()
            } else {
                buildLine(parsed, attributeFields)
            }
            updateLine(record, newLine)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun addAttributeRow(
        container: LinearLayout,
        key: String,
        value: String
    ): Pair<EditText, EditText> {
        val row = layoutInflater.inflate(R.layout.item_manifest_attribute, container, false)
        val nameInput = row.findViewById<EditText>(R.id.attribute_name)
        val valueInput = row.findViewById<EditText>(R.id.attribute_value)
        nameInput.setText(key)
        valueInput.setText(value)
        container.addView(row)
        return nameInput to valueInput
    }

    private fun showAddAttributeDialog(onAdded: (key: String, value: String) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manifest_add_attribute, null, false)
        val keyInput = dialogView.findViewById<EditText>(R.id.key_edit)
        val valueInput = dialogView.findViewById<EditText>(R.id.value_edit)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_key_value)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val key = keyInput.text?.toString().orEmpty().trim()
                val value = valueInput.text?.toString().orEmpty()
                if (key.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.empty_key_tip), Toast.LENGTH_SHORT).show()
                } else {
                    onAdded(key, value)
                }
            }
            .setNegativeButton(R.string.colormixer_cancel, null)
            .show()
    }

    private fun parseLine(rawLine: String): ParsedLine {
        val trimmed = rawLine.trim()
        if (!trimmed.startsWith("<") || trimmed.startsWith("</") || trimmed.startsWith("<?")) {
            return ParsedLine(null, linkedMapOf(), false, "", true)
        }

        val tagSegment = trimmed.substringBefore('>') + if (trimmed.contains('>')) ">" else ""
        val extraContent = trimmed.substringAfter('>', "")
        val selfClosed = tagSegment.endsWith("/>")
        val tagMatch = Pattern.compile("^<\\s*([\\w:.-]+)").matcher(tagSegment)
        val tag = if (tagMatch.find()) tagMatch.group(1) else null
        if (tag == null) {
            return ParsedLine(null, linkedMapOf(), selfClosed, extraContent, true)
        }

        val attributePattern = Pattern.compile("([\\w:.-]+)=\"([^\"]*)\"")
        val matcher = attributePattern.matcher(tagSegment)
        val attributes = linkedMapOf<String, String>()
        while (matcher.find()) {
            val key = matcher.group(1) ?: continue
            val value = matcher.group(2) ?: continue
            attributes[key] = value
        }
        return ParsedLine(tag, attributes, selfClosed, extraContent, false)
    }

    private fun buildLine(
        parsed: ParsedLine,
        fields: List<Pair<EditText, EditText>>
    ): String {
        val tag = parsed.tag ?: return ""
        val parts = mutableListOf<String>()
        parts += "<$tag"
        fields.forEach { (nameInput, valueInput) ->
            val key = nameInput.text?.toString().orEmpty().trim()
            if (key.isBlank()) {
                return@forEach
            }
            val value = valueInput.text?.toString().orEmpty()
            parts += "$key=\"$value\""
        }
        val closing = if (parsed.selfClosed) "/>" else ">"
        return buildString {
            append(parts.joinToString(" "))
            append(closing)
            append(parsed.extraContent)
        }
    }

    private fun updateLine(record: ManifestLineRecord, newLine: String) {
        val target = allLines.getOrNull(record.lineIndex) ?: return
        target.rawLine = newLine
        target.refreshFromRawLine()
        initializeSections()
        persistManifest()
    }

    private fun persistManifest() {
        val file = manifestFile ?: return
        val content = buildString {
            allLines.filterNot { it.deleted }.forEachIndexed { index, record ->
                append(record.rawLine)
                if (index < allLines.count { !it.deleted } - 1) {
                    append('\n')
                }
            }
        }
        file.writeText(content)
        refreshVisibleLines(query)
        compileAndRegisterManifest(reload = false)
    }

    private fun compileAndRegisterManifest(reload: Boolean) {
        val context = requireContext().applicationContext
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val compiled = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditWorkspaceManager.compileManifest(context, apkPath())
                }
            }.getOrElse { error ->
                loading = false
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.failed),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            host().registerModifiedEntry(FullEditRepository.MANIFEST_ENTRY, compiled)
            loading = false
            if (reload) loadManifest()
        }
    }

    private fun replaceManifest(uri: Uri) {
        val file = manifestFile
        if (file == null || !file.exists()) {
            Toast.makeText(requireContext(), getString(R.string.failed), Toast.LENGTH_SHORT).show()
            return
        }

        val resolver = requireContext().applicationContext.contentResolver
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (isBinaryXml(bytes)) {
                            AxmlDecoder().decode(
                                bytes.inputStream(),
                                file.outputStream()
                            ).also { decoded ->
                                check(decoded) { "Failed to decode selected Manifest" }
                            }
                        } else {
                            file.writeBytes(bytes)
                        }
                    } ?: error("Failed to read selected file")
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
            Toast.makeText(requireContext(), getString(R.string.file_saved), Toast.LENGTH_SHORT).show()
            compileAndRegisterManifest(reload = true)
        }
    }

    private fun isBinaryXml(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val magic = (bytes[0].toInt() and 0xff) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 24)
        return magic == 0x00080003
    }

    private fun exportManifest() {
        val file = manifestFile
        if (file == null || !file.exists()) {
            Toast.makeText(requireContext(), getString(R.string.failed), Toast.LENGTH_SHORT).show()
            return
        }

        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val outputFile = runCatching {
                withContext(Dispatchers.IO) {
                    val exportDir = requireContext().applicationContext
                        .getExternalFilesDir("full_edit_export")
                        ?: requireContext().applicationContext.filesDir
                    exportDir.mkdirs()
                    val overwrite = AppSettings.prefs(requireContext())
                        .getString(AppSettings.FILE_RENAME_MODE, "auto") == "overwrite"
                    val targetFile = AppSettings.exportTarget(
                        exportDir,
                        FullEditRepository.MANIFEST_ENTRY,
                        overwrite
                    )
                    file.copyTo(targetFile, overwrite = true)
                    targetFile
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
            Toast.makeText(requireContext(), outputFile.absolutePath, Toast.LENGTH_LONG).show()
        }
    }

    private fun openManifestInEditor() {
        val file = manifestFile
        if (file == null || !file.exists()) {
            Toast.makeText(requireContext(), getString(R.string.failed), Toast.LENGTH_SHORT).show()
            return
        }

        editorLauncher.launch(
            Intent(requireContext(), TextEditBigActivity::class.java).apply {
                putExtra("filePath", file.absolutePath)
                putExtra("fileName", FullEditRepository.MANIFEST_ENTRY)
            }
        )
    }

}
