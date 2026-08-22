package com.saas.apkeditorplus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.saas.apkeditorplus.ui.build.ApkBuildScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File
import java.util.UUID

class ApkCreateActivity : BaseActivity() {
    private var apkPath = ""
    private var modifiedFiles = Bundle()
    private var deletedEntries: Set<String> = emptySet()
    private var jobId = ""
    private var outputApkFile: File? = null
    private var targetPackageName: String? = null
    private var receiverRegistered = false

    private var building by mutableStateOf(true)
    private var buildSuccess by mutableStateOf<Boolean?>(null)
    private var detail by mutableStateOf("")
    private var canInstall by mutableStateOf(false)
    private var canUninstall by mutableStateOf(false)

    private data class SigningCredentials(
        val file: File,
        val storePassword: String,
        val alias: String,
        val keyPassword: String
    )

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(ApkBuildService.EXTRA_JOB_ID) == jobId) refreshState()
        }
    }

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apkPath = intent.getStringExtra(ApkBuildService.EXTRA_APK_PATH).orEmpty()
        modifiedFiles = intent.getBundleExtra(ApkBuildService.EXTRA_REPLACEMENTS) ?: Bundle()
        deletedEntries = intent.getStringArrayListExtra(ApkBuildService.EXTRA_DELETIONS)?.toSet().orEmpty()
        jobId = intent.getStringExtra(ApkBuildService.EXTRA_JOB_ID).orEmpty().ifBlank {
            UUID.randomUUID().toString().also { intent.putExtra(ApkBuildService.EXTRA_JOB_ID, it) }
        }

        setContent {
            ApkEditorTheme {
                ApkBuildScreen(
                    building = building,
                    success = buildSuccess,
                    detail = detail,
                    canInstall = canInstall,
                    canUninstall = canUninstall,
                    onInstall = ::installNewApk,
                    onUninstall = ::uninstallOriginal,
                    onCancel = ::cancelBuild,
                    onClose = ::finish
                )
            }
        }

        val stored = BuildJobStore.read(this, jobId)
        if (stored != null || intent.getBooleanExtra(ApkBuildService.EXTRA_OBSERVE_ONLY, false)) {
            refreshState()
            if (stored == null) showResult(false, "O estado desta reconstrução não está mais disponível")
            return
        }
        if (apkPath.isBlank() || !File(apkPath).isFile) {
            showResult(false, getString(R.string.apk_path_not_found))
            return
        }
        targetPackageName = packageManager.getPackageArchiveInfo(apkPath, 0)?.packageName
        prepareSigningAndBuild()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                stateReceiver,
                IntentFilter(ApkBuildService.ACTION_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        refreshState()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun prepareSigningAndBuild() {
        if (prefs.getString(AppSettings.DEFAULT_SIGNER, "testkey") != "ask") {
            startBuildService(SigningCredentials(KeyStoreManager(this).getTestKey(), "testkey", "testkey", "testkey"))
            return
        }
        detail = "Selecione a chave usada para assinar"
        AlertDialog.Builder(this)
            .setTitle("Chave de reconstrução")
            .setItems(arrayOf("Chave de teste", "Chave personalizada")) { _, index ->
                if (index == 0) {
                    startBuildService(SigningCredentials(KeyStoreManager(this).getTestKey(), "testkey", "testkey", "testkey"))
                } else chooseCustomKeyStore()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun chooseCustomKeyStore() {
        val stores = KeyStoreManager(this).listKeyStores().filterNot { it.name == "testkey.jks" }
        if (stores.isEmpty()) {
            Toast.makeText(this, "Crie ou importe uma chave primeiro.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.select_keystore)
            .setItems(stores.map(File::getName).toTypedArray()) { _, index -> requestStorePassword(stores[index]) }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun requestStorePassword(keyStore: File) {
        val input = passwordInput()
        AlertDialog.Builder(this)
            .setTitle(R.string.password)
            .setView(FrameLayout(this).apply { addView(input) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = input.text.toString()
                val aliases = runCatching {
                    KeyStoreManager(this).inspectKeyStore(keyStore, password.toCharArray())
                }.getOrElse {
                    Toast.makeText(this, it.message ?: "Senha inválida", Toast.LENGTH_LONG).show()
                    finish()
                    return@setPositiveButton
                }
                if (aliases.size == 1) requestKeyPassword(keyStore, password, aliases.first().alias)
                else if (aliases.isNotEmpty()) AlertDialog.Builder(this)
                    .setTitle("Selecione o alias")
                    .setItems(aliases.map { it.alias }.toTypedArray()) { _, index ->
                        requestKeyPassword(keyStore, password, aliases[index].alias)
                    }.show()
                else {
                    Toast.makeText(this, "Nenhuma chave privada encontrada.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.colormixer_cancel) { _, _ -> finish() }
            .show()
    }

    private fun requestKeyPassword(keyStore: File, storePassword: String, alias: String) {
        val input = passwordInput().apply { setText(storePassword); selectAll() }
        AlertDialog.Builder(this)
            .setTitle("Senha da chave: $alias")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                startBuildService(SigningCredentials(keyStore, storePassword, alias, input.text.toString()))
            }
            .setNegativeButton(R.string.colormixer_cancel) { _, _ -> finish() }
            .show()
    }

    private fun passwordInput() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setPadding(50, 20, 50, 20)
    }

    private fun startBuildService(credentials: SigningCredentials) {
        building = true
        buildSuccess = null
        detail = getString(R.string.rebuilding_apk)
        ContextCompat.startForegroundService(
            this,
            Intent(this, ApkBuildService::class.java).setAction(ApkBuildService.ACTION_START).apply {
                putExtra(ApkBuildService.EXTRA_JOB_ID, jobId)
                putExtra(ApkBuildService.EXTRA_APK_PATH, apkPath)
                putExtra(ApkBuildService.EXTRA_REPLACEMENTS, modifiedFiles)
                putStringArrayListExtra(ApkBuildService.EXTRA_DELETIONS, ArrayList(deletedEntries))
                putExtra(ApkBuildService.EXTRA_KEYSTORE, credentials.file.absolutePath)
                putExtra(ApkBuildService.EXTRA_STORE_PASSWORD, credentials.storePassword)
                putExtra(ApkBuildService.EXTRA_ALIAS, credentials.alias)
                putExtra(ApkBuildService.EXTRA_KEY_PASSWORD, credentials.keyPassword)
            }
        )
    }

    private fun refreshState() {
        val state = BuildJobStore.read(this, jobId) ?: return
        targetPackageName = state.packageName.ifBlank { targetPackageName }
        outputApkFile = state.outputPath.takeIf(String::isNotBlank)?.let(::File)
        when (state.status) {
            BuildJobStore.Status.RUNNING -> {
                building = true
                buildSuccess = null
                detail = state.detail
                canInstall = false
                canUninstall = false
            }
            BuildJobStore.Status.SUCCESS -> showResult(true, state.detail)
            BuildJobStore.Status.FAILED -> showResult(false, state.detail)
            BuildJobStore.Status.CANCELLED -> showResult(false, state.detail)
        }
    }

    private fun showResult(success: Boolean, message: String) {
        building = false
        buildSuccess = success
        canInstall = success && outputApkFile?.isFile == true
        canUninstall = success && targetPackageName?.let(::isAppInstalled) == true
        detail = message
    }

    private fun cancelBuild() {
        startService(Intent(this, ApkBuildService::class.java).setAction(ApkBuildService.ACTION_CANCEL))
        detail = "Cancelando…"
    }

    private fun isAppInstalled(packageName: String): Boolean =
        runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private fun uninstallOriginal() {
        val pkg = targetPackageName
        if (pkg == null) Toast.makeText(this, R.string.package_name_not_identified, Toast.LENGTH_SHORT).show()
        else startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    }

    private fun installNewApk() {
        val file = outputApkFile?.takeIf { it.isFile } ?: run {
            Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show(); return
        }
        val installDir = getExternalFilesDir("apk") ?: getExternalFilesDir(null) ?: cacheDir
        installDir.mkdirs()
        val installFile = File(installDir, "gen.apk")
        file.copyTo(installFile, overwrite = true)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", installFile)
        startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
