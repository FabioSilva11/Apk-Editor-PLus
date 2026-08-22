package com.saas.apkeditorplus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saas.apkeditorplus.ui.simple.SimpleArchiveEntry
import com.saas.apkeditorplus.ui.simple.SimpleEditScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File
import java.util.zip.ZipFile

class SimpleEditActivity : BaseActivity() {
    private lateinit var apkPath: String
    private val modifiedFiles = linkedMapOf<String, String>()
    private var pendingEntry: SimpleArchiveEntry? = null
    private var selectedTab by mutableIntStateOf(0)
    private var loading by mutableStateOf(true)
    private var title by mutableStateOf("Edição simples")
    private var fileEntries by mutableStateOf<List<SimpleArchiveEntry>>(emptyList())
    private var imageEntries by mutableStateOf<List<SimpleArchiveEntry>>(emptyList())
    private var audioEntries by mutableStateOf<List<SimpleArchiveEntry>>(emptyList())
    private var modifiedNames by mutableStateOf<Set<String>>(emptySet())

    private val replacementLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val entry = pendingEntry
        pendingEntry = null
        if (entry != null && uri != null) copyReplacement(entry, uri)
    }

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apkPath = intent.getStringExtra("apkPath").orEmpty()
        if (apkPath.isBlank()) {
            Toast.makeText(this, R.string.apk_path_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        title = readApkLabel()
        setContent {
            ApkEditorTheme {
                val entries = when (selectedTab) { 0 -> fileEntries; 1 -> imageEntries; else -> audioEntries }
                SimpleEditScreen(
                    title = title,
                    apkPath = apkPath,
                    loading = loading,
                    selectedTab = selectedTab,
                    entries = entries,
                    modifiedNames = modifiedNames,
                    onBack = ::finish,
                    onTabSelected = { selectedTab = it },
                    onEntryClick = ::chooseReplacement,
                    onClearReplacement = ::clearReplacement,
                    onSave = ::saveOrClose
                )
            }
        }
        loadEntries()
    }

    private fun readApkLabel(): String = runCatching {
        val info = packageManager.getPackageArchiveInfo(apkPath, 0)
        val appInfo = info?.applicationInfo ?: return@runCatching getString(R.string.simple_edit)
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        appInfo.loadLabel(packageManager).toString()
    }.getOrDefault(getString(R.string.simple_edit))

    private fun loadEntries() {
        loading = true
        Thread {
            val result = runCatching {
                ZipFile(apkPath).use { zip ->
                    val all = zip.entries().asSequence().filterNot { it.isDirectory }.map {
                        SimpleArchiveEntry(it.name.substringAfterLast('/'), it.name)
                    }.sortedBy { it.entryName.lowercase() }.toList()
                    Triple(
                        all.filterNot { isImage(it.entryName) || isAudio(it.entryName) },
                        all.filter { isImage(it.entryName) },
                        all.filter { isAudio(it.entryName) }
                    )
                }
            }
            runOnUiThread {
                loading = false
                result.onSuccess { (files, images, audios) ->
                    fileEntries = files
                    imageEntries = images
                    audioEntries = audios
                }.onFailure {
                    Toast.makeText(this, it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun chooseReplacement(entry: SimpleArchiveEntry) {
        pendingEntry = entry
        val mime = when { isImage(entry.entryName) -> "image/*"; isAudio(entry.entryName) -> "audio/*"; else -> "*/*" }
        replacementLauncher.launch(arrayOf(mime))
    }

    private fun copyReplacement(entry: SimpleArchiveEntry, uri: Uri) {
        Thread {
            val result = runCatching {
                val dir = File(cacheDir, "simple_edit_replacements").apply { mkdirs() }
                val file = File(dir, entry.entryName.replace('/', '_'))
                contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use(input::copyTo) }
                    ?: error("Não foi possível ler o arquivo selecionado")
                file
            }
            runOnUiThread {
                result.onSuccess {
                    modifiedFiles[entry.entryName] = it.absolutePath
                    modifiedNames = modifiedFiles.keys.toSet()
                    Toast.makeText(this, R.string.file_replaced, Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(this, it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun clearReplacement(entry: SimpleArchiveEntry) {
        if (modifiedFiles.remove(entry.entryName) != null) {
            modifiedNames = modifiedFiles.keys.toSet()
            Toast.makeText(this, R.string.remove, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveOrClose() {
        if (modifiedFiles.isEmpty()) {
            finish()
            return
        }
        confirmRebuild(::startBuild)
    }

    private fun startBuild() {
        val bundle = Bundle().apply { modifiedFiles.forEach { (name, path) -> putString(name, path) } }
        startActivity(Intent(this, ApkCreateActivity::class.java).apply {
            putExtra("apkPath", apkPath)
            putExtra("modifiedFiles", bundle)
        })
    }

    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")
    private fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("mp3", "ogg", "wav", "m4a", "aac", "flac")
}
