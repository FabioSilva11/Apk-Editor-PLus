package com.saas.apkeditorplus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.appcompat.app.AlertDialog
import com.saas.apkeditorplus.ui.build.ApkBuildScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File

class ApkCreateActivity : BaseActivity() {
    private lateinit var apkPath: String
    private lateinit var modifiedFiles: Bundle
    private var deletedEntries: Set<String> = emptySet()
    private var outputApkFile: File? = null
    private var targetPackageName: String? = null

    private var building by mutableStateOf(true)
    private var buildSuccess by mutableStateOf<Boolean?>(null)
    private var detail by mutableStateOf("")
    private var canInstall by mutableStateOf(false)
    private var canUninstall by mutableStateOf(false)
    private var signingCredentials: SigningCredentials? = null

    private data class SigningCredentials(
        val file: File,
        val storePassword: String,
        val alias: String,
        val keyPassword: String
    )

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apkPath = intent.getStringExtra("apkPath").orEmpty()
        modifiedFiles = intent.getBundleExtra("modifiedFiles") ?: Bundle()
        deletedEntries = intent.getStringArrayListExtra("deletedEntries")?.toSet().orEmpty()

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
                    onClose = ::finish
                )
            }
        }
        if (apkPath.isBlank()) {
            showResult(false, getString(R.string.apk_path_not_found))
            return
        }
        targetPackageName = runCatching { packageManager.getPackageArchiveInfo(apkPath, 0)?.packageName }.getOrNull()
        prepareSigningAndBuild()
    }

    private fun prepareSigningAndBuild() {
        if (prefs.getString(AppSettings.DEFAULT_SIGNER, "testkey") != "ask") {
            useTestKeyAndBuild()
            return
        }
        detail = "Selecione a chave usada para assinar"
        AlertDialog.Builder(this)
            .setTitle("Chave de reconstrução")
            .setItems(arrayOf("Chave de teste", "Chave personalizada")) { _, index ->
                if (index == 0) useTestKeyAndBuild() else chooseCustomKeyStore()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun useTestKeyAndBuild() {
        signingCredentials = SigningCredentials(
            KeyStoreManager(this).getTestKey(), "testkey", "testkey", "testkey"
        )
        startBuildProcess()
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
            .setItems(stores.map(File::getName).toTypedArray()) { _, index ->
                requestStorePassword(stores[index])
            }
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
                signingCredentials = SigningCredentials(keyStore, storePassword, alias, input.text.toString())
                startBuildProcess()
            }
            .setNegativeButton(R.string.colormixer_cancel) { _, _ -> finish() }
            .show()
    }

    private fun passwordInput() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setPadding(50, 20, 50, 20)
    }

    private fun isAppInstalled(packageName: String): Boolean =
        runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private fun startBuildProcess() {
        building = true
        Thread {
            try {
                updateProgress(getString(R.string.rebuilding_apk))
                val unsignedApk = File(cacheDir, "unsigned.apk")
                val replacements = modifiedFiles.keySet().associateWith { File(modifiedFiles.getString(it).orEmpty()) }
                ApkBuildPipeline.rebuild(
                    context = this,
                    sourceApk = File(apkPath),
                    outputApk = unsignedApk,
                    changes = ApkBuildPipeline.ChangeSet(replacements, deletedEntries),
                    onProgress = ::updateProgress
                )
                targetPackageName = packageManager.getPackageArchiveInfo(unsignedApk.absolutePath, 0)?.packageName
                    ?: targetPackageName

                updateProgress(getString(R.string.signing_apk))
                val outputDir = getExternalFilesDir(null) ?: filesDir
                val pattern = prefs.getString("output_apk_name", "{package}_mod.apk") ?: "{package}_mod.apk"
                val safePackage = (targetPackageName ?: "modded").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val outputName = pattern.replace("{package}", safePackage)
                    .replace(Regex("[\\/:*?\"<>|]"), "_")
                    .let { if (it.endsWith(".apk", true)) it else "$it.apk" }
                val overwrite = prefs.getString(AppSettings.FILE_RENAME_MODE, "auto") == "overwrite"
                val signedApk = AppSettings.exportTarget(outputDir, outputName, overwrite)
                val success = signWithDefaultKey(unsignedApk, signedApk)
                runOnUiThread {
                    if (success) {
                        outputApkFile = signedApk
                        showResult(true, getString(R.string.apk_generated_success, signedApk.absolutePath))
                    } else {
                        showResult(false, getString(R.string.signing_failed_check_keys))
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { showResult(false, getString(R.string.error_during_build, error.message)) }
            }
        }.start()
    }

    private fun signWithDefaultKey(input: File, output: File): Boolean = runCatching {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val credentials = signingCredentials ?: error("Chave de assinatura não selecionada")
        ApkSignerManager().signApk(
            inputApk = input,
            outputApk = output,
            keyStoreFile = credentials.file,
            keyStorePassword = credentials.storePassword.toCharArray(),
            keyAlias = credentials.alias,
            keyPassword = credentials.keyPassword.toCharArray(),
            enableV1 = prefs.getBoolean("sign_v1", true),
            enableV2 = prefs.getBoolean("sign_v2", true),
            enableV3 = prefs.getBoolean("sign_v3", true),
            enableV4 = prefs.getBoolean(AppSettings.SIGN_V4, false),
            listener = object : ApkSignerManager.SignerListener {
                override fun onStart() = Unit
                override fun onProgress(message: String) = updateProgress(message)
                override fun onSuccess() = Unit
                override fun onError(message: String) = Unit
            }
        ) && output.exists() && output.length() > 0L
    }.getOrDefault(false)

    private fun updateProgress(message: String) = runOnUiThread { detail = message }

    private fun showResult(success: Boolean, message: String) {
        building = false
        buildSuccess = success
        canInstall = success && outputApkFile?.isFile == true
        canUninstall = success && targetPackageName?.let(::isAppInstalled) == true
        detail = if (success) {
            buildString {
                append(getString(R.string.carlos))
                append(String.format(getString(R.string.apk_savedas_1), outputApkFile?.absolutePath.orEmpty()))
                if (canUninstall) append("\n\n").append(getString(R.string.remove_tip))
            }
        } else message
    }

    private fun uninstallOriginal() {
        val pkg = targetPackageName
        if (pkg == null) {
            Toast.makeText(this, R.string.package_name_not_identified, Toast.LENGTH_SHORT).show()
        } else {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        }
    }

    private fun installNewApk() {
        val file = outputApkFile?.takeIf { it.isFile }
        if (file == null) {
            Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show()
            return
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
