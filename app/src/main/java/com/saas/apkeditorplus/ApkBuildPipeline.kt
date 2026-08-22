package com.saas.apkeditorplus

import android.content.Context
import com.reandroid.apk.ApkModule
import com.reandroid.archive.FileInputSource
import com.saas.apkeditorplus.full.FullEditRepository
import com.saas.apkeditorplus.full.FullEditWorkspaceManager
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

        val textualXmlChanges = changes.replacements.filter { (entryName, source) ->
            isAxmlFile(entryName) && source.isFile && !isBinaryAxml(source)
        }
        val resourcePrerequisites = if (textualXmlChanges.isEmpty()) emptyMap() else {
            changes.replacements.filter { (entryName, source) ->
                source.isFile && (entryName == FullEditRepository.RESOURCES_ENTRY ||
                    entryName == FullEditRepository.MANIFEST_ENTRY && isBinaryAxml(source))
            }
        }
        val stagedBase = if (resourcePrerequisites.isNotEmpty()) {
            onProgress("Preparing unified resource base…")
            createResourceBase(context, sourceApk, resourcePrerequisites)
        } else null
        val assemblyBase = stagedBase ?: sourceApk

        try {
            ApkModule.loadApkFile(assemblyBase).use { module ->
            onProgress("Preparing APK entries…")
            module.setApkSignatureBlock(null)
            removeOldSignatures(module)
            applyDeletions(module, changes.deletions)

            if (textualXmlChanges.isNotEmpty()) {
                onProgress("Compiling XML resources…")
                FullEditWorkspaceManager.applyTextXmlChanges(
                    context = context,
                    apkPath = assemblyBase.absolutePath,
                    apkModule = module,
                    changes = textualXmlChanges
                )
            }

            val compiledDexFiles = linkedMapOf<String, File>()
            changes.replacements.toSortedMap().forEach { (entryName, replacement) ->
                require(isSafeArchiveEntry(entryName)) { "Invalid archive entry: $entryName" }
                require(replacement.exists()) { "Modified file not found: $entryName" }

                if (entryName in textualXmlChanges || entryName in resourcePrerequisites) return@forEach

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

                    else -> replacement
                }

                if (prepared.isDirectory) {
                    // ZIP directory entries are optional. Non-empty directory changes are
                    // represented by their child files; an empty folder has no APK semantics.
                    return@forEach
                }

                replaceEntry(module, entryName, prepared)
            }

            module.updateUncompressedFiles()
            module.getInputSource(FullEditRepository.RESOURCES_ENTRY)?.setUncompressed(true)
            onProgress("Writing aligned APK…")
            module.writeApk(outputApk)
            }
        } finally {
            if (stagedBase != null) {
                FullEditWorkspaceManager.discardWorkspace(context, stagedBase.absolutePath)
            }
            stagedBase?.parentFile?.deleteRecursively()
        }

        validateUnsignedApk(outputApk)
    }

    /**
     * Materializes already-compiled Manifest/resources changes before decoding textual XML.
     * This prevents a later XML compilation from silently reverting typed/string edits,
     * including when a saved project is reopened in a new process.
     */
    private fun createResourceBase(
        context: Context,
        sourceApk: File,
        prerequisites: Map<String, File>
    ): File {
        val directory = File(context.cacheDir, "resource_base/${System.nanoTime()}").apply { mkdirs() }
        val stagedApk = File(directory, "base.apk")
        ApkModule.loadApkFile(sourceApk).use { module ->
            prerequisites.forEach { (entryName, replacement) ->
                require(replacement.isFile) { "Modified resource not found: $entryName" }
                replaceEntry(module, entryName, replacement)
            }
            module.updateUncompressedFiles()
            module.getInputSource(FullEditRepository.RESOURCES_ENTRY)?.setUncompressed(true)
            module.writeApk(stagedApk)
        }
        require(stagedApk.isFile && stagedApk.length() > 0L) { "Could not prepare resource compilation base" }
        return stagedApk
    }

    private fun replaceEntry(module: ApkModule, entryName: String, replacement: File) {
        val original = module.getInputSource(entryName)
        val source = FileInputSource(replacement, entryName)
        if (original != null) source.copyAttributes(original)
        if (entryName == FullEditRepository.RESOURCES_ENTRY) source.setUncompressed(true)
        module.removeInputSource(entryName)
        module.add(source)
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
