package com.saas.apkeditorplus.full

import android.content.Context
import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.saas.apkeditorplus.AppSettings
import com.saas.apkeditorplus.utils.AxmlDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

object FullEditRepository {
    const val MANIFEST_ENTRY = "AndroidManifest.xml"
    const val RESOURCES_ENTRY = "resources.arsc"

    private val editableTextExtensions = setOf(
        "css", "gradle", "html", "java", "js", "json", "kt", "md",
        "properties", "smali", "svg", "txt", "xml", "yml", "yaml"
    )

    data class ArchiveItem(
        val displayName: String,
        val entryName: String,
        val isDirectory: Boolean
    )

    data class StringResourceItem(
        val name: String,
        val value: String?,
        val valueIndex: Int? = null,
        val localeQualifier: String = "",
        val needsTranslation: Boolean = false
    )

    data class SmaliWorkspace(
        val dexEntryName: String,
        val rootDir: File,
        val returnArchivePath: String
    )

    fun decodeManifest(apkPath: String): String {
        return decodeXmlEntry(apkPath, MANIFEST_ENTRY)
    }

    fun parseStringResources(
        apkPath: String,
        localeQualifier: String = ""
    ): List<StringResourceItem> {
        return ResourceStringEditor.parseFromApk(apkPath, localeQualifier)
    }

    fun parseStringResourcesFromArscFile(
        arscFile: File,
        localeQualifier: String = ""
    ): List<StringResourceItem> {
        return ResourceStringEditor.parseFromArscFile(arscFile, localeQualifier)
    }

    fun listStringResourceLocales(
        apkPath: String,
        arscSourceFile: File? = null
    ): List<String> {
        return ResourceStringEditor.listLocales(apkPath, arscSourceFile)
    }

    fun exportStringResourcesEditorFile(
        context: Context,
        apkPath: String,
        arscSourceFile: File? = null,
        localeQualifier: String = ""
    ): File {
        return ResourceStringEditor.exportToXml(
            context = context,
            apkPath = apkPath,
            arscSourceFile = arscSourceFile,
            localeQualifier = localeQualifier
        )
    }

    fun buildResourcesArscFromEditedStrings(
        context: Context,
        apkPath: String,
        editedStringsXml: File,
        arscSourceFile: File? = null,
        localeQualifier: String = ""
    ): File {
        return ResourceStringEditor.buildArscFromEditedXml(
            context = context,
            apkPath = apkPath,
            editedStringsXml = editedStringsXml,
            arscSourceFile = arscSourceFile,
            localeQualifier = localeQualifier
        )
    }

    fun buildResourcesArscFromOverrides(
        context: Context,
        apkPath: String,
        overrides: Map<String, String>,
        arscSourceFile: File? = null,
        localeQualifier: String = ""
    ): File {
        return ResourceStringEditor.buildArscWithOverrides(
            context = context,
            apkPath = apkPath,
            overrides = overrides,
            arscSourceFile = arscSourceFile,
            localeQualifier = localeQualifier
        )
    }

