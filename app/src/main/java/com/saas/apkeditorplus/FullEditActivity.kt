package com.saas.apkeditorplus

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.saas.apkeditorplus.full.FilesFragment
import com.saas.apkeditorplus.full.ManifestFragment
import com.saas.apkeditorplus.full.StringFragment
import java.io.File

class FullEditActivity : BaseActivity() {

    private lateinit var rootView: View
    private lateinit var headerContainer: View
    private lateinit var bottomTabsContainer: View
    private lateinit var viewPager: ViewPager2
    private lateinit var apkIconView: ImageView
    private lateinit var apkTitleView: TextView
    private lateinit var apkPackageView: TextView
    private lateinit var actionWebserverButton: ImageButton
    private lateinit var actionPatchButton: ImageButton
    private lateinit var actionBuildButton: TextView
    private lateinit var tabString: TextView
    private lateinit var tabFiles: TextView
    private lateinit var tabManifest: TextView

    private lateinit var apkInfoLoader: ApkArchiveInfoLoader

    private val modifiedFiles = linkedMapOf<String, String>()
    private val deletedEntries = linkedSetOf<String>()
    private var apkPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fulledit)

        apkPath = intent.getStringExtra("apkPath").orEmpty()

        rootView = findViewById(R.id.full_edit_root)
        headerContainer = findViewById(R.id.header_container)
        bottomTabsContainer = findViewById(R.id.bottom_tabs_container)
        viewPager = findViewById(R.id.view_pager)
        apkIconView = findViewById(R.id.apk_icon)
        apkTitleView = findViewById(R.id.apk_title)
        apkPackageView = findViewById(R.id.apk_package)
        actionWebserverButton = findViewById(R.id.action_webserver_button)
        actionPatchButton = findViewById(R.id.action_patch_button)
        actionBuildButton = findViewById(R.id.action_build_button)
        tabString = findViewById(R.id.tab_string)
        tabFiles = findViewById(R.id.tab_files)
        tabManifest = findViewById(R.id.tab_manifest)

        apkInfoLoader = ApkArchiveInfoLoader(this)

        setupViewPager()
        setupTabs()
        setupActions()
        applyWindowInsets()
        loadHeader()
        updateBuildButtonState()
    }

    override fun onDestroy() {
        apkInfoLoader.shutdown()
        super.onDestroy()
    }

    fun getApkPath(): String = apkPath

    fun registerModifiedEntry(entryName: String, file: File) {
        if (!file.exists()) {
            return
        }
        deletedEntries.remove(entryName)
        modifiedFiles[entryName] = file.absolutePath
        updateBuildButtonState()
    }

    fun registerDeletedEntry(entryName: String) {
        if (entryName.isBlank()) {
            return
        }
        val isDirectory = entryName.endsWith('/')
        modifiedFiles.keys
            .filter { it == entryName || (isDirectory && it.startsWith(entryName)) }
            .forEach(modifiedFiles::remove)
        deletedEntries.add(entryName)
        updateBuildButtonState()
    }

    fun discardModifiedEntry(entryName: String) {
        val isDirectory = entryName.endsWith('/')
        modifiedFiles.keys
            .filter { it == entryName || (isDirectory && it.startsWith(entryName)) }
            .forEach(modifiedFiles::remove)
        deletedEntries.remove(entryName)
        updateBuildButtonState()
    }

    fun modifiedEntryNames(): Set<String> = modifiedFiles.keys.toSet()

    fun isEntryDeleted(entryName: String): Boolean {
        return deletedEntries.any { deleted ->
            entryName == deleted || (deleted.endsWith('/') && entryName.startsWith(deleted))
        }
    }

    fun resolveModifiedEntry(entryName: String): File? {
        return modifiedFiles[entryName]
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(File::exists)
    }

    fun isEntryModified(entryName: String): Boolean = modifiedFiles.containsKey(entryName)

    private fun setupViewPager() {
        viewPager.adapter = FullEditPagerAdapter()
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTabSelection(position)
            }
        })
        updateTabSelection(0)
    }

    private fun setupTabs() {
        tabString.setOnClickListener { viewPager.currentItem = 0 }
        tabFiles.setOnClickListener { viewPager.currentItem = 1 }
        tabManifest.setOnClickListener { viewPager.currentItem = 2 }
    }

    private fun setupActions() {
        actionBuildButton.setOnClickListener { startBuildFlow() }

        val placeholderClickListener = View.OnClickListener {
            Toast.makeText(
                this,
                getString(R.string.action_future_update),
                Toast.LENGTH_SHORT
            ).show()
        }
        actionPatchButton.setOnClickListener(placeholderClickListener)
        actionWebserverButton.setOnClickListener(placeholderClickListener)
    }

    private fun updateTabSelection(position: Int) {
        listOf(tabString, tabFiles, tabManifest).forEachIndexed { index, tabView ->
            tabView.isSelected = index == position
        }
    }

    private fun loadHeader() {
        val fallbackTitle = apkPath
            .takeIf { it.isNotBlank() }
            ?.let { File(it).nameWithoutExtension }
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.full_edit)

        apkTitleView.text = fallbackTitle
        apkPackageView.text = File(apkPath).absolutePath
        apkIconView.setImageDrawable(
            AppCompatResources.getDrawable(this, R.drawable.apk_icon)
        )

        val cachedInfo = apkInfoLoader.get(apkPath)
        if (cachedInfo != null) {
            bindHeaderInfo(cachedInfo)
        } else if (apkPath.isNotBlank()) {
            apkInfoLoader.load(apkPath) {
                runOnUiThread {
                    apkInfoLoader.get(apkPath)?.let(::bindHeaderInfo)
                }
            }
        }
    }

    private fun bindHeaderInfo(info: ApkArchiveInfo) {
        if (info.label.isNotBlank()) {
            apkTitleView.text = info.label
        }
        apkPackageView.text = info.packageName.ifBlank { apkPath }
        apkIconView.setImageDrawable(
            info.icon ?: AppCompatResources.getDrawable(this, R.drawable.apk_icon)
        )
    }

    private fun updateBuildButtonState() {
        actionBuildButton.alpha = if (hasPendingChanges()) 1f else 0.74f
    }

    private fun hasPendingChanges(): Boolean = modifiedFiles.isNotEmpty() || deletedEntries.isNotEmpty()

    private fun startBuildFlow() {
        if (apkPath.isBlank()) {
            Toast.makeText(this, getString(R.string.apk_path_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasPendingChanges()) {
            Toast.makeText(this, getString(R.string.no_change_detected), Toast.LENGTH_SHORT).show()
            return
        }

        val bundle = Bundle().apply {
            modifiedFiles.forEach { (entryName, path) ->
                putString(entryName, path)
            }
        }

        startActivity(
            Intent(this, ApkCreateActivity::class.java).apply {
                putExtra("apkPath", apkPath)
                putExtra("modifiedFiles", bundle)
                putStringArrayListExtra("deletedEntries", ArrayList(deletedEntries))
            }
        )
    }

    override fun onBackPressed() {
        if (!hasPendingChanges()) {
            super.onBackPressed()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.save_changes)
            .setMessage(R.string.unsaved_changes_msg)
            .setPositiveButton(R.string.build) { _, _ ->
                startBuildFlow()
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                finish()
            }
            .setNeutralButton(R.string.colormixer_cancel, null)
            .show()
    }

    private fun applyWindowInsets() {
        val initialHeaderTop = headerContainer.paddingTop
        val initialTabsBottom = bottomTabsContainer.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerContainer.updatePadding(top = initialHeaderTop + systemBars.top)
            bottomTabsContainer.updatePadding(bottom = initialTabsBottom + systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private inner class FullEditPagerAdapter : FragmentStateAdapter(this@FullEditActivity) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> StringFragment.newInstance(apkPath)
                1 -> FilesFragment.newInstance(apkPath)
                2 -> ManifestFragment.newInstance(apkPath)
                else -> StringFragment.newInstance(apkPath)
            }
        }
    }

    override fun shouldHideActionBar(): Boolean = true
}
