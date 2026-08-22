package com.saas.apkeditorplus

import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saas.apkeditorplus.ui.keys.KeyStoreScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File

class KeyStoreListActivity : BaseActivity() {
    private lateinit var manager: KeyStoreManager
    private var files by mutableStateOf<List<File>>(emptyList())

    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "imported_key.p12"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Não foi possível ler a chave")
            manager.importKeyStore(name, bytes)
        }.onSuccess {
            files = manager.listKeyStores()
            Toast.makeText(this, "Chave importada", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show() }
    }

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        manager = KeyStoreManager(this)
        files = manager.listKeyStores()
        setContent {
            ApkEditorTheme {
                KeyStoreScreen(
                    files = files,
                    onBack = ::finish,
                    onImport = { importer.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "application/x-java-keystore")) },
                    onCreate = ::createKey,
                    onInspect = ::inspectKey,
                    onDelete = { file ->
                        if (file.delete()) files = manager.listKeyStores()
                    }
                )
            }
        }
    }

    private fun createKey(form: KeyCreationForm): String? {
        return runCatching {
            require(form.fileName.isNotBlank() && form.storePassword.isNotBlank() && form.alias.isNotBlank()) {
                "Preencha nome, senha e alias"
            }
            require(form.country.length == 2) { "País deve usar duas letras" }
            manager.createKeyStore(
                form.fileName,
                form.storePassword.toCharArray(),
                form.alias,
                form.commonName,
                form.organizationUnit,
                form.organization,
                form.locality,
                form.state,
                form.country.uppercase(),
                form.keyPassword.ifBlank { form.storePassword }.toCharArray()
            )
            files = manager.listKeyStores()
            null
        }.getOrElse { it.message ?: getString(R.string.failed) }
    }

    private fun inspectKey(file: File, password: String): Pair<List<KeyStoreManager.KeyAliasInfo>, String?> {
        return runCatching { manager.inspectKeyStore(file, password.toCharArray()) to null }
            .getOrElse { emptyList<KeyStoreManager.KeyAliasInfo>() to (it.message ?: "Senha inválida") }
    }
}

data class KeyCreationForm(
    val fileName: String = "",
    val storePassword: String = "",
    val alias: String = "",
    val keyPassword: String = "",
    val commonName: String = "APK Editor Plus",
    val organizationUnit: String = "Android",
    val organization: String = "Personal",
    val locality: String = "Manaus",
    val state: String = "Amazonas",
    val country: String = "BR"
)
