package com.saas.apkeditorplus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.saas.apkeditorplus.ui.files.FileBrowserScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File
import java.util.Locale

class FileListActivity : BaseActivity() {
    private lateinit var apkInfoLoader: ApkArchiveInfoLoader
    private var currentDirectory = File("/")
    private var items by mutableStateOf<List<FileBrowserItem>>(emptyList())
    private var keyword by mutableStateOf("")
    private var showingExternalStorage by mutableStateOf(false)

    companion object { private const val LAST_DIR_KEY = "apkDirectory" }

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        apkInfoLoader = ApkArchiveInfoLoader(this)
        setContent {
            ApkEditorTheme {
                FileBrowserScreen(
                    path = currentDirectory.absolutePath,
                    items = items,
                    keyword = keyword,
                    showingExternal = showingExternalStorage,
                    onKeywordChange = { keyword = it },
                    onBack = ::finish,
                    onItemClick = ::openItem,
                    onSearch = ::startSearch,
                    onPrimaryStorage = { openDirectory(StorageRoots.primary(this)) },
                    onExternalStorage = ::openExternalStorage,
                    onAppStorage = { openDirectory(filesDir) }
                )
            }
        }
        val saved = File(getSharedPreferences("config", Context.MODE_PRIVATE)
            .getString(LAST_DIR_KEY, StorageRoots.primary(this).absolutePath).orEmpty())
        openDirectory(saved.takeIf { it.isDirectory } ?: StorageRoots.primary(this))
    }

    override fun onDestroy() {
        apkInfoLoader.shutdown()
        super.onDestroy()
    }

    private fun openDirectory(directory: File) {
        val safe = directory.takeIf { it.isDirectory } ?: StorageRoots.primary(this)
        currentDirectory = safe
        showingExternalStorage = samePath(safe, StorageRoots.external(this))
        getSharedPreferences("config", Context.MODE_PRIVATE).edit().putString(LAST_DIR_KEY, safe.absolutePath).apply()
        val files = safe.listFiles().orEmpty().sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        items = buildList {
            safe.parentFile?.let { add(FileBrowserItem(it, true, null, safe.parentFile?.absolutePath.orEmpty())) }
            files.forEach { file ->
                val info = if (file.name.endsWith(".apk", true)) apkInfoLoader.get(file.absolutePath) else null
                val image = info?.icon?.toBitmap(112, 112)?.asImageBitmap()
                add(
                    FileBrowserItem(
                        file = file,
                        parent = false,
                        icon = image,
                        detail = when {
                            file.isDirectory -> getString(R.string.folder_label)
                            info != null -> info.label.ifBlank { formatSize(file.length()) }
                            else -> formatSize(file.length())
                        }
                    )
                )
                if (file.name.endsWith(".apk", true) && info == null) {
                    apkInfoLoader.load(file.absolutePath) { runOnUiThread { if (!isDestroyed) openDirectory(currentDirectory) } }
                }
            }
        }
    }

    private fun openItem(item: FileBrowserItem) {
        when {
            item.parent -> currentDirectory.parentFile?.let(::openDirectory)
            item.file.isDirectory -> openDirectory(item.file)
            item.file.name.endsWith(".apk", true) -> handleApkSelection(item.file)
            else -> Toast.makeText(this, getString(R.string.select_apk_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleApkSelection(file: File) {
        file.parentFile?.let(::openDirectory)
        when {
            intent.getBooleanExtra("select_for_verify", false) -> {
                setResult(RESULT_OK, Intent().putExtra("apkPath", file.absolutePath)); finish()
            }
            intent.getBooleanExtra("select_for_common_edit", false) -> startEditActivity(EditModeDialog.COMMON_EDIT, file.absolutePath)
            else -> EditModeDialog(this, file.absolutePath, ::startEditActivity).show()
        }
    }

    private fun startEditActivity(mode: Int, path: String) {
        val target = when (mode) {
            EditModeDialog.FULL_EDIT -> FullEditActivity::class.java
            EditModeDialog.SIMPLE_EDIT -> SimpleEditActivity::class.java
            EditModeDialog.COMMON_EDIT -> CommonEditActivity::class.java
            EditModeDialog.XML_FILE_EDIT -> AxmlEditActivity::class.java
            else -> null
        }
        if (target == null) Toast.makeText(this, getString(R.string.edit_mode_not_supported), Toast.LENGTH_SHORT).show()
        else startActivity(Intent(this, target).putExtra("apkPath", path))
    }

    private fun startSearch() {
        startActivity(Intent(this, ApkSearchActivity::class.java).apply {
            putExtra("Keyword", keyword.trim()); putExtra("Path", currentDirectory.absolutePath)
        })
    }

    private fun openExternalStorage() {
        StorageRoots.external(this)?.let(::openDirectory)
            ?: Toast.makeText(this, getString(R.string.cannot_find_ext_sdcard), Toast.LENGTH_SHORT).show()
    }

    private fun samePath(first: File?, second: File?): Boolean {
        if (first == null || second == null) return false
        return runCatching { first.canonicalPath == second.canonicalPath }.getOrDefault(first.absolutePath == second.absolutePath)
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val group = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(units.indices)
        return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, group.toDouble()), units[group])
    }
}

data class FileBrowserItem(
    val file: File,
    val parent: Boolean,
    val icon: ImageBitmap?,
    val detail: String
)
