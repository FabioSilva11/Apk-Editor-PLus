package com.saas.apkeditorplus

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saas.apkeditorplus.ui.signing.SignFileScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File

class SelectFileActivity : BaseActivity() {
    private var currentDir by mutableStateOf(File(Environment.getExternalStorageDirectory().path))
    private var files by mutableStateOf<List<File>>(emptyList())

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApkEditorTheme {
                SignFileScreen(
                    currentPath = currentDir.absolutePath,
                    files = files,
                    onBack = ::navigateBack,
                    onFileClick = ::handleFile
                )
            }
        }
        refreshFiles()
    }

    private fun refreshFiles() {
        val children = currentDir.listFiles().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        files = listOfNotNull(currentDir.parentFile?.let { File(it, "..") }) + children
    }

    private fun navigateBack() {
        currentDir.parentFile?.let { currentDir = it; refreshFiles() } ?: finish()
    }

    private fun handleFile(file: File) {
        if (file.name == "..") {
            navigateBack()
        } else if (file.isDirectory) {
            currentDir = file
            refreshFiles()
        } else if (file.extension.equals("apk", true)) {
            showSignOptionsDialog(file)
        } else {
            Toast.makeText(this, R.string.select_apk_to_sign, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSignOptionsDialog(apkFile: File) {
        val options = arrayOf(getString(R.string.sign_with_testkey), getString(R.string.sign_with_custom_keystore))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sign_apk_title, apkFile.name))
            .setItems(options) { _, which -> if (which == 0) signWithTestKey(apkFile) else selectCustomKeyStore(apkFile) }
            .show()
    }

    private fun signWithTestKey(apkFile: File) {
        val testKey = KeyStoreManager(this).getTestKey()
        launchSigning(apkFile, testKey, "testkey", "testkey", "testkey", "_signed.apk")
    }

    private fun selectCustomKeyStore(apkFile: File) {
        val stores = KeyStoreManager(this).listKeyStores()
        if (stores.isEmpty()) {
            Toast.makeText(this, R.string.signing_failed_check_keys, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.select_keystore)
            .setItems(stores.map { it.name }.toTypedArray()) { _, index -> requestKeyStorePassword(apkFile, stores[index]) }
            .show()
    }

    private fun requestKeyStorePassword(apkFile: File, ksFile: File) {
        val input = passwordInput()
        val container = FrameLayout(this).apply { addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.password)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int ->
                input.text.toString().takeIf { it.isNotEmpty() }?.let { inspectAliases(apkFile, ksFile, it) }
            }
            .setNegativeButton(R.string.colormixer_cancel, null)
            .show()
    }

    private fun inspectAliases(apkFile: File, ksFile: File, storePassword: String) {
        val aliases = runCatching { KeyStoreManager(this).inspectKeyStore(ksFile, storePassword.toCharArray()) }
            .getOrElse {
                Toast.makeText(this, it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show()
                return
            }
        if (aliases.isEmpty()) {
            Toast.makeText(this, "Nenhuma chave privada encontrada", Toast.LENGTH_LONG).show()
        } else if (aliases.size == 1) {
            requestPrivateKeyPassword(apkFile, ksFile, storePassword, aliases.first().alias)
        } else {
            AlertDialog.Builder(this).setTitle("Selecione o alias")
                .setItems(aliases.map { it.alias }.toTypedArray()) { _, index ->
                    requestPrivateKeyPassword(apkFile, ksFile, storePassword, aliases[index].alias)
                }.show()
        }
    }

    private fun requestPrivateKeyPassword(apkFile: File, ksFile: File, storePassword: String, alias: String) {
        val input = passwordInput().apply { setText(storePassword); setSelectAllOnFocus(true) }
        AlertDialog.Builder(this)
            .setTitle("Senha da chave: $alias")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                launchSigning(apkFile, ksFile, storePassword, alias, input.text.toString(), "_signed_custom.apk")
            }
            .setNegativeButton(R.string.colormixer_cancel, null)
            .show()
    }

    private fun passwordInput() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setPadding(50, 20, 50, 20)
    }

    private fun launchSigning(apk: File, keyStore: File, storePass: String, alias: String, keyPass: String, suffix: String) {
        val output = File(apk.parentFile, apk.nameWithoutExtension + suffix)
        startActivityForResult(Intent(this, SigningProgressActivity::class.java).apply {
            putExtra("inputPath", apk.absolutePath)
            putExtra("outputPath", output.absolutePath)
            putExtra("ksPath", keyStore.absolutePath)
            putExtra("ksPass", storePass)
            putExtra("alias", alias)
            putExtra("keyPass", keyPass)
        }, REQUEST_CODE_SIGNING)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SIGNING && resultCode == RESULT_OK) {
            data?.getStringExtra("targetPath")?.let { currentDir = File(it); refreshFiles() }
        }
    }

    companion object { private const val REQUEST_CODE_SIGNING = 1001 }
}
