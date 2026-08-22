package com.saas.apkeditorplus.full

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.saas.apkeditorplus.FullEditActivity
import com.saas.apkeditorplus.R
import com.saas.apkeditorplus.TextEditBigActivity
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

    private lateinit var pathView: TextView
    private lateinit var listView: ListView
    private lateinit var keywordEdit: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView

    private var currentArchivePath: String = ""
    private var currentSmaliWorkspace: FullEditRepository.SmaliWorkspace? = null
    private var currentSmaliPath: String = ""
    private var allItems = emptyList<BrowserItem>()
    private var visibleItems = emptyList<BrowserItem>()
    private var pendingEditorTarget: EditorTarget? = null
    private var pendingReplacementTarget: ReplacementTarget? = null
    private var pendingAdditionTarget: AdditionTarget? = null

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
        return inflater.inflate(R.layout.fragment_full_files, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pathView = view.findViewById(R.id.current_path)
        listView = view.findViewById(R.id.file_list)
        keywordEdit = view.findViewById(R.id.keyword_edit)
        progressBar = view.findViewById(R.id.progress_bar)
        emptyView = view.findViewById(R.id.empty_view)

        listView.emptyView = emptyView
        listView.adapter = FilesAdapter()

        keywordEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        view.findViewById<View>(R.id.search_button).setOnClickListener {
            applyFilter(keywordEdit.text.toString())
        }
        view.findViewById<View>(R.id.home_button).setOnClickListener {
            currentSmaliWorkspace = null
            currentSmaliPath = ""
            currentArchivePath = ""
            loadFiles()
        }
        view.findViewById<View>(R.id.add_file_button).setOnClickListener {
            pendingAdditionTarget = currentAdditionTarget()
            addFileLauncher.launch(arrayOf("*/*"))
        }
        view.findViewById<View>(R.id.add_folder_button).setOnClickListener {
            showAddFolderDialog()
        }

        loadFiles()
    }

    private fun host(): FullEditActivity = requireActivity() as FullEditActivity

    private fun apkPath(): String = arguments?.getString(ARG_APK_PATH).orEmpty()

    private fun loadFiles() {
        val context = requireContext().applicationContext
        val apkPath = apkPath()
        progressBar.visibility = View.VISIBLE
        emptyView.visibility = View.GONE

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
                progressBar.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                emptyView.text = error.message ?: getString(R.string.failed)
                (listView.adapter as BaseAdapter).notifyDataSetChanged()
                return@launch
            }

            allItems = items
            progressBar.visibility = View.GONE
            pathView.text = buildCurrentPathLabel()
            pathView.isVisible = pathView.text.isNotBlank()
            applyFilter(keywordEdit.text.toString())
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
        emptyView.text = getString(R.string.not_found)
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
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

            item.kind == ItemKind.DEX -> enterSmaliWorkspace(item.entryName)

            item.kind == ItemKind.MANIFEST -> openManifestEditor()

            item.kind == ItemKind.XML || item.kind == ItemKind.TEXT -> openArchiveEditor(item)

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
        progressBar.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val workspace = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditRepository.prepareDexSmaliWorkspace(context, apkPath, dexEntryName)
                }
            }.getOrElse { error ->
                progressBar.visibility = View.GONE
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
        progressBar.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val manifestFile = runCatching {
                withContext(Dispatchers.IO) {
                    FullEditWorkspaceManager.getManifestFile(context, apkPath)
                }
            }.getOrElse { error ->
                progressBar.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            progressBar.visibility = View.GONE
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
        progressBar.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val editorFile = runCatching {
                withContext(Dispatchers.IO) {
                    host().resolveModifiedEntry(item.entryName)
                        ?: FullEditRepository.extractEntryForEditing(context, apkPath, item.entryName)
                }
            }.getOrElse { error ->
                progressBar.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            progressBar.visibility = View.GONE
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
        progressBar.visibility = View.VISIBLE

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
                        val replaceDir = File(context.cacheDir, "full_edit_replace").apply { mkdirs() }
                        val outputFile = File(replaceDir, target.displayName)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            outputFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Failed to read selected file")
                        outputFile
                    }
                }
            }.getOrElse { error ->
                progressBar.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            progressBar.visibility = View.GONE
            host().registerModifiedEntry(target.modifiedEntryName, result)
            Toast.makeText(requireContext(), getString(R.string.file_replaced), Toast.LENGTH_SHORT).show()
            loadFiles()
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
        progressBar.visibility = View.VISIBLE

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
                progressBar.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            host().registerModifiedEntry(registeredChange.first, registeredChange.second)
            progressBar.visibility = View.GONE
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
        progressBar.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val outputFile = runCatching {
                withContext(Dispatchers.IO) {
                    val exportDir = context.getExternalFilesDir("full_edit_export") ?: context.filesDir
                    exportDir.mkdirs()
                    val outputFile = uniqueTargetFile(exportDir, item.displayName)

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
                progressBar.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            progressBar.visibility = View.GONE
            Toast.makeText(
                requireContext(),
                getString(R.string.save_succeed_1, outputFile.absolutePath),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun uniqueTargetFile(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        if (!candidate.exists()) {
            return candidate
        }
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val suffix = "_$index"
            val nextName = if (extension.isBlank() || extension == fileName) {
                base + suffix
            } else {
                "$base$suffix.$extension"
            }
            candidate = File(directory, nextName)
            index += 1
        }
        return candidate
    }

    private inner class FilesAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleItems.size

        override fun getItem(position: Int): Any = visibleItems[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val rowView = convertView
                ?: layoutInflater.inflate(R.layout.item_zipfile, parent, false)
            val item = visibleItems[position]

            rowView.findViewById<TextView>(R.id.filename).text = item.displayName

            val detailView = rowView.findViewById<TextView>(R.id.detail1)
            detailView.text = item.detail
            detailView.visibility = if (item.detail.isBlank()) View.GONE else View.VISIBLE

            rowView.findViewById<View>(R.id.selection_indicator).alpha =
                if (item.modified) 1f else 0.24f

            val iconBadge = rowView.findViewById<View>(R.id.file_icon_badge)
            val icon = rowView.findViewById<ImageView>(R.id.file_icon)
            val menuEdit = rowView.findViewById<View>(R.id.menu_edit)
            val menuSave = rowView.findViewById<View>(R.id.menu_save)

            val iconRes = when (item.kind) {
                ItemKind.BACK -> R.drawable.ic_back
                ItemKind.FOLDER -> R.drawable.ic_folder
                ItemKind.MANIFEST, ItemKind.XML -> R.drawable.ic_edit_4
                ItemKind.DEX -> R.drawable.ic_edit_3
                ItemKind.TEXT -> R.drawable.ic_edit_2
                ItemKind.BINARY -> R.drawable.ic_file_unknown
            }
            val badgeRes = when (item.kind) {
                ItemKind.BACK,
                ItemKind.FOLDER -> R.drawable.full_edit_badge_folder
                ItemKind.MANIFEST -> R.drawable.full_edit_badge_manifest
                ItemKind.DEX -> R.drawable.full_edit_badge_dex
                ItemKind.XML,
                ItemKind.TEXT,
                ItemKind.BINARY -> R.drawable.full_edit_badge_generic
            }

            iconBadge.background = ContextCompat.getDrawable(requireContext(), badgeRes)
            icon.setImageResource(iconRes)

            val actionVisible = item.kind != ItemKind.BACK && !item.isDirectory
            menuEdit.visibility = if (actionVisible) View.VISIBLE else View.INVISIBLE
            menuSave.visibility = if (actionVisible) View.VISIBLE else View.INVISIBLE

            rowView.setOnClickListener { openItem(item) }
            rowView.setOnLongClickListener {
                requestDelete(item)
                true
            }
            menuEdit.setOnClickListener { replaceArchiveItem(item) }
            menuSave.setOnClickListener { exportItem(item) }
            return rowView
        }
    }
}
