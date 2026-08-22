package com.saas.apkeditorplus

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.saas.apkeditorplus.simple.SimpleAudioFragment
import com.saas.apkeditorplus.simple.SimpleFilesFragment
import com.saas.apkeditorplus.simple.SimpleImagesFragment
import com.saas.apkeditorplus.ui.simple.SimpleArchiveEntry
import com.saas.apkeditorplus.ui.simple.SimpleEditScreen
import com.saas.apkeditorplus.ui.files.decodeImageThumbnail
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipFile

class SimpleEditActivity : BaseActivity() {
    private lateinit var viewPager: ViewPager2
    private lateinit var apkPath: String
    private val modifiedFiles = linkedMapOf<String, String>()
    private var pendingEntry: SimpleArchiveEntry? = null
    private var pendingExport: SimpleArchiveEntry? = null
    private var mediaPlayer: MediaPlayer? = null

    private var selectedTab by mutableIntStateOf(0)
    private var loading by mutableStateOf(true)
    private var title by mutableStateOf("Edição simples")
    private var packageNameLabel by mutableStateOf("")
    private var icon by mutableStateOf<ImageBitmap?>(null)
    private var fileEntries = emptyList<SimpleArchiveEntry>()
    private var imageEntries = emptyList<SimpleArchiveEntry>()
    private var audioEntries = emptyList<SimpleArchiveEntry>()

    internal var entriesRevision by mutableIntStateOf(0)
        private set
    internal var modifiedNames by mutableStateOf<Set<String>>(emptySet())
        private set
    internal var playingEntryName by mutableStateOf<String?>(null)
        private set

