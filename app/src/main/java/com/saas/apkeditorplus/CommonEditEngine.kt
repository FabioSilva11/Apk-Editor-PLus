package com.saas.apkeditorplus

import android.content.Context
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.security.MessageDigest

object CommonEditEngine {

    private const val ID_INSTALL_LOCATION = 0x010102b7

    data class Snapshot(
        val packageName: String,
        val versionCode: Int,
        val versionName: String,
        val appLabel: String?,
        val minSdk: Int?,
        val targetSdk: Int?,
        val maxSdk: Int?,
        val installLocation: Int?
    )

    data class Changes(
        val packageName: String,
        val versionCode: Int,
        val versionName: String,
        val appLabel: String,
        val minSdk: Int?,
        val targetSdk: Int?,
        val maxSdk: Int?,
        val installLocation: Int?,
        val renameResourcePackage: Boolean
    )

    data class Output(
        val manifestFile: File,
        val resourcesFile: File?
    )

    fun findLauncherIconEntries(apkFile: File): List<String> {
        ApkModule.loadApkFile(apkFile).use { module ->
            val manifest = module.androidManifestBlock ?: return emptyList()
            val iconIds = setOf(manifest.iconResourceId, manifest.roundIconResourceId)
                .filter { it != 0 }
                .toSet()
            if (iconIds.isEmpty()) return emptyList()
            return module.listResFiles()
                .filter { resFile ->
                    resFile.entryList.any { entry -> entry.resourceId in iconIds }
                }
                .map { it.filePath }
                .filter { path ->
                    path.endsWith(".png", ignoreCase = true) ||
                        path.endsWith(".webp", ignoreCase = true) ||
                        path.endsWith(".jpg", ignoreCase = true) ||
                        path.endsWith(".jpeg", ignoreCase = true)
                }
                .distinct()
                .sorted()
        }
    }

    fun read(apkFile: File): Snapshot {
        require(apkFile.isFile) { "APK file not found" }
        ApkModule.loadApkFile(apkFile).use { module ->
            val manifest = module.androidManifestBlock
                ?: error("AndroidManifest.xml could not be parsed")
            val usesSdk = manifest.getElement(AndroidManifest.TAG_uses_sdk)
            return Snapshot(
                packageName = manifest.packageName.orEmpty(),
                versionCode = manifest.versionCode ?: 1,
                versionName = manifest.versionName.orEmpty(),
                appLabel = manifest.applicationLabelString,
                minSdk = manifest.minSdkVersion,
                targetSdk = manifest.targetSdkVersion,
                maxSdk = readAndroidInt(usesSdk, AndroidManifest.NAME_maxSdkVersion),
                installLocation = readAndroidInt(
                    manifest.manifestElement,
                    AndroidManifest.NAME_installLocation
                )
            )
        }
    }

    fun apply(context: Context, apkFile: File, changes: Changes): Output {
        validateChanges(changes)
        val outputDir = File(context.cacheDir, "common_edit/${workspaceKey(apkFile)}")
            .apply { mkdirs() }
        val manifestOutput = File(outputDir, AndroidManifest.FILE_NAME)
        val resourcesOutput = File(outputDir, "resources.arsc")

        ApkModule.loadApkFile(apkFile).use { module ->
            val manifest = module.androidManifestBlock
                ?: error("AndroidManifest.xml could not be parsed")

            val oldPackage = manifest.packageName.orEmpty()
            if (changes.renameResourcePackage && oldPackage != changes.packageName) {
                module.setPackageName(changes.packageName)
            } else {
                manifest.setPackageName(changes.packageName)
            }
            manifest.setVersionCode(changes.versionCode)
            manifest.setVersionName(changes.versionName)
            applySdkVersions(manifest, changes)
            setAndroidInt(
                manifest.manifestElement,
                AndroidManifest.NAME_installLocation,
                ID_INSTALL_LOCATION,
                changes.installLocation
            )

            var resourcesChanged = changes.renameResourcePackage && oldPackage != changes.packageName
            val labelReference = manifest.applicationLabelReference
            if (labelReference != null && module.hasTableBlock()) {
                val entries = module.tableBlock.getEntries(labelReference)
                while (entries.hasNext()) {
                    val entry = entries.next()
                    if (entry.isScalar) {
                        entry.setValueAsString(changes.appLabel)
                        resourcesChanged = true
                    }
                }
            } else {
                manifest.setApplicationLabel(changes.appLabel)
            }

            manifest.refreshFull()
            manifest.writeBytes(manifestOutput)
            require(AndroidManifestBlock.load(manifestOutput).packageName == changes.packageName) {
                "Generated Manifest failed validation"
            }

            if (resourcesChanged) {
                module.tableBlock.refreshFull()
                module.tableBlock.writeBytes(resourcesOutput)
            } else if (resourcesOutput.exists()) {
                resourcesOutput.delete()
            }
        }

        return Output(
            manifestFile = manifestOutput,
            resourcesFile = resourcesOutput.takeIf(File::isFile)
        )
    }

