package com.saas.apkeditorplus

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.apk.axml.ResourceTableParser
import com.saas.apkeditorplus.ui.files.ArchiveBrowserItem
import com.saas.apkeditorplus.ui.files.ArchiveXmlBrowserScreen
import com.saas.apkeditorplus.ui.files.decodeImageThumbnail
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import com.saas.apkeditorplus.utils.AxmlDecoder
import java.io.File
import java.util.zip.ZipFile

class AxmlEditActivity : BaseActivity() {
    private lateinit var apkPath: String
    private var currentPath = ""
    private var items by mutableStateOf<List<ArchiveBrowserItem>>(emptyList())
    private var loading by mutableStateOf(true)
    private var title by mutableStateOf("Editor XML")
    private val modifiedFiles = mutableMapOf<String, String>()
    private var modifiedNames by mutableStateOf<Set<String>>(emptySet())
    private var resourceEntries: List<*>? = null
    private var lastOpenedEntryName: String? = null

    private val editorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        lastOpenedEntryName?.let { entryName ->
            val file = File(cacheDir, "xml_edit/${entryName.replace('/', '_')}")
            if (file.isFile) {
                modifiedFiles[entryName] = file.absolutePath
                modifiedNames = modifiedFiles.keys.toSet()
            }
        }
    }

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apkPath = intent.getStringExtra("apkPath").orEmpty()
        if (apkPath.isBlank()) { finish(); return }
        title = readApkLabel()
        setContent {
            ApkEditorTheme {
                ArchiveXmlBrowserScreen(
                    title = title,
                    path = if (currentPath.isBlank()) apkPath else "$apkPath/$currentPath",
                    items = items,
                    loading = loading,
                    modifiedNames = modifiedNames,
                    onBack = ::navigateBack,
                    onItemClick = ::openItem,
                    onSave = ::startApkCreate,
                    thumbnailLoader = ::loadThumbnail
                )
            }
        }
        loadResources()
        loadFiles()
    }

    private fun readApkLabel(): String = runCatching {
        val info = packageManager.getPackageArchiveInfo(apkPath, 0)
        val appInfo = info?.applicationInfo ?: return@runCatching "Editor XML"
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        appInfo.loadLabel(packageManager).toString()
    }.getOrDefault("Editor XML")

    private fun loadThumbnail(item: ArchiveBrowserItem) = ZipFile(apkPath).use { zip ->
        val entry = zip.getEntry(item.fullPath) ?: return@use null
        decodeImageThumbnail(openStream = { zip.getInputStream(entry) })
    }

    private fun loadResources() {
        Thread {
            resourceEntries = runCatching {
                ZipFile(apkPath).use { zip ->
                    zip.getEntry("resources.arsc")?.let { entry ->
                        zip.getInputStream(entry).use { ResourceTableParser(it).parse() }
                    }
                }
            }.getOrNull()
        }.start()
    }

    private fun loadFiles() {
        loading = true
        val pathSnapshot = currentPath
        Thread {
            val result = runCatching {
                ZipFile(apkPath).use { zip ->
                    val folders = sortedSetOf<String>()
                    val files = mutableListOf<ArchiveBrowserItem>()
                    zip.entries().asSequence().forEach { entry ->
                        if (!entry.name.startsWith(pathSnapshot)) return@forEach
                        val relative = entry.name.removePrefix(pathSnapshot)
                        if (relative.isBlank()) return@forEach
                        val slash = relative.indexOf('/')
                        if (slash >= 0) folders += relative.substring(0, slash)
                        else if (!entry.isDirectory) files += ArchiveBrowserItem(relative, entry.name, false)
                    }
                    buildList {
                        if (pathSnapshot.isNotBlank()) add(ArchiveBrowserItem("..", parentPath(pathSnapshot), true))
                        folders.forEach { add(ArchiveBrowserItem(it, "$pathSnapshot$it/", true)) }
                        addAll(files.sortedBy { it.name.lowercase() })
                    }
                }
            }
            runOnUiThread {
                loading = false
                result.onSuccess { if (pathSnapshot == currentPath) items = it }
                    .onFailure { Toast.makeText(this, getString(R.string.error_loading_files, it.message), Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun openItem(item: ArchiveBrowserItem) {
        if (item.directory) {
            currentPath = item.fullPath
            loadFiles()
        } else if (item.name.endsWith(".xml", true)) {
            openXml(item.fullPath)
        } else {
            Toast.makeText(this, "Somente arquivos XML podem ser editados neste modo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openXml(entryName: String) {
        lastOpenedEntryName = entryName
        loading = true
        Thread {
            val result = runCatching {
                val dir = File(cacheDir, "xml_edit").apply { mkdirs() }
                val temp = File(dir, entryName.replace('/', '_'))
                if (entryName in modifiedFiles && temp.isFile) return@runCatching temp
                ZipFile(apkPath).use { zip ->
                    val entry = zip.getEntry(entryName) ?: error("Arquivo não encontrado no APK")
                    zip.getInputStream(entry).use { input ->
                        temp.outputStream().use { output ->
                            check(AxmlDecoder().decodeWithResources(input, output, resourceEntries)) {
                                "O XML binário não pôde ser decodificado"
                            }
                        }
                    }
                }
                temp
            }
            runOnUiThread {
                loading = false
                result.onSuccess { file ->
                    editorLauncher.launch(Intent(this, TextEditBigActivity::class.java).apply {
                        putExtra("filePath", file.absolutePath)
                        putExtra("fileName", entryName)
                    })
                }.onFailure { showErrorDialog(getString(R.string.failed_to_process_file), it.stackTraceToString()) }
            }
        }.start()
    }

    private fun navigateBack() {
        if (currentPath.isBlank()) finish() else { currentPath = parentPath(currentPath); loadFiles() }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() = navigateBack()

    private fun parentPath(path: String): String {
        val clean = path.removeSuffix("/")
        val slash = clean.lastIndexOf('/')
        return if (slash < 0) "" else clean.substring(0, slash + 1)
    }

    private fun showErrorDialog(title: String, log: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(log).setPositiveButton(R.string.close, null).show()
    }

    private fun startApkCreate() {
        confirmRebuild(::launchApkCreate)
    }

    private fun launchApkCreate() {
        val bundle = Bundle().apply { modifiedFiles.forEach { (name, path) -> putString(name, path) } }
        startActivity(Intent(this, ApkCreateActivity::class.java).apply {
            putExtra("apkPath", apkPath)
            putExtra("modifiedFiles", bundle)
        })
        finish()
    }
}
