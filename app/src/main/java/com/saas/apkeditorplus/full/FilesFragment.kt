package com.saas.apkeditorplus.full

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Home
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.saas.apkeditorplus.AppSettings
import com.saas.apkeditorplus.FullEditActivity
import com.saas.apkeditorplus.ImageEditorActivity
import com.saas.apkeditorplus.R
import com.saas.apkeditorplus.TextEditBigActivity
import com.saas.apkeditorplus.ui.files.FileVisualKind
import com.saas.apkeditorplus.ui.files.UnifiedFileRow
import com.saas.apkeditorplus.ui.files.classifyFile
import com.saas.apkeditorplus.ui.files.decodeImageThumbnail
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FilesFragment : Fragment() {

    private enum class ItemKind {
        BACK,
        FOLDER,
        MANIFEST,
        DEX,
        XML,
        TEXT,
        BINARY
    }

    private data class BrowserItem(
        val entryName: String,
        val displayName: String,
        val detail: String,
        val isDirectory: Boolean,
        val kind: ItemKind,
        val modified: Boolean
    )

    private data class EditorTarget(
        val modifiedEntryName: String,
        val editorFile: File,
        val registerFile: File
    )

    private data class ReplacementTarget(
        val modifiedEntryName: String,
        val displayName: String,
        val workspace: FullEditRepository.SmaliWorkspace? = null,
        val workspaceRelativePath: String? = null
    )

    private data class AdditionTarget(
        val archivePath: String,
        val workspace: FullEditRepository.SmaliWorkspace?,
        val smaliPath: String
    )

    private data class ImageEditorTarget(val entryName: String, val file: File)

    private var currentArchivePath: String = ""
    private var currentSmaliWorkspace: FullEditRepository.SmaliWorkspace? = null
    private var currentSmaliPath: String = ""
    private var allItems = emptyList<BrowserItem>()
    private var visibleItems by mutableStateOf<List<BrowserItem>>(emptyList())
    private var query by mutableStateOf("")
    private var pathLabel by mutableStateOf("")
    private var loading by mutableStateOf(true)
    private var emptyMessage by mutableStateOf("")
    private var pendingEditorTarget: EditorTarget? = null
    private var pendingReplacementTarget: ReplacementTarget? = null
    private var pendingAdditionTarget: AdditionTarget? = null
    private var pendingImageEditorTarget: ImageEditorTarget? = null

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = pendingEditorTarget ?: return@registerForActivityResult
        pendingEditorTarget = null
        if (result.resultCode == Activity.RESULT_OK) {
            host().registerModifiedEntry(target.modifiedEntryName, target.registerFile)
            loadFiles()
        }
    }

    private val replacementLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = pendingReplacementTarget ?: return@registerForActivityResult
        pendingReplacementTarget = null
        if (uri != null) {
            replaceEntry(target, uri)
        }
    }

    private val addFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = pendingAdditionTarget ?: return@registerForActivityResult
        pendingAdditionTarget = null
        if (uri != null) {
            addSelectedFile(target, uri)
        }
    }

    private val imageEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = pendingImageEditorTarget
        pendingImageEditorTarget = null
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            host().registerModifiedEntry(target.entryName, target.file)
            loadFiles()
        }
    }

    companion object {
        private const val ARG_APK_PATH = "apk_path"

        fun newInstance(apkPath: String): FilesFragment {
            return FilesFragment().apply {
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
            setContent { ApkEditorTheme { FilesContent() } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadFiles()
    }

    @Composable
    private fun FilesContent() {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                IconButton(onClick = {
                    currentSmaliWorkspace = null
                    currentSmaliPath = ""
                    currentArchivePath = ""
                    loadFiles()
                }) { Icon(Icons.Rounded.Home, "Início") }
                IconButton(onClick = {
                    pendingAdditionTarget = currentAdditionTarget()
                    addFileLauncher.launch(arrayOf("*/*"))
                }) { Icon(Icons.Rounded.Add, "Adicionar arquivo") }
                IconButton(onClick = ::showAddFolderDialog) { Icon(Icons.Rounded.CreateNewFolder, "Nova pasta") }
            }
            if (pathLabel.isNotBlank()) {
                Text(pathLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; applyFilter(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("Pesquisar nome ou conteúdo") },
                singleLine = true,
                trailingIcon = { IconButton(onClick = { requestRecursiveSearch(query) }) { Icon(Icons.Rounded.Search, "Pesquisar") } }
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && visibleItems.isEmpty()) {
                Text(emptyMessage.ifBlank { "Nenhum arquivo" }, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(visibleItems, key = { it.entryName + it.kind.name }) { item ->
                    val actionVisible = item.kind != ItemKind.BACK && !item.isDirectory
                    val visualKind = when (item.kind) {
                        ItemKind.BACK -> FileVisualKind.PARENT
                        ItemKind.FOLDER -> FileVisualKind.FOLDER
                        ItemKind.MANIFEST, ItemKind.XML -> FileVisualKind.XML
                        ItemKind.DEX -> FileVisualKind.DEX
                        ItemKind.TEXT -> classifyFile(item.displayName)
                        ItemKind.BINARY -> classifyFile(item.displayName)
                    }
                    UnifiedFileRow(
                        name = item.displayName,
                        detail = item.detail,
                        kind = visualKind,
                        thumbnailKey = item.entryName.takeIf { visualKind == FileVisualKind.IMAGE },
                        thumbnailLoader = if (visualKind == FileVisualKind.IMAGE) {
                            { loadArchiveThumbnail(item) }
                        } else null,
                        modified = item.modified,
                        onReplace = if (actionVisible) ({ replaceArchiveItem(item) }) else null,
                        onExport = if (actionVisible) ({ exportItem(item) }) else null,
                        onDelete = if (item.kind != ItemKind.BACK) ({ requestDelete(item) }) else null,
                        onClick = { openItem(item) }
                    )
                    HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    private fun host(): FullEditActivity = requireActivity() as FullEditActivity

    private fun apkPath(): String = arguments?.getString(ARG_APK_PATH).orEmpty()

    private fun loadArchiveThumbnail(item: BrowserItem): ImageBitmap? {
        host().resolveModifiedEntry(item.entryName)?.takeIf(File::isFile)?.let { replacement ->
            return decodeImageThumbnail(replacement)
        }
        return ZipFile(apkPath()).use { zip ->
            val zipEntry = zip.getEntry(item.entryName) ?: return@use null
            decodeImageThumbnail(openStream = { zip.getInputStream(zipEntry) })
        }
    }

    private fun loadFiles() {
        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true
        emptyMessage = ""

        viewLifecycleOwner.lifecycleScope.launch {
            val items = runCatching {
                withContext(Dispatchers.IO) {
                    if (currentSmaliWorkspace != null) {
                        loadSmaliItems()
                    } else {
                        loadArchiveItems(context, apkPath)
                    }
                }
            }.getOrElse { error ->
                allItems = emptyList()
                visibleItems = emptyList()
                loading = false
                emptyMessage = error.message ?: getString(R.string.failed)
                return@launch
            }

            allItems = items
            loading = false
            pathLabel = buildCurrentPathLabel()
            applyFilter(query)
        }
    }

    private fun loadArchiveItems(context: android.content.Context, apkPath: String): List<BrowserItem> {
        val modifiedNames = host().modifiedEntryNames()
        val combined = linkedMapOf<String, BrowserItem>()

        FullEditRepository.listDirectory(apkPath, currentArchivePath)
            .filterNot { host().isEntryDeleted(it.entryName) }
            .forEach { item ->
                val modified = host().isEntryModified(item.entryName) ||
                    (item.isDirectory && modifiedNames.any { it.startsWith(item.entryName) })
                combined[item.entryName] = BrowserItem(
                    entryName = item.entryName,
                    displayName = item.displayName,
                    detail = buildArchiveDetail(item, modified),
                    isDirectory = item.isDirectory,
                    kind = resolveArchiveKind(item),
                    modified = modified
                )
            }

        modifiedNames.forEach { entryName ->
            if (host().isEntryDeleted(entryName) || !entryName.startsWith(currentArchivePath)) {
                return@forEach
            }
            val relativeName = entryName.removePrefix(currentArchivePath)
            if (relativeName.isBlank()) {
                return@forEach
            }
            val firstSlash = relativeName.indexOf('/')
            val isDirectory = firstSlash >= 0
            val displayName = if (isDirectory) relativeName.substring(0, firstSlash) else relativeName
            if (displayName.isBlank()) {
                return@forEach
            }
            val visibleEntryName = currentArchivePath + displayName + if (isDirectory) "/" else ""
            if (combined.containsKey(visibleEntryName)) {
                return@forEach
            }
            val kind = when {
                isDirectory -> ItemKind.FOLDER
                visibleEntryName == FullEditRepository.MANIFEST_ENTRY -> ItemKind.MANIFEST
                FullEditRepository.isDexEntry(visibleEntryName) -> ItemKind.DEX
                visibleEntryName.endsWith(".xml", ignoreCase = true) -> ItemKind.XML
                FullEditRepository.isEditableTextEntry(visibleEntryName) -> ItemKind.TEXT
                else -> ItemKind.BINARY
            }
            combined[visibleEntryName] = BrowserItem(
                entryName = visibleEntryName,
                displayName = displayName,
                detail = getString(R.string.str_modified),
                isDirectory = isDirectory,
                kind = kind,
                modified = true
            )
        }

        return combined.values.sortedWith(
            compareBy<BrowserItem> { it.kind != ItemKind.BACK }
                .thenBy { !it.isDirectory }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun loadSmaliItems(): List<BrowserItem> {
        val workspace = currentSmaliWorkspace ?: return emptyList()
        val dexModified = host().isEntryModified(workspace.dexEntryName)
        return FullEditRepository.listSmaliDirectory(workspace, currentSmaliPath).map { item ->
            val kind = if (item.displayName == "..") {
                ItemKind.BACK
            } else if (item.isDirectory) {
                ItemKind.FOLDER
            } else {
                ItemKind.TEXT
            }
            BrowserItem(
                entryName = item.entryName,
                displayName = item.displayName,
                detail = if (item.displayName == "..") {
                    getString(R.string.previous)
                } else if (dexModified) {
                    getString(R.string.str_modified)
                } else {
                    workspace.dexEntryName
                },
                isDirectory = item.isDirectory,
                kind = kind,
                modified = dexModified && item.displayName != ".."
            )
        }
    }

    private fun buildArchiveDetail(item: FullEditRepository.ArchiveItem, modified: Boolean): String {
        if (item.displayName == "..") {
            return getString(R.string.previous)
        }
        if (modified) {
            return getString(R.string.str_modified)
        }
        return when {
            item.isDirectory -> getString(R.string.folder_label)
            item.entryName == FullEditRepository.MANIFEST_ENTRY -> getString(R.string.manifest)
            FullEditRepository.isDexEntry(item.entryName) -> "Smali workspace"
            else -> item.entryName
        }
    }

    private fun resolveArchiveKind(item: FullEditRepository.ArchiveItem): ItemKind {
        if (item.displayName == "..") {
            return ItemKind.BACK
        }
        if (item.isDirectory) {
            return ItemKind.FOLDER
        }
        if (item.entryName == FullEditRepository.MANIFEST_ENTRY) {
            return ItemKind.MANIFEST
        }
        if (FullEditRepository.isDexEntry(item.entryName)) {
            return ItemKind.DEX
        }
        if (item.entryName.endsWith(".xml", ignoreCase = true)) {
            return ItemKind.XML
        }
        if (FullEditRepository.isEditableTextEntry(item.entryName)) {
            return ItemKind.TEXT
        }
        return ItemKind.BINARY
    }

    private fun buildCurrentPathLabel(): String {
        return if (currentSmaliWorkspace != null) {
            val workspace = currentSmaliWorkspace ?: return ""
            if (currentSmaliPath.isBlank()) {
                workspace.dexEntryName
            } else {
                "${workspace.dexEntryName}/${currentSmaliPath.removeSuffix("/")}"
            }
        } else {
            val relativePath = currentArchivePath.removeSuffix("/")
            if (relativePath.isBlank()) {
                ""
            } else {
                "/$relativePath"
            }
        }
    }

    private fun applyFilter(query: String) {
        val normalizedQuery = query.trim()
        visibleItems = if (normalizedQuery.isEmpty()) {
            allItems
        } else {
            allItems.filter { item ->
                item.displayName.contains(normalizedQuery, ignoreCase = true) ||
                    item.entryName.contains(normalizedQuery, ignoreCase = true) ||
                    item.detail.contains(normalizedQuery, ignoreCase = true)
            }
        }
        emptyMessage = getString(R.string.not_found)
    }

    private fun openItem(item: BrowserItem) {
        when {
            item.kind == ItemKind.BACK && currentSmaliWorkspace != null -> {
                if (currentSmaliPath.isBlank()) {
                    val workspace = currentSmaliWorkspace ?: return
                    currentSmaliWorkspace = null
                    currentArchivePath = workspace.returnArchivePath
                } else {
                    currentSmaliPath = item.entryName
                }
                loadFiles()
            }

            item.kind == ItemKind.BACK -> {
                currentArchivePath = item.entryName
                loadFiles()
            }

            item.isDirectory && currentSmaliWorkspace != null -> {
                currentSmaliPath = item.entryName
                loadFiles()
            }

            item.isDirectory -> {
                currentArchivePath = item.entryName
                loadFiles()
            }

            item.kind == ItemKind.DEX -> {
                if (AppSettings.prefs(requireContext()).getBoolean(AppSettings.SMALI_EDITING, true)) {
                    enterSmaliWorkspace(item.entryName)
                } else {
                    Toast.makeText(requireContext(), "A edição Smali está desativada nas configurações", Toast.LENGTH_LONG).show()
                }
            }

            item.kind == ItemKind.MANIFEST -> openManifestEditor()

            item.kind == ItemKind.XML || item.kind == ItemKind.TEXT -> openArchiveEditor(item)

            classifyFile(item.displayName) == FileVisualKind.IMAGE -> openImageEditor(item)

            else -> Toast.makeText(
                requireContext(),
                getString(R.string.full_edit_unsupported_file),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun enterSmaliWorkspace(dexEntryName: String) {
        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val workspace = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditRepository.prepareDexSmaliWorkspace(context, apkPath, dexEntryName)
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

            currentSmaliWorkspace = workspace
            currentSmaliPath = ""
            loadFiles()
        }
    }

    private fun openManifestEditor() {
        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val manifestFile = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditWorkspaceManager.getManifestFile(context, apkPath)
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
            pendingEditorTarget = EditorTarget(
                modifiedEntryName = FullEditRepository.MANIFEST_ENTRY,
                editorFile = manifestFile,
                registerFile = manifestFile
            )
            editorLauncher.launch(
                Intent(requireContext(), TextEditBigActivity::class.java).apply {
                    putExtra("filePath", manifestFile.absolutePath)
                    putExtra("fileName", FullEditRepository.MANIFEST_ENTRY)
                }
            )
        }
    }

    private fun openArchiveEditor(item: BrowserItem) {
        if (currentSmaliWorkspace != null) {
            openSmaliEditor(item)
            return
        }

        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val editorFile = runCatching {
                withContext(Dispatchers.IO) {
                    host().resolveModifiedEntry(item.entryName)
                        ?: FullEditRepository.extractEntryForEditing(context, apkPath, item.entryName)
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
            pendingEditorTarget = EditorTarget(
                modifiedEntryName = item.entryName,
                editorFile = editorFile,
                registerFile = editorFile
            )
            editorLauncher.launch(
                Intent(requireContext(), TextEditBigActivity::class.java).apply {
                    putExtra("filePath", editorFile.absolutePath)
                    putExtra("fileName", item.displayName)
                }
            )
        }
    }

    private fun openSmaliEditor(item: BrowserItem) {
        val workspace = currentSmaliWorkspace ?: return
        val file = FullEditRepository.resolveSmaliWorkspaceFile(workspace, item.entryName)
        if (!file.isFile) {
            Toast.makeText(requireContext(), getString(R.string.failed), Toast.LENGTH_SHORT).show()
            return
        }

        pendingEditorTarget = EditorTarget(
            modifiedEntryName = workspace.dexEntryName,
            editorFile = file,
            registerFile = workspace.rootDir
        )
        editorLauncher.launch(
            Intent(requireContext(), TextEditBigActivity::class.java).apply {
                putExtra("filePath", file.absolutePath)
                putExtra("fileName", item.displayName)
            }
        )
    }

    private fun replaceArchiveItem(item: BrowserItem) {
        if (item.isDirectory || item.kind == ItemKind.BACK) {
            return
        }
        val replacement = if (currentSmaliWorkspace != null) {
            ReplacementTarget(
                modifiedEntryName = currentSmaliWorkspace!!.dexEntryName,
                displayName = item.displayName,
                workspace = currentSmaliWorkspace,
                workspaceRelativePath = item.entryName
            )
        } else {
            ReplacementTarget(
                modifiedEntryName = item.entryName,
                displayName = item.displayName
            )
        }
        pendingReplacementTarget = replacement
        replacementLauncher.launch(arrayOf("*/*"))
    }

    private fun replaceEntry(target: ReplacementTarget, uri: Uri) {
        val context = requireContext().applicationContext
        loading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (target.workspace != null && target.workspaceRelativePath != null) {
                        val outputFile = FullEditRepository.resolveSmaliWorkspaceFile(
                            target.workspace,
                            target.workspaceRelativePath
                        )
                        outputFile.parentFile?.mkdirs()
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            outputFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Failed to read selected file")
                        target.workspace.rootDir
                    } else {
                        val replaceDir = AppSettings.workspaceRoot(context, "full_edit_replace")
                        val outputFile = File(replaceDir, target.displayName)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            outputFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Failed to read selected file")
                        outputFile
                    }
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
            host().registerModifiedEntry(target.modifiedEntryName, result)
            Toast.makeText(requireContext(), getString(R.string.file_replaced), Toast.LENGTH_SHORT).show()
            loadFiles()
        }
    }

    private fun openImageEditor(item: BrowserItem) {
        if (currentSmaliWorkspace != null) return
        val context = requireContext().applicationContext
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val file = runCatching {
                withContext(Dispatchers.IO) {
                    host().resolveModifiedEntry(item.entryName)?.takeIf(File::isFile) ?: run {
                        val directory = File(context.cacheDir, "full_edit_images").apply { mkdirs() }
                        val output = File(directory, "${item.entryName.hashCode().toUInt().toString(16)}_${item.displayName}")
                        ZipFile(apkPath()).use { zip ->
                            val entry = zip.getEntry(item.entryName) ?: error("Imagem não encontrada")
                            zip.getInputStream(entry).use { input -> output.outputStream().use(input::copyTo) }
                        }
                        output
                    }
                }
            }.getOrElse {
                loading = false
                Toast.makeText(requireContext(), it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show()
                return@launch
            }
            loading = false
            pendingImageEditorTarget = ImageEditorTarget(item.entryName, file)
            imageEditorLauncher.launch(Intent(requireContext(), ImageEditorActivity::class.java).apply {
                putExtra(ImageEditorActivity.EXTRA_FILE_PATH, file.absolutePath)
                putExtra(ImageEditorActivity.EXTRA_TITLE, item.displayName)
            })
        }
    }

    private fun requestRecursiveSearch(query: String) {
        val needle = query.trim()
        if (needle.isEmpty() || currentSmaliWorkspace != null) {
            applyFilter(needle)
            return
        }
        when (AppSettings.prefs(requireContext()).getString(AppSettings.DECODE_MODE, "ask")) {
            "all" -> searchRecursively(needle, searchContent = true)
            "partial" -> searchRecursively(needle, searchContent = false)
            else -> AlertDialog.Builder(requireContext())
                .setTitle("Pesquisa no APK")
                .setMessage("Pesquisar também dentro dos arquivos de texto? Isso pode levar mais tempo.")
                .setNegativeButton("Somente nomes") { _, _ -> searchRecursively(needle, false) }
                .setPositiveButton("Nome e conteúdo") { _, _ -> searchRecursively(needle, true) }
                .show()
        }
    }

    private fun searchRecursively(query: String, searchContent: Boolean) {
        val needle = query.trim()
        if (needle.isEmpty() || currentSmaliWorkspace != null) {
            applyFilter(needle)
            return
        }
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val results = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditRepository.searchArchive(apkPath(), needle, searchContent).map { item ->
                        val kind = resolveArchiveKind(item)
                        BrowserItem(
                            entryName = item.entryName,
                            displayName = item.displayName,
                            detail = item.entryName,
                            isDirectory = false,
                            kind = kind,
                            modified = host().isEntryModified(item.entryName)
                        )
                    }
                }
            }.getOrElse { error ->
                Toast.makeText(requireContext(), error.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show()
                emptyList()
            }
            currentArchivePath = ""
            allItems = results
            visibleItems = results
            pathLabel = if (searchContent) "Resultados em nome e conteúdo" else "Resultados por nome"
            loading = false
            emptyMessage = getString(R.string.not_found)
        }
    }

    private fun currentAdditionTarget(): AdditionTarget {
        return AdditionTarget(
            archivePath = currentArchivePath,
            workspace = currentSmaliWorkspace,
            smaliPath = currentSmaliPath
        )
    }

    private fun addSelectedFile(target: AdditionTarget, uri: Uri) {
        val context = requireContext().applicationContext
        val displayName = sanitizeEntryName(queryDisplayName(uri) ?: "new_file")
        if (displayName == null) {
            Toast.makeText(requireContext(), getString(R.string.invalid_file_name), Toast.LENGTH_SHORT).show()
            return
        }
        if (additionEntryExists(target, displayName, isDirectory = false)) {
            Toast.makeText(
                requireContext(),
                getString(R.string.file_already_exist, displayName),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        loading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val registeredChange = runCatching {
                withContext(Dispatchers.IO) {
                    if (target.workspace != null) {
                        val relativePath = target.smaliPath + displayName
                        val outputFile = FullEditRepository.resolveSmaliWorkspaceFile(
                            target.workspace,
                            relativePath
                        )
                        outputFile.parentFile?.mkdirs()
                        copyUriToFile(context, uri, outputFile)
                        target.workspace.dexEntryName to target.workspace.rootDir
                    } else {
                        val entryName = target.archivePath + displayName
                        val addDir = File(
                            context.cacheDir,
                            "full_edit_added/${apkPath().hashCode().toUInt().toString(16)}"
                        ).apply { mkdirs() }
                        val outputFile = File(addDir, entryName.replace('/', File.separatorChar))
                        outputFile.parentFile?.mkdirs()
                        copyUriToFile(context, uri, outputFile)
                        entryName to outputFile
                    }
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

            host().registerModifiedEntry(registeredChange.first, registeredChange.second)
            loading = false
            Toast.makeText(
                requireContext(),
                getString(R.string.file_added, registeredChange.first),
                Toast.LENGTH_SHORT
            ).show()
            loadFiles()
        }
    }

    private fun showAddFolderDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.pls_input_foldername)
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.new_folder)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val folderName = sanitizeEntryName(input.text?.toString().orEmpty())
                if (folderName == null) {
                    input.error = getString(R.string.invalid_file_name)
                    return@setOnClickListener
                }
                addFolder(currentAdditionTarget(), folderName)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun addFolder(target: AdditionTarget, folderName: String) {
        val context = requireContext().applicationContext
        if (additionEntryExists(target, folderName, isDirectory = true)) {
            Toast.makeText(
                requireContext(),
                getString(R.string.file_already_exist, folderName),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        runCatching {
            if (target.workspace != null) {
                val relativePath = target.smaliPath + folderName + "/"
                val folder = FullEditRepository.resolveSmaliWorkspaceFile(target.workspace, relativePath)
                check(folder.mkdirs() || folder.isDirectory) { getString(R.string.failed_create_dir, folder) }
                host().registerModifiedEntry(target.workspace.dexEntryName, target.workspace.rootDir)
            } else {
                val entryName = target.archivePath + folderName + "/"
                val folder = File(
                    context.cacheDir,
                    "full_edit_added_dirs/${apkPath().hashCode().toUInt().toString(16)}/${entryName.replace('/', File.separatorChar)}"
                )
                check(folder.mkdirs() || folder.isDirectory) { getString(R.string.failed_create_dir, folder) }
                host().registerModifiedEntry(entryName, folder)
            }
        }.onFailure { error ->
            Toast.makeText(
                requireContext(),
                error.message ?: getString(R.string.failed),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        Toast.makeText(requireContext(), getString(R.string.folder_added), Toast.LENGTH_SHORT).show()
        loadFiles()
    }

    private fun requestDelete(item: BrowserItem) {
        if (item.kind == ItemKind.BACK) {
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.full_edit_delete_confirm, item.displayName))
            .setPositiveButton(R.string.delete) { _, _ -> deleteItem(item) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteItem(item: BrowserItem) {
        val workspace = currentSmaliWorkspace
        if (workspace != null) {
            runCatching {
                val target = FullEditRepository.resolveSmaliWorkspaceFile(workspace, item.entryName)
                val workspaceRoot = workspace.rootDir.canonicalFile
                val canonicalTarget = target.canonicalFile
                check(canonicalTarget.path.startsWith(workspaceRoot.path + File.separator))
                check(if (canonicalTarget.isDirectory) canonicalTarget.deleteRecursively() else canonicalTarget.delete())
                host().registerModifiedEntry(workspace.dexEntryName, workspace.rootDir)
            }.onFailure { error ->
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        } else {
            val existedInOriginal = ZipFile(apkPath()).use { zipFile ->
                if (item.isDirectory) {
                    zipFile.entries().asSequence().any { it.name.startsWith(item.entryName) }
                } else {
                    zipFile.getEntry(item.entryName) != null
                }
            }
            if (existedInOriginal) {
                host().registerDeletedEntry(item.entryName)
            } else {
                host().discardModifiedEntry(item.entryName)
            }
        }

        Toast.makeText(requireContext(), getString(R.string.file_deleted), Toast.LENGTH_SHORT).show()
        loadFiles()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return requireContext().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) cursor.getString(nameColumn) else null
            }
        } ?: uri.lastPathSegment
    }

    private fun sanitizeEntryName(rawName: String): String? {
        val name = rawName
            .trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .replace(Regex("[<>:\"|?*]"), "_")
            .trimEnd(' ', '.')
        return name.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun additionEntryExists(
        target: AdditionTarget,
        displayName: String,
        isDirectory: Boolean
    ): Boolean {
        if (target.workspace != null) {
            val relativePath = target.smaliPath + displayName + if (isDirectory) "/" else ""
            return FullEditRepository.resolveSmaliWorkspaceFile(target.workspace, relativePath).exists()
        }

        val entryName = target.archivePath + displayName + if (isDirectory) "/" else ""
        if (host().isEntryDeleted(entryName)) {
            return false
        }
        if (host().modifiedEntryNames().any { existing ->
                existing == entryName || (isDirectory && existing.startsWith(entryName))
            }
        ) {
            return true
        }
        return ZipFile(apkPath()).use { zipFile ->
            if (isDirectory) {
                zipFile.entries().asSequence().any { it.name.startsWith(entryName) }
            } else {
                zipFile.getEntry(entryName) != null
            }
        }
    }

    private fun copyUriToFile(context: android.content.Context, uri: Uri, outputFile: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Failed to read selected file")
    }

    private fun exportItem(item: BrowserItem) {
        if (item.isDirectory || item.kind == ItemKind.BACK) {
            return
        }

        val context = requireContext().applicationContext
        val apkPath = apkPath()
        loading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val outputFile = runCatching {
                withContext(Dispatchers.IO) {
                    val exportDir = context.getExternalFilesDir("full_edit_export") ?: context.filesDir
                    exportDir.mkdirs()
                    val overwrite = AppSettings.prefs(context)
                        .getString(AppSettings.FILE_RENAME_MODE, "auto") == "overwrite"
                    val outputFile = AppSettings.exportTarget(exportDir, item.displayName, overwrite)

                    val modifiedFile = when {
                        currentSmaliWorkspace != null -> {
                            val workspace = currentSmaliWorkspace ?: error("Workspace not available")
                            FullEditRepository.resolveSmaliWorkspaceFile(workspace, item.entryName)
                        }

                        item.kind == ItemKind.MANIFEST -> {
                            FullEditWorkspaceManager.getManifestFile(context, apkPath)
                        }

                        item.kind == ItemKind.XML || item.kind == ItemKind.TEXT -> {
                            host().resolveModifiedEntry(item.entryName)
                                ?: FullEditRepository.extractEntryForEditing(context, apkPath, item.entryName)
                        }

                        else -> host().resolveModifiedEntry(item.entryName)
                    }

                    if (modifiedFile != null && modifiedFile.exists()) {
                        modifiedFile.copyTo(outputFile, overwrite = true)
                    } else {
                        ZipFile(apkPath).use { zipFile ->
                            val entry = zipFile.getEntry(item.entryName) ?: error("Entry not found")
                            zipFile.getInputStream(entry).use { input ->
                                outputFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    }
                    outputFile
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
            Toast.makeText(
                requireContext(),
                getString(R.string.save_succeed_1, outputFile.absolutePath),
                Toast.LENGTH_LONG
            ).show()
        }
    }

}