    private val replacementLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val entry = pendingEntry
        pendingEntry = null
        if (entry != null && uri != null) copyReplacement(entry, uri)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = pendingExport
        pendingExport = null
        if (entry != null && uri != null) exportEntry(entry, uri)
    }

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apkPath = intent.getStringExtra("apkPath").orEmpty()
        if (!File(apkPath).isFile) {
            Toast.makeText(this, R.string.apk_path_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        readApkHeader()
        setContent {
            ApkEditorTheme {
                entriesRevision
                SimpleEditScreen(
                    title = title,
                    subtitle = packageNameLabel.ifBlank { apkPath },
                    icon = icon,
                    loading = loading,
                    selectedTab = selectedTab,
                    itemCounts = listOf(fileEntries.size, imageEntries.size, audioEntries.size),
                    modifiedCount = modifiedNames.size,
                    onBack = ::finish,
                    onTabSelected = ::selectTab,
                    onPagerReady = ::setupViewPager,
                    onSave = ::saveOrClose
                )
            }
        }
        loadEntries()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    internal fun entriesForTab(tabIndex: Int): List<SimpleArchiveEntry> = when (tabIndex) {
        0 -> fileEntries
        1 -> imageEntries
        else -> audioEntries
    }

    internal fun childrenForPath(path: String): List<SimpleArchiveEntry> {
        val normalizedPath = path.trimStart('/').let { value ->
            if (value.isBlank() || value.endsWith('/')) value else "$value/"
        }
        val directories = linkedMapOf<String, SimpleArchiveEntry>()
        val files = mutableListOf<SimpleArchiveEntry>()
        fileEntries.forEach { entry ->
            if (!entry.entryName.startsWith(normalizedPath)) return@forEach
            val relative = entry.entryName.removePrefix(normalizedPath)
            if (relative.isBlank()) return@forEach
            val slash = relative.indexOf('/')
            if (slash >= 0) {
                val directoryName = relative.substring(0, slash)
                val directoryPath = "$normalizedPath$directoryName/"
                directories.putIfAbsent(
                    directoryPath,
                    SimpleArchiveEntry(directoryName, directoryPath, isDirectory = true, relatedEntries = emptyList())
                )
            } else {
                files += entry
            }
        }
        return directories.values.sortedBy { it.displayName.lowercase(Locale.ROOT) } +
            files.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    internal fun chooseReplacement(entry: SimpleArchiveEntry) {
        if (entry.isDirectory) return
        pendingEntry = entry
        replacementLauncher.launch(arrayOf(mimeType(entry.entryName)))
    }

    internal fun chooseExport(entry: SimpleArchiveEntry) {
        if (entry.isDirectory) return
        pendingExport = entry
        exportLauncher.launch(entry.displayName)
    }

    internal fun clearReplacement(entry: SimpleArchiveEntry) {
        var changed = false
        entry.relatedEntries.forEach { changed = modifiedFiles.remove(it) != null || changed }
        if (changed) notifyChanges()
    }

    internal fun openEntry(entry: SimpleArchiveEntry, tabIndex: Int) {
        when {
            tabIndex == 2 || isAudio(entry.entryName) -> toggleAudio(entry)
            tabIndex == 1 || isPreviewableImage(entry.entryName) -> openImage(entry)
        }
    }

    internal fun isEntryModified(entry: SimpleArchiveEntry): Boolean =
        entry.relatedEntries.any(modifiedFiles::containsKey)

    internal fun loadThumbnail(entry: SimpleArchiveEntry): ImageBitmap? {
        val replacement = entry.relatedEntries.firstNotNullOfOrNull { name ->
            modifiedFiles[name]?.let(::File)?.takeIf(File::isFile)
        }
        if (replacement != null) return decodeImageThumbnail(replacement)
        return ZipFile(apkPath).use { zip ->
            val zipEntry = zip.getEntry(entry.entryName) ?: return@use null
            decodeImageThumbnail(openStream = { zip.getInputStream(zipEntry) })
        }
    }

    private fun readApkHeader() {
        runCatching {
            val info = packageManager.getPackageArchiveInfo(apkPath, 0) ?: return@runCatching
            val appInfo = info.applicationInfo ?: return@runCatching
            appInfo.sourceDir = apkPath
            appInfo.publicSourceDir = apkPath
            title = appInfo.loadLabel(packageManager).toString()
            packageNameLabel = info.packageName.orEmpty()
            icon = appInfo.loadIcon(packageManager).toBitmap(144, 144).asImageBitmap()
        }.onFailure {
            title = File(apkPath).nameWithoutExtension.ifBlank { getString(R.string.simple_edit) }
            packageNameLabel = apkPath
        }
    }

    private fun setupViewPager(pager: ViewPager2) {
        if (::viewPager.isInitialized && viewPager === pager) return
        viewPager = pager
        if (pager.id == View.NO_ID) pager.id = View.generateViewId()
        pager.adapter = SimpleEditPagerAdapter()
        pager.offscreenPageLimit = 2
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectedTab = position
            }
        })
        pager.setCurrentItem(selectedTab, false)
    }

    private fun selectTab(position: Int) {
        selectedTab = position
        if (::viewPager.isInitialized && viewPager.currentItem != position) {
            viewPager.setCurrentItem(position, true)
        }
    }

    private fun loadEntries() {
        loading = true
        Thread {
            val result = runCatching {
                ZipFile(apkPath).use { zip ->
                    val all = zip.entries().asSequence()
                        .filterNot { it.isDirectory }
                        .map { SimpleArchiveEntry(it.name.substringAfterLast('/'), it.name) }
                        .sortedBy { it.entryName.lowercase(Locale.ROOT) }
                        .toList()
                    val images = all.asSequence()
                        .filter { isImageResource(it.entryName) }
                        .groupBy(SimpleArchiveEntry::displayName)
                        .map { (name, variants) ->
                            SimpleArchiveEntry(
                                displayName = name,
                                entryName = variants.first().entryName,
                                detail = "${variants.size} variante(s)",
                                relatedEntries = variants.map(SimpleArchiveEntry::entryName)
                            )
                        }
                        .sortedBy { it.displayName.lowercase(Locale.ROOT) }
                    Triple(all, images, all.filter { isAudio(it.entryName) })
                }
            }
            runOnUiThread {
                loading = false
                result.onSuccess { (files, images, audios) ->
                    fileEntries = files
                    imageEntries = images
                    audioEntries = audios
                    entriesRevision++
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun copyReplacement(entry: SimpleArchiveEntry, uri: Uri) {
        if (!isCompatibleReplacement(entry, uri)) {
            Toast.makeText(this, "Selecione um arquivo do mesmo tipo", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val result = runCatching {
                val directory = File(cacheDir, "simple_edit_replacements").apply { mkdirs() }
                val extension = entry.displayName.substringAfterLast('.', "")
                val suffix = extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
                val file = File(directory, "${entry.entryName.hashCode()}$suffix")
                contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use(input::copyTo) }
                    ?: error("Não foi possível ler o arquivo selecionado")
                file
            }
            runOnUiThread {
                result.onSuccess { file ->
                    entry.relatedEntries.forEach { modifiedFiles[it] = file.absolutePath }
                    notifyChanges()
                    Toast.makeText(this, R.string.file_replaced, Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(this, it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun exportEntry(entry: SimpleArchiveEntry, uri: Uri) {
        Thread {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { output -> writeEntry(entry, output) }
                    ?: error("Não foi possível criar o arquivo")
            }
            runOnUiThread {
                result.onSuccess { Toast.makeText(this, "Arquivo exportado", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(this, it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun writeEntry(entry: SimpleArchiveEntry, output: OutputStream) {
        val replacement = entry.relatedEntries.firstNotNullOfOrNull { modifiedFiles[it]?.let(::File)?.takeIf(File::isFile) }
        if (replacement != null) {
            replacement.inputStream().use { it.copyTo(output) }
            return
        }
        ZipFile(apkPath).use { zip ->
            val zipEntry = zip.getEntry(entry.entryName) ?: error("Arquivo não encontrado no APK")
            zip.getInputStream(zipEntry).use { it.copyTo(output) }
        }
    }

    private fun materializeEntry(entry: SimpleArchiveEntry): File {
        val extension = entry.displayName.substringAfterLast('.', "")
        val suffix = extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        val output = File(File(cacheDir, "simple_edit_preview").apply { mkdirs() }, "${entry.entryName.hashCode()}$suffix")
        output.outputStream().use { writeEntry(entry, it) }
        return output
    }

    private fun openImage(entry: SimpleArchiveEntry) {
        Thread {
            val result = runCatching { materializeEntry(entry) }
            runOnUiThread {
                result.onSuccess { file ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType(entry.entryName))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this, "Nenhum visualizador de imagem disponível", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { Toast.makeText(this, it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun toggleAudio(entry: SimpleArchiveEntry) {
        if (playingEntryName == entry.entryName) {
            releasePlayer()
            entriesRevision++
            return
        }
        releasePlayer()
        Thread {
            val result = runCatching { materializeEntry(entry) }
            runOnUiThread {
                result.onSuccess { file ->
                    runCatching {
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            setOnCompletionListener {
                                releasePlayer()
                                entriesRevision++
                            }
                            prepare()
                            start()
                        }
                        playingEntryName = entry.entryName
                        entriesRevision++
                    }.onFailure { Toast.makeText(this, it.message ?: "Não foi possível reproduzir", Toast.LENGTH_LONG).show() }
                }.onFailure { Toast.makeText(this, it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        playingEntryName = null
    }

    private fun notifyChanges() {
        modifiedNames = modifiedFiles.keys.toSet()
        entriesRevision++
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

    private fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun isCompatibleReplacement(entry: SimpleArchiveEntry, uri: Uri): Boolean {
        val targetExtension = entry.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val selectedName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            }
            .orEmpty()
        val selectedExtension = selectedName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            isImageResource(entry.entryName) -> contentResolver.getType(uri)?.startsWith("image/") == true ||
                selectedExtension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
            isAudio(entry.entryName) -> contentResolver.getType(uri)?.startsWith("audio/") == true || isAudio(selectedName)
            targetExtension.isBlank() -> true
            else -> selectedExtension == targetExtension
        }
    }

    private fun isPreviewableImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    private fun isImageResource(name: String): Boolean =
        (name.startsWith("res/drawable") || name.startsWith("res/mipmap")) && isPreviewableImage(name)

    private fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf(
        "wav", "mp2", "mp3", "ogg", "aac", "mpg", "mpeg", "mid", "midi", "smf", "jet", "rtttl",
        "imy", "xmf", "mp4", "m4a", "m4v", "3gp", "3gpp", "3g2", "3gpp2", "amr", "awb", "wma",
        "wmv", "flac"
    )

    private inner class SimpleEditPagerAdapter : FragmentStateAdapter(this@SimpleEditActivity) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> SimpleFilesFragment()
            1 -> SimpleImagesFragment()
            else -> SimpleAudioFragment()
        }
    }
}
