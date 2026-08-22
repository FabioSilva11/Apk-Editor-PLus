package com.saas.apkeditorplus

import android.content.Context
import com.reandroid.apk.ApkModule
import com.reandroid.archive.FileInputSource
import com.saas.apkeditorplus.full.FullEditRepository
import com.saas.apkeditorplus.utils.AxmlEncoder
import java.io.File

/**
 * Single, strict APK assembly path shared by every editor mode.
 *
 * ARSCLib owns archive writing so original compression metadata and Android's
 * uncompressed-file rules are retained. Text XML is never copied into an APK
 * when binary encoding fails.
 */
object ApkBuildPipeline {

    data class ChangeSet(
        val replacements: Map<String, File>,
        val deletions: Set<String> = emptySet()
    )

    fun rebuild(
        context: Context,
        sourceApk: File,
        outputApk: File,
        changes: ChangeSet,
        onProgress: (String) -> Unit = {}
    ) {
        require(sourceApk.isFile) { "APK file not found: ${sourceApk.absolutePath}" }
        outputApk.parentFile?.mkdirs()
        if (outputApk.exists()) {
            check(outputApk.delete()) { "Could not replace ${outputApk.absolutePath}" }
        }

        ApkModule.loadApkFile(sourceApk).use { module ->
            onProgress("Preparing APK entries…")
            module.setApkSignatureBlock(null)
            removeOldSignatures(module)
            applyDeletions(module, changes.deletions)

            val compiledDexFiles = linkedMapOf<String, File>()
            changes.replacements.toSortedMap().forEach { (entryName, replacement) ->
                require(isSafeArchiveEntry(entryName)) { "Invalid archive entry: $entryName" }
                require(replacement.exists()) { "Modified file not found: $entryName" }

                val prepared = when {
                    FullEditRepository.isDexEntry(entryName) && replacement.isDirectory -> {
                        onProgress("Rebuilding $entryName…")
                        compiledDexFiles.getOrPut(entryName) {
                            FullEditRepository.compileSmaliWorkspaceToDex(
                                context = context,
                                apkPath = sourceApk.absolutePath,
                                dexEntryName = entryName,
                                smaliDir = replacement
                            )
                        }
                    }

                    isAxmlFile(entryName) && replacement.isFile && !isBinaryAxml(replacement) -> {
                        onProgress("Compiling $entryName…")
                        encodeBinaryXml(context, entryName, replacement)
                    }

                    else -> replacement
                }

                if (prepared.isDirectory) {
                    // ZIP directory entries are optional. Non-empty directory changes are
                    // represented by their child files; an empty folder has no APK semantics.
                    return@forEach
                }

                val original = module.getInputSource(entryName)
                val source = FileInputSource(prepared, entryName)
                if (original != null) {
                    source.copyAttributes(original)
                }
                if (entryName == FullEditRepository.RESOURCES_ENTRY) {
                    source.setUncompressed(true)
                }
                module.removeInputSource(entryName)
                module.add(source)
            }

            module.updateUncompressedFiles()
            module.getInputSource(FullEditRepository.RESOURCES_ENTRY)?.setUncompressed(true)
            onProgress("Writing aligned APK…")
            module.writeApk(outputApk)
        }

        validateUnsignedApk(outputApk)
    }

    private fun removeOldSignatures(module: ApkModule) {
        module.listInputSources()
            .map { it.alias }
            .filter { name ->
                name.startsWith("META-INF/", ignoreCase = true) &&
                    (name.endsWith(".SF", ignoreCase = true) ||
                        name.endsWith(".RSA", ignoreCase = true) ||
                        name.endsWith(".DSA", ignoreCase = true) ||
                        name.endsWith(".EC", ignoreCase = true) ||
                        name.equals("META-INF/MANIFEST.MF", ignoreCase = true))
            }
            .forEach(module::removeInputSource)
    }

    private fun applyDeletions(module: ApkModule, deletions: Set<String>) {
        if (deletions.isEmpty()) return
        module.listInputSources()
            .map { it.alias }
            .filter { entryName ->
                deletions.any { deleted ->
                    entryName == deleted || (deleted.endsWith('/') && entryName.startsWith(deleted))
                }
            }
            .forEach(module::removeInputSource)
    }

    private fun encodeBinaryXml(context: Context, entryName: String, source: File): File {
        val encoded = AxmlEncoder().encode(source.readText(), context)
            ?: error("Failed to compile binary XML: $entryName")
        require(encoded.isNotEmpty()) { "Empty binary XML generated: $entryName" }
        val outputDir = File(context.cacheDir, "compiled_axml").apply { mkdirs() }
        val output = File(outputDir, entryName.replace('/', '_'))
        output.writeBytes(encoded)
        require(isBinaryAxml(output)) { "Invalid binary XML generated: $entryName" }
        return output
    }

    private fun validateUnsignedApk(apk: File) {
        require(apk.isFile && apk.length() > 0L) { "Empty APK output" }
        ApkModule.loadApkFile(apk).use { module ->
            require(module.hasAndroidManifestBlock()) { "Generated APK has no binary AndroidManifest.xml" }
            require(module.getPackageName().isNotBlank()) { "Generated APK has no package name" }
            require(module.listDexFiles().isNotEmpty()) { "Generated APK has no DEX file" }
            if (module.containsFile(FullEditRepository.RESOURCES_ENTRY)) {
                require(module.hasTableBlock()) { "Generated APK has an invalid resources.arsc" }
            }
        }
    }

    private fun isSafeArchiveEntry(entryName: String): Boolean {
        return entryName.isNotBlank() &&
            !entryName.startsWith('/') &&
            !entryName.startsWith('\\') &&
            entryName.split('/', '\\').none { it == ".." }
    }

    private fun isAxmlFile(name: String): Boolean {
        return name == FullEditRepository.MANIFEST_ENTRY ||
            (name.startsWith("res/") && name.endsWith(".xml", ignoreCase = true))
    }

    private fun isBinaryAxml(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return file.inputStream().use { input ->
            val header = ByteArray(4)
            if (input.read(header) != header.size) {
                false
            } else {
                val magic = (header[0].toInt() and 0xff) or
                    ((header[1].toInt() and 0xff) shl 8) or
                    ((header[2].toInt() and 0xff) shl 16) or
                    ((header[3].toInt() and 0xff) shl 24)
                magic == 0x00080003
            }
        }
    }
}
