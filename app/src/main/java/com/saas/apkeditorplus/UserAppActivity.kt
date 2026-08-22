package com.saas.apkeditorplus

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saas.apkeditorplus.ui.apps.InstalledAppsScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File
import kotlin.concurrent.thread

class UserAppActivity : BaseActivity() {
    private var showSystemApps by mutableStateOf(false)
    private var apps by mutableStateOf<List<AppInfo>>(emptyList())
    private var loading by mutableStateOf(true)

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApkEditorTheme {
                InstalledAppsScreen(
                    apps = apps,
                    loading = loading,
                    showSystemApps = showSystemApps,
                    onBack = ::finish,
                    onShowUserApps = { if (showSystemApps) { showSystemApps = false; loadApps() } },
                    onShowSystemApps = { if (!showSystemApps) { showSystemApps = true; loadApps() } },
                    onAppClick = { showEditModeDialog(it.sourceDir) }
                )
            }
        }
        loadApps()
    }

    private fun loadApps() {
        loading = true
        val system = showSystemApps
        thread {
            val pm = packageManager
            val loaded = pm.getInstalledApplications(0).mapNotNull { app ->
                val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                if (isSystem != system) return@mapNotNull null
                AppInfo(
                    name = app.loadLabel(pm).toString(),
                    packageName = app.packageName,
                    sourceDir = app.publicSourceDir,
                    icon = runCatching { app.loadIcon(pm) }.getOrNull(),
                    lastModified = File(app.publicSourceDir).lastModified()
                )
            }.let { found ->
                if (prefs.getString(AppSettings.APP_LIST_ORDER, "name") == "date") {
                    found.sortedByDescending { it.lastModified }
                } else {
                    found.sortedBy { it.name.lowercase() }
                }
            }
            runOnUiThread {
                if (!isFinishing && system == showSystemApps) {
                    apps = loaded
                    loading = false
                }
            }
        }
    }

    private fun showEditModeDialog(path: String) {
        EditModeDialog(this, path) { mode, apkPath -> startEditActivity(mode, apkPath) }.show()
    }

    private fun startEditActivity(mode: Int, path: String) {
        val target = when (mode) {
            EditModeDialog.FULL_EDIT -> FullEditActivity::class.java
            EditModeDialog.SIMPLE_EDIT -> SimpleEditActivity::class.java
            EditModeDialog.COMMON_EDIT -> CommonEditActivity::class.java
            EditModeDialog.XML_FILE_EDIT -> AxmlEditActivity::class.java
            else -> null
        }
        if (target == null) {
            Toast.makeText(this, R.string.edit_mode_not_supported, Toast.LENGTH_SHORT).show()
        } else {
            startActivity(Intent(this, target).putExtra("apkPath", path))
        }
    }
}
