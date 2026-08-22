package com.saas.apkeditorplus

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

class ProjectStore(private val context: Context) {

    enum class SourceStatus { VALID, MISSING, CHANGED, UNVERIFIED }

    data class Project(
        val id: String,
        val apkPath: String,
        val displayName: String,
        val modifiedFiles: Map<String, String>,
        val deletedEntries: Set<String>,
        val updatedAt: Long,
        val sourceStatus: SourceStatus
    )

    private data class StoredProject(
        val id: String,
        val apkPath: String,
        val displayName: String,
        val modifiedEntries: Map<String, String>,
        val deletedEntries: Set<String>,
        val updatedAt: Long,
        val sourceSha256: String? = null,
        val sourceSize: Long? = null,
        val sourceLastModified: Long? = null
    )

    private val gson = Gson()
    private val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }

    fun save(
        apkPath: String,
        modifiedFiles: Map<String, String>,
        deletedEntries: Set<String>,
        existingProjectId: String? = null
    ): Project {
        require(apkPath.isNotBlank()) { "Caminho do APK ausente" }
        val sourceApk = File(apkPath)
        require(sourceApk.isFile) { "APK original não encontrado" }
        val id = existingProjectId ?: projectId(apkPath)
        val directory = File(projectsDir, id).apply { mkdirs() }
        val changesDirectory = File(directory, "changes").apply { mkdirs() }
        val storedChanges = linkedMapOf<String, String>()

        modifiedFiles.forEach { (entryName, sourcePath) ->
            val source = File(sourcePath)
            if (source.isFile) {
                val extension = source.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
                val target = File(changesDirectory, "${digest(entryName)}$extension")
                source.copyTo(target, overwrite = true)
                storedChanges[entryName] = target.name
            } else if (source.isDirectory) {
                val target = File(changesDirectory, digest(entryName))
                if (target.exists()) target.deleteRecursively()
                source.copyRecursively(target, overwrite = true)
                storedChanges[entryName] = target.name
            }
        }

        val previous = readStored(directory)
        val canReuseFingerprint = previous?.sourceSha256 != null &&
            previous.apkPath == sourceApk.absolutePath &&
            previous.sourceSize == sourceApk.length() &&
            previous.sourceLastModified == sourceApk.lastModified()
        val stored = StoredProject(
            id = id,
            apkPath = sourceApk.absolutePath,
            displayName = previous?.displayName
                ?: sourceApk.nameWithoutExtension.ifBlank { sourceApk.name },
            modifiedEntries = storedChanges,
            deletedEntries = deletedEntries,
            updatedAt = System.currentTimeMillis(),
            sourceSha256 = if (canReuseFingerprint) previous.sourceSha256 else sha256(sourceApk),
            sourceSize = sourceApk.length(),
            sourceLastModified = sourceApk.lastModified()
        )
        writeStored(directory, stored)
        return toProject(directory, stored)
    }

    fun list(): List<Project> = projectsDir.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory ->
            runCatching {
                val stored = readStored(directory) ?: error("Projeto inválido")
                toProject(directory, stored)
            }.getOrNull()
        }
        .sortedByDescending(Project::updatedAt)
        .toList()

    fun delete(id: String): Boolean {
        val directory = File(projectsDir, id)
        return directory.canonicalFile.path.startsWith(projectsDir.canonicalFile.path + File.separator) &&
            (!directory.exists() || directory.deleteRecursively())
    }

    fun deleteForApk(apkPath: String): Boolean = delete(projectId(apkPath))

    fun verifySource(project: Project): SourceStatus {
        val directory = File(projectsDir, project.id)
        val stored = readStored(directory) ?: return SourceStatus.UNVERIFIED
        val source = File(stored.apkPath)
        if (!source.isFile) return SourceStatus.MISSING
        val expected = stored.sourceSha256 ?: return SourceStatus.UNVERIFIED
        if (stored.sourceSize != source.length()) return SourceStatus.CHANGED
        return if (sha256(source) == expected) SourceStatus.VALID else SourceStatus.CHANGED
    }

    /** Establishes a fingerprint for projects created by older app versions. */
    fun adoptCurrentSource(id: String): Project {
        val directory = projectDirectory(id)
        val stored = readStored(directory) ?: error("Projeto não encontrado")
        val source = File(stored.apkPath)
        require(source.isFile) { "APK original não encontrado" }
        val updated = stored.copy(
            sourceSha256 = sha256(source),
            sourceSize = source.length(),
            sourceLastModified = source.lastModified()
        )
        writeStored(directory, updated)
        return toProject(directory, updated)
    }

    /**
     * Reconnects a project only when the selected APK has the same fingerprint.
     * Legacy projects without a fingerprint adopt the selected source explicitly.
     */
    fun relinkSource(id: String, apkPath: String): Project {
        val directory = projectDirectory(id)
        val stored = readStored(directory) ?: error("Projeto não encontrado")
        val source = File(apkPath)
        require(source.isFile) { "APK selecionado não encontrado" }
        val selectedHash = sha256(source)
        require(stored.sourceSha256 == null || stored.sourceSha256 == selectedHash) {
            "O APK selecionado não corresponde ao original deste projeto"
        }
        val updated = stored.copy(
            apkPath = source.absolutePath,
            sourceSha256 = stored.sourceSha256 ?: selectedHash,
            sourceSize = source.length(),
            sourceLastModified = source.lastModified()
        )
        writeStored(directory, updated)
        return toProject(directory, updated)
    }

    fun validateSourceCandidate(id: String, apkPath: String) {
        val stored = readStored(projectDirectory(id)) ?: error("Projeto não encontrado")
        val source = File(apkPath)
        require(source.isFile) { "APK selecionado não encontrado" }
        require(stored.sourceSha256 == null || stored.sourceSha256 == sha256(source)) {
            "O APK selecionado não corresponde ao original deste projeto"
        }
    }

    private fun toProject(directory: File, stored: StoredProject): Project {
        val changesDirectory = File(directory, "changes")
        return Project(
            id = stored.id,
            apkPath = stored.apkPath,
            displayName = stored.displayName,
            modifiedFiles = stored.modifiedEntries.mapValues { File(changesDirectory, it.value).absolutePath },
            deletedEntries = stored.deletedEntries,
            updatedAt = stored.updatedAt,
            sourceStatus = quickSourceStatus(stored)
        )
    }

    private fun quickSourceStatus(stored: StoredProject): SourceStatus {
        val source = File(stored.apkPath)
        if (!source.isFile) return SourceStatus.MISSING
        if (stored.sourceSha256 == null || stored.sourceSize == null) return SourceStatus.UNVERIFIED
        return if (stored.sourceSize != source.length() ||
            stored.sourceLastModified != null && stored.sourceLastModified != source.lastModified()
        ) SourceStatus.CHANGED else SourceStatus.VALID
    }

    private fun projectDirectory(id: String): File {
        val directory = File(projectsDir, id).canonicalFile
        require(directory.path.startsWith(projectsDir.canonicalFile.path + File.separator)) {
            "Identificador de projeto inválido"
        }
        return directory
    }

    private fun readStored(directory: File): StoredProject? = runCatching {
        gson.fromJson(File(directory, "project.json").readText(), StoredProject::class.java)
    }.getOrNull()

    private fun writeStored(directory: File, stored: StoredProject) {
        val target = File(directory, "project.json")
        val temporary = File(directory, "project.json.tmp")
        temporary.writeText(gson.toJson(stored))
        if (target.exists() && !target.delete()) error("Falha ao atualizar o projeto")
        if (!temporary.renameTo(target)) error("Falha ao concluir a atualização do projeto")
    }

    private fun projectId(apkPath: String): String = digest(File(apkPath).absolutePath.lowercase())

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(24)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
