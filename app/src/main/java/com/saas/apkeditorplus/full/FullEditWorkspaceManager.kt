package com.saas.apkeditorplus.full

import android.content.Context
import android.util.Xml
import com.saas.apkeditorplus.AppSettings
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import com.reandroid.apk.ApkUtil
import com.reandroid.apk.DexDecoder
import com.reandroid.apk.DexFileInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.use
import org.xmlpull.v1.XmlPullParser
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal object FullEditWorkspaceManager {
    private const val WORKSPACE_DIR = "full_edit_workspace"
    private const val WORKSPACE_MARKER = ".decoded"
    private const val STRINGS_FILE = "strings.xml"
    private const val LEGACY_OVERRIDE_STRINGS_FILE = "zz_strings_full_edit.xml"
    private const val PENDING_TRANSLATIONS_DIR = ".pending_translations"
    private val hiddenStringPrefixes = listOf(
        "abc_",
        "androidx_",
        "bottomsheet_",
        "character_counter_",
        "clear_text_end_icon_",
        "common_google_play_services_",
        "error_icon_content_description",
        "exposed_dropdown_menu_",
        "fallback_menu_item_",
        "m3_",
        "material_",
        "material_clock_",
        "material_timepicker_",
        "mtrl_",
        "nav_app_bar_",
        "password_toggle_",
        "path_password_",
        "search_menu_",
        "searchbar_",
        "searchview_",
        "side_sheet_",
        "status_bar_",
        "v7_preference_"
    )
    private val hiddenStringPatterns = listOf(
        Regex("^APKTOOL_.*$"),
        Regex("^default_res_0x[0-9a-fA-F]+$"),
        Regex("^string_[0-9a-fA-F]+$"),
        Regex("^mr_.*$")
    )
    private val formatSpecifierRegex = Regex(
        """%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT])?[a-zA-Z%]"""
    )

    data class WorkspaceInfo(
        val rootDir: File,
        val manifestFile: File,
        val resDir: File,
        val key: String
    )

    private data class StringValue(
        val name: String,
        val value: String
    )

    private object NoOpDexDecoder : DexDecoder {
        override fun decodeDex(dexFileInputSource: DexFileInputSource, mainDirectory: File) = Unit
    }

    fun getWorkspace(context: Context, apkPath: String): WorkspaceInfo {
        val apkFile = File(apkPath)
        require(apkFile.isFile) { "APK file not found" }
        val key = workspaceKey(apkFile)
        val rootDir = File(AppSettings.workspaceRoot(context, WORKSPACE_DIR), key)
        val marker = File(rootDir, WORKSPACE_MARKER)
        if (!marker.exists()) {
            decodeWorkspace(apkFile, rootDir)
            marker.parentFile?.mkdirs()
            marker.writeText("decoded")
        }
        return locateWorkspace(rootDir, key)
    }

    fun getManifestFile(context: Context, apkPath: String): File {
        return getWorkspace(context, apkPath).manifestFile
    }

    fun compileManifest(context: Context, apkPath: String): File {
        val workspace = getWorkspace(context, apkPath)
        ApkModule.loadApkFile(File(apkPath)).use { apkModule ->
            val encoder = ApkModuleXmlEncoder(apkModule, apkModule.tableBlock)
            encoder.buildResources(workspace.rootDir)
            val manifestSource = apkModule.getInputSource(AndroidManifestBlock.FILE_NAME)
                ?: error("Failed to rebuild AndroidManifest.xml")
            val outputDir = AppSettings.workspaceRoot(context, "full_edit_workspace_manifest")
            val outputFile = File(outputDir, "${workspace.key}_AndroidManifest.xml")
            manifestSource.write(outputFile)
            require(AndroidManifestBlock.load(outputFile).packageName.isNotBlank()) {
                "Generated AndroidManifest.xml is invalid"
            }
            return outputFile
        }
    }

    fun listStringLocales(context: Context, apkPath: String): List<String> {
        val resDir = getWorkspace(context, apkPath).resDir
        return ApkUtil.listValuesDirectory(resDir)
            .mapNotNull { valuesDir ->
                if (containsDisplayableStringResource(valuesDir)) {
                    qualifierFromValuesDirectory(valuesDir.name)
                } else {
                    null
                }
            }
            .distinct()
            .sortedWith(
                compareBy<String>({ if (it.isEmpty()) 0 else 1 }, { it.lowercase(Locale.ROOT) })
            )
    }

    fun readStringResources(
        context: Context,
        apkPath: String,
        localeQualifier: String
    ): List<FullEditRepository.StringResourceItem> {
        val valuesDir = valuesDirectoryForQualifier(getWorkspace(context, apkPath).resDir, localeQualifier)
        val workspace = getWorkspace(context, apkPath)
        val pendingTranslations = readPendingTranslations(workspace, localeQualifier)
        val merged = linkedMapOf<String, String>()
        stringXmlFiles(valuesDir).forEach { file ->
            readStringValues(file).forEach { value ->
                merged[value.name] = value.value
            }
        }
        return merged.entries
            .filter { (name, _) -> isDisplayableString(name) }
            .map { (name, value) ->
                FullEditRepository.StringResourceItem(
                    name = name,
                    value = value,
                    localeQualifier = localeQualifier,
                    needsTranslation = name in pendingTranslations
                )
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun exportStringEditorFile(
        context: Context,
        apkPath: String,
        localeQualifier: String
    ): File {
        val values = readStringResources(context, apkPath, localeQualifier)
            .associate { it.name to it.value.orEmpty() }
        val outputDir = AppSettings.workspaceRoot(context, "full_edit_strings_workspace")
        val workspace = getWorkspace(context, apkPath)
        val outputFile = File(outputDir, "${workspace.key}_${safeLocaleSuffix(localeQualifier)}_strings.xml")
        writeStringsXml(outputFile, values)
        return outputFile
    }

    fun saveSingleStringOverride(
        context: Context,
        apkPath: String,
        localeQualifier: String,
        name: String,
        newValue: String
    ): File {
        val workspace = getWorkspace(context, apkPath)
        val valuesDir = valuesDirectoryForQualifier(workspace.resDir, localeQualifier)
        val previousValue = readStringResources(context, apkPath, localeQualifier)
            .firstOrNull { it.name == name }
            ?.value
        require(updateStringValue(valuesDir, name, newValue)) { "String not found: $name" }
        if (previousValue != newValue) markTranslationsCompleted(workspace, localeQualifier, setOf(name))
        return compileResources(context, apkPath, workspace)
    }

    fun applyEditedStringsFile(
        context: Context,
        apkPath: String,
        localeQualifier: String,
        editedStringsXml: File
    ): File {
        require(editedStringsXml.isFile) { "Edited strings file not found" }
        val parsedValues = readStringValues(editedStringsXml).associate { it.name to it.value }
        require(parsedValues.isNotEmpty()) { "No string resources found in edited file" }
        val workspace = getWorkspace(context, apkPath)
        val valuesDir = valuesDirectoryForQualifier(workspace.resDir, localeQualifier)
        val currentValues = readStringResources(context, apkPath, localeQualifier)
            .associate { it.name to it.value.orEmpty() }
        var changed = 0
        val changedNames = linkedSetOf<String>()
        parsedValues.forEach { (name, value) ->
            if (currentValues[name] != value && updateStringValue(valuesDir, name, value)) {
                changed++
                changedNames += name
            }
        }
        require(changed > 0) { "No changed strings found" }
        markTranslationsCompleted(workspace, localeQualifier, changedNames)
        return compileResources(context, apkPath, workspace)
    }

    fun createLocaleFromDefault(
        context: Context,
        apkPath: String,
        localeQualifier: String
    ): File {
        return addLanguageLikeOriginal(context, apkPath, localeQualifier)
    }

    fun addLanguageLikeOriginal(
        context: Context,
        apkPath: String,
        localeQualifier: String
    ): File {
        require(localeQualifier.length >= 3) { "Invalid locale qualifier" }

        val defaultValues = readStringResources(context, apkPath, "")
        if (defaultValues.isEmpty()) {
            error("Wait for decoding")
        }

        val currentValues = readStringResources(context, apkPath, localeQualifier)
        val existingNames = currentValues.mapTo(linkedSetOf()) { it.name }
        val mergedValues = linkedMapOf<String, String>()

        currentValues.forEach { item ->
            mergedValues[item.name] = item.value.orEmpty()
        }

        var addedCount = 0
        val addedNames = linkedSetOf<String>()
        defaultValues.forEach { item ->
            if (existingNames.add(item.name)) {
                mergedValues[item.name] = item.value.orEmpty()
                addedCount += 1
                addedNames += item.name
            }
        }

        if (addedCount == 0) {
            error("Locale already exists")
        }

        val workspace = getWorkspace(context, apkPath)
        val valuesDir = valuesDirectoryForQualifier(workspace.resDir, localeQualifier).apply { mkdirs() }
        writeStringsXml(File(valuesDir, STRINGS_FILE), mergedValues)
        addPendingTranslations(workspace, localeQualifier, addedNames)
        return compileResources(context, apkPath, workspace)
    }

    private fun compileResources(
        context: Context,
        apkPath: String,
        workspace: WorkspaceInfo
    ): File {
        ApkModule.loadApkFile(File(apkPath)).use { apkModule ->
            val encoder = ApkModuleXmlEncoder(apkModule, apkModule.tableBlock)
            encoder.scanDirectory(workspace.rootDir)
            val outputDir = AppSettings.workspaceRoot(context, "full_edit_workspace_resources")
            val outputFile = File(outputDir, "${workspace.key}_resources.arsc")
            val inputSource = apkModule.getInputSource(TableBlock.FILE_NAME)
                ?: error("Failed to rebuild resources.arsc")
            inputSource.write(outputFile)
            return outputFile
        }
    }

    private fun decodeWorkspace(apkFile: File, rootDir: File) {
        if (rootDir.exists()) {
            rootDir.deleteRecursively()
        }
        rootDir.mkdirs()
        ApkModule.loadApkFile(apkFile).use { apkModule ->
            val decoder = ApkModuleXmlDecoder(apkModule)
            decoder.setDexDecoder(NoOpDexDecoder)
            decoder.decode(rootDir)
        }
    }

    private fun locateWorkspace(rootDir: File, key: String): WorkspaceInfo {
        val manifestFile = File(rootDir, "AndroidManifest.xml")
        require(manifestFile.isFile) { "Manifest not decoded" }
        val resourcesRoot = File(rootDir, TableBlock.DIRECTORY_NAME)
        val packageDir = ApkUtil.listPackageDirectories(resourcesRoot).firstOrNull()
            ?: error("Decoded resources not found")
        val resDir = File(packageDir, PackageBlock.RES_DIRECTORY_NAME)
        require(resDir.isDirectory) { "Decoded res directory not found" }
        return WorkspaceInfo(
            rootDir = rootDir,
            manifestFile = manifestFile,
            resDir = resDir,
            key = key
        )
    }

    private fun valuesDirectoryForQualifier(resDir: File, qualifier: String): File {
        val directoryName = if (qualifier.isBlank()) {
            PackageBlock.VALUES_DIRECTORY_NAME
        } else {
            PackageBlock.VALUES_DIRECTORY_NAME + qualifier
        }
        return File(resDir, directoryName)
    }

    private fun qualifierFromValuesDirectory(directoryName: String): String {
        if (directoryName == PackageBlock.VALUES_DIRECTORY_NAME) {
            return ""
        }
        return directoryName.removePrefix(PackageBlock.VALUES_DIRECTORY_NAME)
            .takeIf { it.startsWith('-') }
            ?: ""
    }

    private fun stringXmlFiles(valuesDir: File): List<File> {
        if (!valuesDir.isDirectory) {
            return emptyList()
        }
        return valuesDir.listFiles()
            ?.filter { file -> file.isFile && file.extension.equals("xml", ignoreCase = true) }
            ?.sortedWith(
                compareBy<File>(
                    { if (it.name.equals(STRINGS_FILE, ignoreCase = true)) 0 else 1 },
                    { it.name.lowercase(Locale.ROOT) }
                )
            )
            .orEmpty()
    }

    private fun containsDisplayableStringResource(valuesDir: File): Boolean {
        return stringXmlFiles(valuesDir).any { file ->
            readStringValues(file).any { value -> isDisplayableString(value.name) }
        }
    }

    private fun pendingTranslationsFile(workspace: WorkspaceInfo, localeQualifier: String): File =
        File(
            File(File(workspace.rootDir.parentFile, PENDING_TRANSLATIONS_DIR), workspace.key),
            "${safeLocaleSuffix(localeQualifier)}.txt"
        )

    private fun readPendingTranslations(workspace: WorkspaceInfo, localeQualifier: String): Set<String> =
        pendingTranslationsFile(workspace, localeQualifier)
            .takeIf(File::isFile)
            ?.readLines(Charsets.UTF_8)
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()

    private fun addPendingTranslations(workspace: WorkspaceInfo, localeQualifier: String, names: Collection<String>) {
        if (names.isEmpty()) return
        writePendingTranslations(workspace, localeQualifier, readPendingTranslations(workspace, localeQualifier) + names)
    }

    private fun markTranslationsCompleted(workspace: WorkspaceInfo, localeQualifier: String, names: Set<String>) {
        if (names.isEmpty()) return
        writePendingTranslations(workspace, localeQualifier, readPendingTranslations(workspace, localeQualifier) - names)
    }

    private fun writePendingTranslations(workspace: WorkspaceInfo, localeQualifier: String, names: Set<String>) {
        val marker = pendingTranslationsFile(workspace, localeQualifier)
        if (names.isEmpty()) {
            marker.delete()
            return
        }
        marker.parentFile?.mkdirs()
        marker.writeText(names.sorted().joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun readStringValues(file: File): List<StringValue> {
        if (!file.isFile) {
            return emptyList()
        }
        return runCatching {
            val document = newDocumentBuilderFactory().newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            buildList {
                for (index in 0 until nodes.length) {
                    val element = nodes.item(index) as? org.w3c.dom.Element ?: continue
                    val name = element.getAttribute("name")
                    if (name.isNotBlank()) add(StringValue(name, element.textContent.orEmpty()))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun updateStringValue(valuesDir: File, name: String, value: String): Boolean {
        for (file in stringXmlFiles(valuesDir)) {
            val document = runCatching { newDocumentBuilderFactory().newDocumentBuilder().parse(file) }
                .getOrNull() ?: continue
            val nodes = document.getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? org.w3c.dom.Element ?: continue
                if (element.getAttribute("name") != name) continue
                while (element.hasChildNodes()) element.removeChild(element.firstChild)
                element.appendChild(document.createTextNode(value))
                val transformer = TransformerFactory.newInstance().newTransformer().apply {
                    setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                    setOutputProperty(OutputKeys.INDENT, "yes")
                }
                transformer.transform(DOMSource(document), StreamResult(file))
                return true
            }
        }
        return false
    }

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
    }

    private fun writeStringsXml(file: File, values: Map<String, String>) {
        file.parentFile?.mkdirs()
        file.outputStream().use { outputStream ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(outputStream, Charsets.UTF_8.name())
            serializer.startDocument(Charsets.UTF_8.name(), true)
            serializer.text("\n")
            serializer.startTag(null, "resources")
            values.forEach { (name, value) ->
                serializer.text("\n    ")
                serializer.startTag(null, "string")
                serializer.attribute(null, "name", name)
                if (requiresFormattedFalse(value)) {
                    serializer.attribute(null, "formatted", "false")
                }
                serializer.text(value)
                serializer.endTag(null, "string")
            }
            serializer.text("\n")
            serializer.endTag(null, "resources")
            serializer.text("\n")
            serializer.endDocument()
        }
    }

    private fun isDisplayableString(name: String): Boolean {
        return hiddenStringPrefixes.none { prefix -> name.startsWith(prefix) } &&
            hiddenStringPatterns.none { pattern -> pattern.matches(name) }
    }

    private fun requiresFormattedFalse(value: String): Boolean {
        val nonPositionalFormats = formatSpecifierRegex.findAll(value)
            .map { it.value }
            .filter { token -> token != "%%" && !token.contains('$') }
            .count()
        return nonPositionalFormats > 1
    }

    private fun safeLocaleSuffix(localeQualifier: String): String {
        return if (localeQualifier.isBlank()) {
            "default"
        } else {
            localeQualifier.removePrefix("-").replace("-r", "_").replace('-', '_')
        }
    }

    private fun workspaceKey(apkFile: File): String {
        val identity = "${apkFile.absolutePath}|${apkFile.length()}|${apkFile.lastModified()}"
        val digest = MessageDigest.getInstance("SHA-1").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
