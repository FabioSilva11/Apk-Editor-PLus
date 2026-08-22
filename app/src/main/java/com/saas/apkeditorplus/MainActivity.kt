package com.saas.apkeditorplus

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.saas.apkeditorplus.ui.home.MainScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme

class MainActivity : BaseActivity() {

    private val storagePermissionCode = 1
    private var showAbout by mutableStateOf(false)
    private var showStorageAccess by mutableStateOf(false)

    private val mainMenuItems by lazy {
        listOf(
            MainMenuItem(
                titleRes = R.string.action_apk,
                subtitleRes = R.string.action_apk_desc,
                iconRes = R.drawable.ic_apk_shortcut,
                destination = MainMenuDestination.APK_FILE
            ),
            MainMenuItem(
                titleRes = R.string.action_app,
                subtitleRes = R.string.action_app_desc,
                iconRes = R.drawable.ic_app_shortcut,
                destination = MainMenuDestination.INSTALLED_APP
            ),
            MainMenuItem(
                titleRes = R.string.common_edit,
                subtitleRes = R.string.common_edit,
                iconRes = R.drawable.ic_edit_1,
                destination = MainMenuDestination.COMMON_EDIT
            ),
            MainMenuItem(
                titleRes = R.string.action_prj,
                subtitleRes = R.string.action_prj_desc,
                iconRes = R.drawable.ic_project_shortcut,
                destination = MainMenuDestination.PROJECTS
            ),
            MainMenuItem(
                titleRes = R.string.action_sign,
                subtitleRes = R.string.action_sign_desc,
                iconRes = R.drawable.ic_select,
                destination = MainMenuDestination.SIGN_APK
            ),
            MainMenuItem(
                titleRes = R.string.action_verify,
                subtitleRes = R.string.action_verify_desc,
                iconRes = R.drawable.ic_search,
                destination = MainMenuDestination.VERIFY_SIGNATURE
            ),
            MainMenuItem(
                titleRes = R.string.action_db,
                subtitleRes = R.string.action_db_desc,
                iconRes = R.drawable.ic_edit_3,
                destination = MainMenuDestination.SIGNATURE_KEYS
            ),
            MainMenuItem(
                titleRes = R.string.action_info,
                subtitleRes = R.string.action_info_desc,
                iconRes = R.drawable.ic_person,
                destination = MainMenuDestination.GIT_STATUS
            ),
            MainMenuItem(
                titleRes = R.string.action_sett,
                subtitleRes = R.string.action_sett_desc,
                iconRes = R.drawable.ic_theme,
                destination = MainMenuDestination.SETTINGS
            ),
            MainMenuItem(
                titleRes = R.string.action_exit,
                subtitleRes = R.string.action_exit_desc,
                iconRes = R.drawable.ic_close,
                destination = MainMenuDestination.EXIT
            )
        )
    }

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        setContent {
            ApkEditorTheme {
                MainScreen(
                    items = mainMenuItems,
                    showAboutDialog = showAbout,
                    showStorageAccessDialog = showStorageAccess,
                    onDestinationClick = { openDestination(it.destination) },
                    onToggleTheme = ::toggleTheme,
                    onShare = ::shareApp,
                    onOpenTelegram = ::openTelegram,
                    onShowAbout = { showAbout = true },
                    onDismissAbout = { showAbout = false },
                    onOpenStorageSettings = ::openAllFilesAccessSettings,
                    onDismissStorageAccess = { showStorageAccess = false }
                )
            }
        }

        checkStoragePermissions()
    }

    private fun openDestination(destination: MainMenuDestination) {
        val intent = when (destination) {
            MainMenuDestination.APK_FILE -> Intent(this, FileListActivity::class.java)
            MainMenuDestination.INSTALLED_APP -> Intent(this, UserAppActivity::class.java)
            MainMenuDestination.COMMON_EDIT -> {
                Intent(this, FileListActivity::class.java).apply {
                    putExtra("select_for_common_edit", true)
                }
            }
            MainMenuDestination.PROJECTS -> Intent(this, ProjectListActivity::class.java)
            MainMenuDestination.SIGN_APK -> Intent(this, SelectFileActivity::class.java)
            MainMenuDestination.VERIFY_SIGNATURE -> Intent(this, VerifyActivity::class.java)
            MainMenuDestination.SIGNATURE_KEYS -> Intent(this, KeyStoreListActivity::class.java)
            MainMenuDestination.GIT_STATUS -> Intent(this, InfoActivity::class.java)
            MainMenuDestination.SETTINGS -> Intent(this, SettingActivity::class.java)
            MainMenuDestination.EXIT -> {
                finish()
                null
            }
        }

        intent?.let(::startActivity)
    }

    private fun toggleTheme() {
        val currentMode = AppCompatDelegate.getDefaultNightMode()
        val nextMode = if (currentMode == AppCompatDelegate.MODE_NIGHT_YES) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }

        AppCompatDelegate.setDefaultNightMode(nextMode)
        prefs.edit().putInt("theme_mode", nextMode).apply()
        recreate()
    }

    private fun shareApp() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
            putExtra(
                Intent.EXTRA_TEXT,
                "Baixe o ${getString(R.string.app_name)}: https://github.com/FabioSilva11/Apk-Editor-PLus"
            )
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.menu_share_app)))
    }

    private fun openTelegram() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/seu_grupo")))
        } catch (_: Exception) {
            Toast.makeText(this, "Erro ao abrir o Telegram", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkStoragePermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VIDEO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                storagePermissionCode
            )
        } else {
            checkAllFilesAccess()
        }
    }

    private fun checkAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            showStorageAccess = true
        }
    }

    private fun openAllFilesAccessSettings() {
        showStorageAccess = false
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                addCategory("android.intent.category.DEFAULT")
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            showStorageAccess = false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == storagePermissionCode) {
            val allGranted = grantResults.isNotEmpty() &&
                grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                checkAllFilesAccess()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.storage_permission_required),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