    private fun applySdkVersions(manifest: AndroidManifestBlock, changes: Changes) {
        changes.minSdk?.let(manifest::setMinSdkVersion)
        changes.targetSdk?.let(manifest::setTargetSdkVersion)
        val usesSdk = manifest.getElement(AndroidManifest.TAG_uses_sdk)
            ?: if (changes.maxSdk != null) manifest.getOrCreateElement(AndroidManifest.TAG_uses_sdk) else null
        if (changes.minSdk == null) {
            usesSdk?.removeAttributesWithName(AndroidManifest.NAME_minSdkVersion)
        }
        if (changes.targetSdk == null) {
            usesSdk?.removeAttributesWithName(AndroidManifest.NAME_targetSdkVersion)
        }
        setAndroidInt(
            usesSdk,
            AndroidManifest.NAME_maxSdkVersion,
            AndroidManifest.ID_maxSdkVersion,
            changes.maxSdk
        )
    }

    private fun readAndroidInt(element: ResXmlElement?, name: String): Int? {
        val attribute = element?.searchAttributeByName(name) ?: return null
        return attribute.data
    }

    private fun setAndroidInt(
        element: ResXmlElement?,
        name: String,
        resourceId: Int,
        value: Int?
    ) {
        if (element == null) return
        if (value == null) {
            element.removeAttributesWithName(name)
            return
        }
        element.getOrCreateAndroidAttribute(name, resourceId).apply {
            valueType = ValueType.DEC
            data = value
        }
    }

    internal fun validateChanges(changes: Changes) {
        require(changes.packageName.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$"))) {
            "Invalid package name"
        }
        require(changes.packageName.length < 128) { "Package name is too long" }
        require(changes.versionCode > 0) { "Invalid version code" }
        require(changes.appLabel.isNotBlank()) { "Application label is empty" }
        require(changes.minSdk == null || changes.minSdk > 0) { "Invalid minSdkVersion" }
        require(changes.targetSdk == null || changes.targetSdk > 0) { "Invalid targetSdkVersion" }
        require(changes.maxSdk == null || changes.maxSdk > 0) { "Invalid maxSdkVersion" }
        if (changes.minSdk != null && changes.targetSdk != null) {
            require(changes.targetSdk >= changes.minSdk) {
                "targetSdkVersion must be greater than or equal to minSdkVersion"
            }
        }
        if (changes.minSdk != null && changes.maxSdk != null) {
            require(changes.maxSdk >= changes.minSdk) {
                "maxSdkVersion must be greater than or equal to minSdkVersion"
            }
        }
        require(changes.installLocation == null || changes.installLocation in 0..2) {
            "Invalid install location"
        }
    }

    private fun workspaceKey(apkFile: File): String {
        val identity = "${apkFile.absolutePath}|${apkFile.length()}|${apkFile.lastModified()}"
        return MessageDigest.getInstance("SHA-1")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
