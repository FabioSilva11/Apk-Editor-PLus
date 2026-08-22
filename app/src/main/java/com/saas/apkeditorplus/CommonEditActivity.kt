package com.saas.apkeditorplus

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.saas.apkeditorplus.full.FullEditRepository
import com.saas.apkeditorplus.ui.common.CommonEditScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommonEditActivity : BaseActivity() {
    private lateinit var apkPath: String
    private var snapshot: CommonEditEngine.Snapshot? = null
    private var replacementIconFile: File? = null
    private var launcherIconEntries: List<String> = emptyList()
    private var form by mutableStateOf(CommonEditForm())
    private var icon by mutableStateOf<ImageBitmap?>(null)
    private var loading by mutableStateOf(true)
    private var saving by mutableStateOf(false)

    private val iconPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importLauncherIcon)
    }

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        apkPath = intent.getStringExtra("apkPath").orEmpty()
        if (apkPath.isBlank() || !File(apkPath).isFile) {
            Toast.makeText(this, getString(R.string.apk_path_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            ApkEditorTheme {
                CommonEditScreen(
                    title = snapshot?.appLabel ?: File(apkPath).nameWithoutExtension,
                    form = form,
                    icon = icon,
                    loading = loading,
                    saving = saving,
                    onFormChange = { form = it },
                    onPickIcon = { iconPicker.launch(arrayOf("image/png", "image/webp", "image/jpeg")) },
                    onClose = ::finish,
                    onSave = ::saveChanges
                )
            }
        }
        loadApkInfo()
    }

    private fun loadApkInfo() {
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { CommonEditEngine.read(File(apkPath)) } }
                .getOrElse { error ->
                    Toast.makeText(this@CommonEditActivity, getString(R.string.error_loading_apk_info, error.message), Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
            snapshot = result
            form = CommonEditForm(
                appName = result.appLabel.orEmpty(),
                packageName = result.packageName,
                versionCode = result.versionCode.toString(),
                versionName = result.versionName,
                minSdk = result.minSdk?.toString().orEmpty(),
                targetSdk = result.targetSdk?.toString().orEmpty(),
                maxSdk = result.maxSdk?.toString().orEmpty(),
                installLocation = result.installLocation,
                renameResources = true,
                renameDex = false
            )
            runCatching {
                packageManager.getPackageArchiveInfo(apkPath, 0)?.applicationInfo?.let { appInfo ->
                    appInfo.sourceDir = apkPath
                    appInfo.publicSourceDir = apkPath
                    icon = appInfo.loadIcon(packageManager).toBitmap(192, 192).asImageBitmap()
                    if (form.appName.isBlank()) form = form.copy(appName = appInfo.loadLabel(packageManager).toString())
                }
            }
            launcherIconEntries = withContext(Dispatchers.IO) {
                CommonEditEngine.findLauncherIconEntries(File(apkPath))
            }
            loading = false
        }
    }

    private fun importLauncherIcon(uri: Uri) {
        val target = File(cacheDir, "common_edit/launcher_replacement.png")
        runCatching {
            target.parentFile?.mkdirs()
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
                ?: error("Não foi possível ler a imagem")
            require(target.length() > 0L) { "A imagem selecionada está vazia" }
            val bitmap = BitmapFactory.decodeFile(target.absolutePath) ?: error("Formato de imagem inválido")
            replacementIconFile = target
            icon = bitmap.asImageBitmap()
        }.onFailure { Toast.makeText(this, it.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show() }
    }

    private fun saveChanges() {
        confirmRebuild(::performSaveChanges)
    }

    private fun performSaveChanges() {
        val original = snapshot ?: return
        val changes = runCatching {
            CommonEditEngine.Changes(
                packageName = form.packageName.trim(),
                versionCode = form.versionCode.toInt(),
                versionName = form.versionName,
                appLabel = form.appName,
                minSdk = form.minSdk.toIntOrNull(),
                targetSdk = form.targetSdk.toIntOrNull(),
                maxSdk = form.maxSdk.toIntOrNull(),
                installLocation = form.installLocation,
                renameResourcePackage = form.renameResources
            )
        }.getOrElse {
            Toast.makeText(this, getString(R.string.invalid_ver_code), Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        lifecycleScope.launch {
            val output = runCatching {
                withContext(Dispatchers.IO) {
                    val commonOutput = CommonEditEngine.apply(this@CommonEditActivity, File(apkPath), changes)
                    val dexChanges = if (form.renameDex && original.packageName != changes.packageName) {
                        prepareDexPackageRename(original.packageName, changes.packageName)
                    } else emptyMap()
                    commonOutput to dexChanges
                }
            }.getOrElse { error ->
                saving = false
                Toast.makeText(this@CommonEditActivity, getString(R.string.error_saving, error.message), Toast.LENGTH_LONG).show()
                return@launch
            }
            val modified = Bundle().apply {
                putString("AndroidManifest.xml", output.first.manifestFile.absolutePath)
                output.first.resourcesFile?.let { putString("resources.arsc", it.absolutePath) }
                output.second.forEach { (name, workspace) -> putString(name, workspace.absolutePath) }
                replacementIconFile?.let { image ->
                    buildIconReplacements(image).forEach { (entry, file) -> putString(entry, file.absolutePath) }
                }
            }
            startActivity(Intent(this@CommonEditActivity, ApkCreateActivity::class.java).apply {
                putExtra("apkPath", apkPath)
                putExtra("modifiedFiles", modified)
            })
            saving = false
        }
    }

    private fun prepareDexPackageRename(oldPackage: String, newPackage: String): Map<String, File> {
        require(oldPackage.isNotBlank() && newPackage.isNotBlank()) { "Nome de pacote inválido" }
        val oldDescriptor = "L${oldPackage.replace('.', '/')}"
        val newDescriptor = "L${newPackage.replace('.', '/')}"
        val replacements = linkedMapOf<String, File>()
        ZipFile(apkPath).use { zip ->
            zip.entries().asSequence().map { it.name }.filter(FullEditRepository::isDexEntry).sorted().forEach { dexEntry ->
                val workspace = FullEditRepository.prepareDexSmaliWorkspace(this, apkPath, dexEntry)
                var changed = false
                workspace.rootDir.walkTopDown().filter { it.isFile && it.extension.equals("smali", true) }.forEach { file ->
                    val original = file.readText()
                    val updated = original.replace(oldDescriptor, newDescriptor).replace(oldPackage, newPackage)
                    if (updated != original) {
                        file.writeText(updated)
                        changed = true
                    }
                }
                if (changed) replacements[dexEntry] = workspace.rootDir
            }
        }
        return replacements
    }

    private fun buildIconReplacements(source: File): Map<String, File> {
        val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: error("Ícone selecionado inválido")
        val directory = File(cacheDir, "common_edit/icons").apply { mkdirs() }
        return launcherIconEntries.associateWith { entryName ->
            val extension = entryName.substringAfterLast('.', "png").lowercase()
            val target = File(directory, entryName.replace('/', '_'))
            val format = when (extension) {
                "webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                else -> Bitmap.CompressFormat.PNG
            }
            target.outputStream().use { output ->
                check(bitmap.compress(format, 100, output)) { "Falha ao converter ícone para $extension" }
            }
            target
        }
    }
}

data class CommonEditForm(
    val appName: String = "",
    val packageName: String = "",
    val versionCode: String = "",
    val versionName: String = "",
    val minSdk: String = "",
    val targetSdk: String = "",
    val maxSdk: String = "",
    val installLocation: Int? = null,
    val renameResources: Boolean = true,
    val renameDex: Boolean = false
)