    fun listDirectory(apkPath: String, currentPath: String): List<ArchiveItem> {
        ZipFile(apkPath).use { zipFile ->
            val displayList = mutableListOf<ArchiveItem>()
            if (currentPath.isNotEmpty()) {
                displayList.add(ArchiveItem("..", parentPathOf(currentPath), true))
            }

            val folders = linkedSetOf<String>()
            val files = mutableListOf<ArchiveItem>()
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (!name.startsWith(currentPath)) {
                    continue
                }
                val relativeName = name.removePrefix(currentPath)
                if (relativeName.isBlank()) {
                    continue
                }
                val parts = relativeName.split("/")
                if (parts.size > 1) {
                    folders.add(parts.first())
                } else if (!entry.isDirectory) {
                    files.add(
                        ArchiveItem(
                            displayName = relativeName,
                            entryName = name,
                            isDirectory = false
                        )
                    )
                }
            }

            folders.sorted().forEach { folderName ->
                displayList.add(
                    ArchiveItem(
                        displayName = folderName,
                        entryName = "$currentPath$folderName/",
                        isDirectory = true
                    )
                )
            }
            displayList.addAll(files.sortedBy { it.displayName.lowercase() })
            return displayList
        }
    }

    fun prepareDexSmaliWorkspace(
        context: Context,
        apkPath: String,
        dexEntryName: String
    ): SmaliWorkspace {
        require(isDexEntry(dexEntryName)) { "Not a dex entry: $dexEntryName" }

        val workspace = SmaliWorkspace(
            dexEntryName = dexEntryName,
            rootDir = buildSmaliWorkspaceRoot(context, apkPath, dexEntryName),
            returnArchivePath = parentPathOf(dexEntryName)
        )
        val markerFile = File(workspace.rootDir, ".ready")
        val hasVisibleFiles = workspace.rootDir.listFiles()?.any { !it.name.startsWith(".") } == true
        if (markerFile.exists() && hasVisibleFiles) {
            return workspace
        }

        workspace.rootDir.deleteRecursively()
        workspace.rootDir.mkdirs()

        val dexFile = loadDexFileFromApk(apkPath, dexEntryName)
        val success = Baksmali.disassembleDexFile(
            dexFile,
            workspace.rootDir,
            workerCount(),
            BaksmaliOptions().apply {
                apiLevel = dexFile.opcodes.api
            }
        )
        if (!success) {
            workspace.rootDir.deleteRecursively()
            error("Failed to convert $dexEntryName to smali")
        }

        markerFile.writeText("ready")
        return workspace
    }

    fun listSmaliDirectory(workspace: SmaliWorkspace, currentPath: String): List<ArchiveItem> {
        val relativePath = currentPath.removeSuffix("/")
        val currentDir = if (relativePath.isBlank()) {
            workspace.rootDir
        } else {
            File(workspace.rootDir, relativePath)
        }
        if (!currentDir.exists() || !currentDir.isDirectory) {
            error("Smali directory not found: $currentPath")
        }

        val displayList = mutableListOf<ArchiveItem>()
        displayList.add(
            ArchiveItem(
                displayName = "..",
                entryName = if (relativePath.isBlank()) "" else parentPathOf("$relativePath/"),
                isDirectory = true
            )
        )

        val children = currentDir.listFiles()
            ?.filterNot { it.name.startsWith(".") }
            .orEmpty()

        children
            .filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
            .forEach { directory ->
                displayList.add(
                    ArchiveItem(
                        displayName = directory.name,
                        entryName = buildChildPath(currentPath, directory.name, true),
                        isDirectory = true
                    )
                )
            }

        children
            .filter { it.isFile }
            .sortedBy { it.name.lowercase() }
            .forEach { file ->
                displayList.add(
                    ArchiveItem(
                        displayName = file.name,
                        entryName = buildChildPath(currentPath, file.name, false),
                        isDirectory = false
                    )
                )
            }

        return displayList
    }

    fun extractEntryForEditing(context: Context, apkPath: String, entryName: String): File {
        if (entryName == MANIFEST_ENTRY || (entryName.startsWith("res/") && entryName.endsWith(".xml", true))) {
            return FullEditWorkspaceManager.getXmlFileForEditing(context, apkPath, entryName)
        }
        val targetDir = AppSettings.workspaceRoot(context, "full_edit")
        val targetFile = File(targetDir, entryName.replace("/", "_"))

        ZipFile(apkPath).use { zipFile ->
            val entry = zipFile.getEntry(entryName) ?: error("Entry not found: $entryName")
            zipFile.getInputStream(entry).use { inputStream ->
                FileOutputStream(targetFile).use { output -> inputStream.copyTo(output) }
            }
        }
        return targetFile
    }

    fun resolveSmaliWorkspaceFile(workspace: SmaliWorkspace, relativePath: String): File {
        return File(workspace.rootDir, relativePath.removeSuffix("/"))
    }

    fun compileSmaliWorkspaceToDex(
        context: Context,
        apkPath: String,
        dexEntryName: String,
        smaliDir: File
    ): File {
        require(isDexEntry(dexEntryName)) { "Not a dex entry: $dexEntryName" }
        require(smaliDir.exists() && smaliDir.isDirectory) {
            "Smali workspace not found: ${smaliDir.absolutePath}"
        }

        val dexFile = loadDexFileFromApk(apkPath, dexEntryName)
        val outputDir = AppSettings.workspaceRoot(context, "full_edit_built_dex")
        val outputDex = File(outputDir, "${workspaceKey(apkPath, dexEntryName)}.dex")
        if (outputDex.exists()) {
            outputDex.delete()
        }

        val success = Smali.assemble(
            SmaliOptions().apply {
                apiLevel = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getInt("smali_api", dexFile.opcodes.api)
                outputDexFile = outputDex.absolutePath
                jobs = workerCount()
            },
            smaliDir.absolutePath
        )
        if (!success || !outputDex.exists()) {
            error("Failed to compile smali back to $dexEntryName")
        }
        DexBackedDexFile(null, outputDex.readBytes()).classes.iterator().hasNext()

        return outputDex
    }

    fun isEditableTextEntry(entryName: String): Boolean {
        if (entryName.endsWith("/")) {
            return false
        }
        val extension = entryName.substringAfterLast('.', "")
        return extension.lowercase() in editableTextExtensions
    }

    fun isDexEntry(entryName: String): Boolean {
        return entryName.endsWith(".dex", ignoreCase = true)
    }

    private fun decodeXmlEntry(apkPath: String, entryName: String): String {
        ZipFile(apkPath).use { zipFile ->
            val entry = zipFile.getEntry(entryName) ?: error("Entry not found: $entryName")
            zipFile.getInputStream(entry).use { inputStream ->
                val output = ByteArrayOutputStream()
                val decoded = AxmlDecoder().decode(inputStream, output)
                if (!decoded) {
                    error("Failed to decode XML: $entryName")
                }
                return output.toString(Charsets.UTF_8.name())
            }
        }
    }

    private fun parentPathOf(path: String): String {
        val normalized = path.removeSuffix("/")
        val lastSlash = normalized.lastIndexOf('/')
        return if (lastSlash == -1) "" else normalized.substring(0, lastSlash + 1)
    }

    private fun buildSmaliWorkspaceRoot(context: Context, apkPath: String, dexEntryName: String): File {
        val baseDir = AppSettings.workspaceRoot(context, "full_edit_smali")
        return File(baseDir, "${workspaceKey(apkPath, dexEntryName)}_${sanitizeEntryName(dexEntryName)}")
    }

    private fun loadDexFileFromApk(apkPath: String, dexEntryName: String): DexBackedDexFile {
        ZipFile(apkPath).use { zipFile ->
            val entry = zipFile.getEntry(dexEntryName) ?: error("Entry not found: $dexEntryName")
            zipFile.getInputStream(entry).use { inputStream ->
                return DexBackedDexFile(null, inputStream.readBytes())
            }
        }
    }

    private fun buildChildPath(currentPath: String, childName: String, isDirectory: Boolean): String {
        val basePath = currentPath.removeSuffix("/")
        val childPath = if (basePath.isBlank()) childName else "$basePath/$childName"
        return if (isDirectory) "$childPath/" else childPath
    }

    private fun workspaceKey(apkPath: String, entryName: String): String {
        val apkFile = File(apkPath)
        val identity = "$apkPath|${apkFile.length()}|${apkFile.lastModified()}|$entryName"
        val digest = MessageDigest.getInstance("SHA-1").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sanitizeEntryName(entryName: String): String {
        return entryName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun workerCount(): Int {
        return 1
    }

    fun searchArchive(
        apkPath: String,
        query: String,
        searchContent: Boolean = true,
        maxResults: Int = 500
    ): List<ArchiveItem> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val results = mutableListOf<ArchiveItem>()
        ZipFile(apkPath).use { zipFile ->
            val entries = zipFile.entries()
            while (entries.hasMoreElements() && results.size < maxResults) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val nameMatch = entry.name.contains(needle, ignoreCase = true)
                val contentMatch = !nameMatch && searchContent &&
                    isEditableTextEntry(entry.name) && entry.size in 0..1_048_576 &&
                    runCatching {
                        zipFile.getInputStream(entry).bufferedReader().use { reader ->
                            val buffer = CharArray(4096)
                            var carry = ""
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                val chunk = carry + String(buffer, 0, count)
                                if (chunk.contains(needle, ignoreCase = true)) return@runCatching true
                                carry = chunk.takeLast(needle.length.coerceAtMost(256))
                            }
                            false
                        }
                    }.getOrDefault(false)
                if (nameMatch || contentMatch) {
                    results += ArchiveItem(
                        displayName = entry.name.substringAfterLast('/'),
                        entryName = entry.name,
                        isDirectory = false
                    )
                }
            }
        }
        return results
    }
}
