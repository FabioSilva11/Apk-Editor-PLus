package com.saas.apkeditorplus

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.saas.apkeditorplus.full.FilesFragment
import com.saas.apkeditorplus.full.DiffFragment
import com.saas.apkeditorplus.full.ManifestFragment
import com.saas.apkeditorplus.full.StringFragment
import com.saas.apkeditorplus.full.TypedResourcesFragment
import com.saas.apkeditorplus.ui.full.FullEditScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FullEditActivity : BaseActivity() {
    private lateinit var viewPager: ViewPager2
    private lateinit var apkInfoLoader: ApkArchiveInfoLoader
    private lateinit var projectStore: ProjectStore
    private val modifiedFiles = linkedMapOf<String, String>()
    private val deletedEntries = linkedSetOf<String>()
    private var apkPath = ""
    private var restoredProjectId: String? = null
    private var webServer: ApkWebServer? = null
    private var title by mutableStateOf("")
    private var packageLabel by mutableStateOf("")
    private var icon by mutableStateOf<ImageBitmap?>(null)
    private var selectedTab by mutableStateOf(0)
    private var hasChanges by mutableStateOf(false)
    private var patchBusy by mutableStateOf(false)
    private var serverRunning by mutableStateOf(false)
    var changesRevision by mutableIntStateOf(0)
        private set

    data class PendingChange(
        val entryName: String,
        val modifiedFile: File?,
        val deleted: Boolean
    )

    private val patchLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val patchFile = File(cacheDir, "selected_${System.currentTimeMillis()}.zip")
        runCatching {
            contentResolver.openInputStream(uri)?.use { input -> patchFile.outputStream().use(input::copyTo) }
                ?: error("Não foi possível ler o patch")
        }.onSuccess { applyPatch(patchFile) }
            .onFailure { Toast.makeText(this, it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show() }
    }

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        apkPath = intent.getStringExtra("apkPath").orEmpty()
        restoredProjectId = intent.getStringExtra("projectId")
        if (!File(apkPath).isFile) {
            Toast.makeText(this, getString(R.string.apk_path_not_found), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        projectStore = ProjectStore(this)
        apkInfoLoader = ApkArchiveInfoLoader(this)
        intent.getBundleExtra("modifiedFiles")?.let { restored ->
            restored.keySet().forEach { name -> restored.getString(name)?.let { modifiedFiles[name] = it } }
        }
        deletedEntries.addAll(intent.getStringArrayListExtra("deletedEntries").orEmpty())
        title = File(apkPath).nameWithoutExtension.ifBlank { getString(R.string.full_edit) }
        packageLabel = apkPath
        hasChanges = hasPendingChanges()

        setContent {
            ApkEditorTheme {
                FullEditScreen(
                    title = title,
                    packageName = packageLabel,
                    icon = icon,
                    selectedTab = selectedTab,
                    hasChanges = hasChanges,
                    patchBusy = patchBusy,
                    serverRunning = serverRunning,
                    onBack = { onBackPressed() },
                    onBuild = ::requestBuild,
                    onPatch = { patchLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onWebServer = ::toggleWebServer,
                    onTabSelected = { position ->
                        selectedTab = position
                        if (::viewPager.isInitialized && viewPager.currentItem != position) {
                            viewPager.setCurrentItem(position, true)
                        }
                    },
                    onPagerReady = ::setupViewPager
                )
            }
        }
        loadHeader()
    }

    override fun onDestroy() {
        webServer?.close()
        apkInfoLoader.shutdown()
        super.onDestroy()
    }

    fun getApkPath(): String = apkPath

    fun registerModifiedEntry(entryName: String, file: File) {
        if (!file.exists()) return
        deletedEntries.remove(entryName)
        modifiedFiles[entryName] = file.absolutePath
        changesUpdated()
    }

    fun registerDeletedEntry(entryName: String) {
        if (entryName.isBlank()) return
        val directory = entryName.endsWith('/')
        modifiedFiles.keys.filter { it == entryName || directory && it.startsWith(entryName) }
            .forEach(modifiedFiles::remove)
        deletedEntries.add(entryName)
        changesUpdated()
    }

    fun discardModifiedEntry(entryName: String) {
        val directory = entryName.endsWith('/')
        modifiedFiles.keys.filter { it == entryName || directory && it.startsWith(entryName) }
            .forEach(modifiedFiles::remove)
        deletedEntries.remove(entryName)
        changesUpdated()
    }

    fun modifiedEntryNames(): Set<String> = modifiedFiles.keys.toSet()
    fun isEntryDeleted(entryName: String): Boolean = deletedEntries.any {
        entryName == it || it.endsWith('/') && entryName.startsWith(it)
    }
    fun resolveModifiedEntry(entryName: String): File? = modifiedFiles[entryName]
        ?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::exists)
    fun isEntryModified(entryName: String): Boolean = modifiedFiles.containsKey(entryName)
    fun pendingChangesSnapshot(): List<PendingChange> =
        (modifiedFiles.keys + deletedEntries).distinct().sorted().map { entryName ->
            PendingChange(
                entryName = entryName,
                modifiedFile = modifiedFiles[entryName]?.let(::File)?.takeIf(File::exists),
                deleted = deletedEntries.contains(entryName)
            )
        }

    private fun setupViewPager(pager: ViewPager2) {
        if (::viewPager.isInitialized && viewPager === pager) return
        viewPager = pager
        if (pager.id == View.NO_ID) pager.id = View.generateViewId()
        pager.adapter = FullEditPagerAdapter()
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { selectedTab = position }
        })
        pager.setCurrentItem(selectedTab, false)
    }

    private fun changesUpdated() {
        hasChanges = hasPendingChanges()
        changesRevision++
        if (apkPath.isBlank()) return
        runCatching {
            if (hasChanges) projectStore.save(apkPath, modifiedFiles, deletedEntries, restoredProjectId)
            else restoredProjectId?.let(projectStore::delete) ?: projectStore.deleteForApk(apkPath)
        }
    }

    private fun loadHeader() {
        val cached = apkInfoLoader.get(apkPath)
        if (cached != null) bindHeaderInfo(cached)
        else apkInfoLoader.load(apkPath) { runOnUiThread { apkInfoLoader.get(apkPath)?.let(::bindHeaderInfo) } }
    }

    private fun bindHeaderInfo(info: ApkArchiveInfo) {
        if (info.label.isNotBlank()) title = info.label
        packageLabel = info.packageName.ifBlank { apkPath }
        val drawable = info.icon ?: AppCompatResources.getDrawable(this, R.drawable.apk_icon)
        icon = drawable?.toBitmap(144, 144)?.asImageBitmap()
    }

    private fun applyPatch(patchFile: File) {
        lifecycleScope.launch {
            patchBusy = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ApkPatchEngine.apply(
                        applicationContext,
                        File(apkPath),
                        patchFile,
                        modifiedFiles.mapValues { File(it.value) }
                    )
                }
            }
            patchBusy = false
            result.onSuccess { applied ->
                applied.replacements.forEach(::registerModifiedEntry)
                applied.deletions.forEach(::registerDeletedEntry)
                AlertDialog.Builder(this@FullEditActivity)
                    .setTitle("${applied.appliedRules} regra(s) aplicadas")
                    .setMessage(applied.reports.joinToString("\n").ifBlank { "Patch aplicado sem alterações descritivas." })
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }.onFailure { error ->
                AlertDialog.Builder(this@FullEditActivity).setTitle(R.string.patch)
                    .setMessage(error.message ?: getString(R.string.failed))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    private fun toggleWebServer() {
        webServer?.let {
            it.close()
            webServer = null
            serverRunning = false
            Toast.makeText(this, "Servidor encerrado", Toast.LENGTH_SHORT).show()
            return
        }
        val server = ApkWebServer(
            File(apkPath),
            { modifiedFiles.mapValues { File(it.value) } },
            { deletedEntries.toSet() }
        )
        val address = runCatching { server.start() }.getOrElse { error ->
            server.close()
            Toast.makeText(this, error.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show()
            return
        }
        webServer = server
        serverRunning = true
        AlertDialog.Builder(this).setTitle(R.string.web_server)
            .setMessage("Servidor iniciado em:\n$address\n\nUse outro aparelho na mesma rede Wi-Fi.")
            .setPositiveButton("Abrir") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(address))) }
            .setNegativeButton("Parar") { _, _ -> toggleWebServer() }
            .setNeutralButton(android.R.string.ok, null).show()
    }

    private fun requestBuild() {
        if (!hasPendingChanges()) {
            Toast.makeText(this, getString(R.string.no_change_detected), Toast.LENGTH_SHORT).show()
            return
        }
        confirmRebuild(::startBuildFlow)
    }

    private fun startBuildFlow() {
        val bundle = Bundle().apply { modifiedFiles.forEach(::putString) }
        startActivity(Intent(this, ApkCreateActivity::class.java).apply {
            putExtra("apkPath", apkPath)
            putExtra("modifiedFiles", bundle)
            putStringArrayListExtra("deletedEntries", ArrayList(deletedEntries))
        })
    }

    override fun onBackPressed() {
        if (!hasPendingChanges()) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.save_changes).setMessage(R.string.unsaved_changes_msg)
            .setPositiveButton(R.string.build) { _, _ -> requestBuild() }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(R.string.colormixer_cancel, null).show()
    }

    private fun hasPendingChanges(): Boolean = modifiedFiles.isNotEmpty() || deletedEntries.isNotEmpty()

    private inner class FullEditPagerAdapter : FragmentStateAdapter(this@FullEditActivity) {
        override fun getItemCount(): Int = 5
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> StringFragment.newInstance(apkPath)
            1 -> FilesFragment.newInstance(apkPath)
            2 -> TypedResourcesFragment.newInstance(apkPath)
            3 -> ManifestFragment.newInstance(apkPath)
            4 -> DiffFragment.newInstance(apkPath)
            else -> StringFragment.newInstance(apkPath)
        }
    }
}
