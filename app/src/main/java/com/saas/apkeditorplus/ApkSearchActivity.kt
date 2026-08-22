package com.saas.apkeditorplus

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.saas.apkeditorplus.ui.files.ApkSearchItem
import com.saas.apkeditorplus.ui.files.ApkSearchScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class ApkSearchActivity : BaseActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var infoLoader: ApkArchiveInfoLoader
    private var keyword = ""
    private var searchPath = ""
    private var items by mutableStateOf<List<ApkSearchItem>>(emptyList())
    private var searching by mutableStateOf(true)

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyword = intent.getStringExtra("Keyword").orEmpty().trim()
        searchPath = intent.getStringExtra("Path").orEmpty().trim()
        infoLoader = ApkArchiveInfoLoader(this)
        setContent {
            ApkEditorTheme {
                ApkSearchScreen(keyword, items, searching, ::finish) { openEditMode(it.file) }
            }
        }
        startSearch()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        infoLoader.shutdown()
        super.onDestroy()
    }

    private fun startSearch() {
        val root = File(searchPath)
        if (!root.isDirectory) {
            searching = false
            Toast.makeText(this, R.string.error_filepath_notexist, Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = keyword.lowercase(Locale.getDefault())
        executor.execute {
            root.walkTopDown()
                .onEnter { !Thread.currentThread().isInterrupted }
                .filter { file -> file.isFile && file.extension.equals("apk", true) }
                .filter { file -> normalized.isBlank() || file.name.lowercase(Locale.getDefault()).contains(normalized) }
                .forEach { file ->
                    if (Thread.currentThread().isInterrupted) return@execute
                    val info = infoLoader.loadBlocking(file.absolutePath)
                    val item = ApkSearchItem(
                        file = file,
                        label = info?.label.orEmpty(),
                        packageName = info?.packageName.orEmpty(),
                        icon = info?.icon?.toBitmap(96, 96)?.asImageBitmap()
                    )
                    runOnUiThread { if (!isDestroyed) items = items + item }
                }
            runOnUiThread { if (!isDestroyed) searching = false }
        }
    }

    private fun openEditMode(file: File) {
        EditModeDialog(this, file.absolutePath) { mode, path ->
            val target = when (mode) {
                EditModeDialog.FULL_EDIT -> FullEditActivity::class.java
                EditModeDialog.SIMPLE_EDIT -> SimpleEditActivity::class.java
                EditModeDialog.COMMON_EDIT -> CommonEditActivity::class.java
                EditModeDialog.XML_FILE_EDIT -> AxmlEditActivity::class.java
                else -> null
            }
            if (target == null) Toast.makeText(this, R.string.edit_mode_not_supported, Toast.LENGTH_SHORT).show()
            else startActivity(Intent(this, target).putExtra("apkPath", path))
        }.show()
    }
}
