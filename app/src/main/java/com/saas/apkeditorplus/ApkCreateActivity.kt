package com.saas.apkeditorplus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.saas.apkeditorplus.full.FullEditRepository
import com.saas.apkeditorplus.utils.AxmlEncoder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkCreateActivity : BaseActivity() {

    private lateinit var layoutGenerating: LinearLayout
    private lateinit var layoutReinstall: LinearLayout
    private lateinit var tvDetail: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnInstall: Button
    private lateinit var btnUninstall: Button
    private lateinit var ivResult: ImageView
    private lateinit var apkPath: String
    private lateinit var modifiedFiles: Bundle
    private var deletedEntries: Set<String> = emptySet()
    private var outputApkFile: File? = null
    private var targetPackageName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simpleedit_making)

        layoutGenerating = findViewById(R.id.layout_apk_generating)
        layoutReinstall = findViewById(R.id.layout_apk_reinstall)
        tvDetail = findViewById(R.id.tv_detail)
        tvResult = findViewById(R.id.result)
        btnInstall = findViewById(R.id.button_reinstall)
        btnUninstall = findViewById(R.id.button_uninstall)
        ivResult = findViewById(R.id.result_image)

        apkPath = intent.getStringExtra("apkPath") ?: ""
        modifiedFiles = intent.getBundleExtra("modifiedFiles") ?: Bundle()
        deletedEntries = intent.getStringArrayListExtra("deletedEntries")?.toSet().orEmpty()

        findViewById<Button>(R.id.button_close).setOnClickListener { finish() }
        btnUninstall.setOnClickListener { uninstallOriginal() }
        btnInstall.setOnClickListener { installNewApk() }

        extractPackageName()
        startBuildProcess()
    }

    private fun extractPackageName() {
        try {
            val info = packageManager.getPackageArchiveInfo(apkPath, 0)
            targetPackageName = info?.packageName
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startBuildProcess() {
        layoutGenerating.visibility = View.VISIBLE
        layoutReinstall.visibility = View.GONE

        Thread {
            try {
                updateProgress(getString(R.string.rebuilding_apk))
                val unsignedApk = File(cacheDir, "unsigned.apk")
                rebuildApk(unsignedApk)

                updateProgress(getString(R.string.signing_apk))
                val outputDir = getExternalFilesDir(null) ?: filesDir
                val signedApk = File(outputDir, "${targetPackageName ?: "modded"}_pro.apk")
                val success = signWithDefaultOrFirstKey(unsignedApk, signedApk)

                runOnUiThread {
                    if (success) {
                        outputApkFile = signedApk
                        showResult(true, getString(R.string.apk_generated_success, signedApk.absolutePath))
                    } else {
                        showResult(false, getString(R.string.signing_failed_check_keys))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    showResult(false, getString(R.string.error_during_build, e.message))
                }
            }
        }.start()
    }

    private fun signWithDefaultOrFirstKey(input: File, output: File): Boolean {
        return try {
            output.parentFile?.mkdirs()
            if (output.exists()) {
                output.delete()
            }

            val keyStoreFile = KeyStoreManager(this).getTestKey()
            ApkSignerManager().signApk(
                inputApk = input,
                outputApk = output,
                keyStoreFile = keyStoreFile,
                keyStorePassword = "testkey".toCharArray(),
                keyAlias = "testkey",
                keyPassword = "testkey".toCharArray(),
                listener = object : ApkSignerManager.SignerListener {
                    override fun onStart() = Unit

                    override fun onProgress(message: String) {
                        updateProgress(message)
                    }

                    override fun onSuccess() = Unit

                    override fun onError(message: String) = Unit
                }
            ) && output.exists() && output.length() > 0L
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun rebuildApk(outputFile: File) {
        ZipFile(apkPath).use { zipFile ->
            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                val compiledDexFiles = linkedMapOf<String, File>()
                val writtenEntries = linkedSetOf<String>()
                val entries = zipFile.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()

                    if (isDeletedEntry(entry.name)) {
                        continue
                    }

                    if (entry.name.startsWith("META-INF/") &&
                        (entry.name.endsWith(".SF") ||
                            entry.name.endsWith(".RSA") ||
                            entry.name.endsWith(".MF"))
                    ) {
                        continue
                    }

                    writtenEntries.add(entry.name)
                    zos.putNextEntry(ZipEntry(entry.name))

                    val modifiedPath = modifiedFiles.getString(entry.name)
                    if (modifiedPath != null) {
                        val modifiedFile = File(modifiedPath)
                        writeModifiedEntry(entry.name, modifiedFile, zos, compiledDexFiles)
                    } else {
                        zipFile.getInputStream(entry).use { it.copyTo(zos) }
                    }

                    zos.closeEntry()
                }

                modifiedFiles.keySet()
                    .asSequence()
                    .filterNot(writtenEntries::contains)
                    .filterNot(::isDeletedEntry)
                    .sorted()
                    .forEach { entryName ->
                        require(isSafeArchiveEntry(entryName)) { "Invalid archive entry: $entryName" }
                        val modifiedFile = File(modifiedFiles.getString(entryName).orEmpty())
                        require(modifiedFile.exists()) { "Modified file not found: $entryName" }

                        val normalizedName = if (modifiedFile.isDirectory && !entryName.endsWith('/')) {
                            "$entryName/"
                        } else {
                            entryName
                        }
                        zos.putNextEntry(ZipEntry(normalizedName))
                        if (modifiedFile.isFile) {
                            writeModifiedEntry(normalizedName, modifiedFile, zos, compiledDexFiles)
                        }
                        zos.closeEntry()
                    }
            }
        }
    }

    private fun writeModifiedEntry(
        entryName: String,
        modifiedFile: File,
        zos: ZipOutputStream,
        compiledDexFiles: MutableMap<String, File>
    ) {
        if (FullEditRepository.isDexEntry(entryName) && modifiedFile.isDirectory) {
            val compiledDex = compiledDexFiles.getOrPut(entryName) {
                updateProgress("${getString(R.string.rebuilding_apk)} ($entryName)")
                FullEditRepository.compileSmaliWorkspaceToDex(
                    context = this,
                    apkPath = apkPath,
                    dexEntryName = entryName,
                    smaliDir = modifiedFile
                )
            }
            compiledDex.inputStream().use { it.copyTo(zos) }
        } else if (isAxmlFile(entryName)) {
            if (isBinaryAxml(modifiedFile)) {
                modifiedFile.inputStream().use { it.copyTo(zos) }
            } else {
                val binaryData = AxmlEncoder().encode(modifiedFile.readText(), this)
                if (binaryData != null) {
                    zos.write(binaryData as ByteArray)
                } else {
                    modifiedFile.inputStream().use { it.copyTo(zos) }
                }
            }
        } else {
            modifiedFile.inputStream().use { it.copyTo(zos) }
        }
    }

    private fun isDeletedEntry(entryName: String): Boolean {
        return deletedEntries.any { deleted ->
            entryName == deleted || (deleted.endsWith('/') && entryName.startsWith(deleted))
        }
    }

    private fun isSafeArchiveEntry(entryName: String): Boolean {
        return entryName.isNotBlank() &&
            !entryName.startsWith('/') &&
            !entryName.startsWith('\\') &&
            entryName.split('/', '\\').none { it == ".." }
    }

    private fun isAxmlFile(name: String): Boolean {
        return name == "AndroidManifest.xml" || (name.startsWith("res/") && name.endsWith(".xml"))
    }

    private fun isBinaryAxml(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                if (read == 4) {
                    val magic = (header[0].toInt() and 0xFF) or
                        ((header[1].toInt() and 0xFF) shl 8) or
                        ((header[2].toInt() and 0xFF) shl 16) or
                        ((header[3].toInt() and 0xFF) shl 24)
                    magic == 0x00080003
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun updateProgress(message: String) {
        runOnUiThread { tvDetail.text = message }
    }

    private fun showResult(success: Boolean, message: String) {
        layoutGenerating.visibility = View.GONE
        layoutReinstall.visibility = View.VISIBLE

        if (success) {
            ivResult.setImageResource(R.drawable.ic_select)
            btnInstall.visibility = View.VISIBLE

            val resultText = getString(R.string.carlos) +
                String.format(getString(R.string.apk_savedas_1), outputApkFile?.absolutePath ?: "") +
                "\n\n"

            targetPackageName?.let { pkg ->
                if (isAppInstalled(pkg)) {
                    val removeTip = getString(R.string.remove_tip)
                    val spannable = android.text.SpannableStringBuilder(resultText + removeTip)
                    val start = resultText.length
                    val end = spannable.length

                    spannable.setSpan(
                        android.text.style.AbsoluteSizeSpan(12, true),
                        start,
                        end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(android.graphics.Color.DKGRAY),
                        start,
                        end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    tvResult.text = spannable
                    btnUninstall.visibility = View.VISIBLE
                } else {
                    tvResult.text = resultText
                    btnUninstall.visibility = View.GONE
                }
            } ?: run {
                tvResult.text = resultText
                btnUninstall.visibility = View.GONE
            }
        } else {
            ivResult.setImageResource(R.drawable.ic_close)
            tvResult.text = message
            btnInstall.visibility = View.GONE
            btnUninstall.visibility = View.GONE
        }
    }

    private fun uninstallOriginal() {
        targetPackageName?.let { pkg ->
            startActivity(
                Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$pkg")
                }
            )
        } ?: Toast.makeText(this, getString(R.string.package_name_not_identified), Toast.LENGTH_SHORT)
            .show()
    }

    private fun installNewApk() {
        outputApkFile?.let { file ->
            if (!file.exists()) {
                Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
                return
            }

            val installDir = getExternalFilesDir("apk") ?: getExternalFilesDir(null) ?: cacheDir
            if (!installDir.exists()) {
                installDir.mkdirs()
            }

            val installFile = File(installDir, "gen.apk")
            file.copyTo(installFile, overwrite = true)

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", installFile)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }
}
