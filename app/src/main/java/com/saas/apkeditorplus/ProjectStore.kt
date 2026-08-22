package com.saas.apkeditorplus

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

class ProjectStore(private val context: Context) {

    data class Project(
        val id: String,
        val apkPath: String,
        val displayName: String,
        val modifiedFiles: Map<String, String>,
        val deletedEntries: Set<String>,
        val updatedAt: Long
    )

    private data class StoredProject(
        val id: String,
        val apkPath: String,
        val displayName: String,
        val modifiedEntries: Map<String, String>,
        val deletedEntries: Set<String>,
        val updatedAt: Long
    )

    private val gson = Gson()
    private val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }

    fun save(
        apkPath: String,
        modifiedFiles: Map<String, String>,
        deletedEntries: Set<String>
    ): Project {
        require(apkPath.isNotBlank()) { "Caminho do APK ausente" }
        val id = projectId(apkPath)
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

        val stored = StoredProject(
            id = id,
            apkPath = apkPath,
            displayName = File(apkPath).nameWithoutExtension.ifBlank { File(apkPath).name },
            modifiedEntries = storedChanges,
            deletedEntries = deletedEntries,
            updatedAt = System.currentTimeMillis()
        )
        File(directory, "project.json").writeText(gson.toJson(stored))
        return toProject(directory, stored)
    }

    fun list(): List<Project> = projectsDir.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory ->
            runCatching {
                val stored = gson.fromJson(
                    File(directory, "project.json").readText(),
                    StoredProject::class.java
                )
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

    private fun toProject(directory: File, stored: StoredProject): Project {
        val changesDirectory = File(directory, "changes")
        return Project(
            id = stored.id,
            apkPath = stored.apkPath,
            displayName = stored.displayName,
            modifiedFiles = stored.modifiedEntries.mapValues { File(changesDirectory, it.value).absolutePath },
            deletedEntries = stored.deletedEntries,
            updatedAt = stored.updatedAt
        )
    }

    private fun projectId(apkPath: String): String = digest(File(apkPath).absolutePath.lowercase())

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(24)
}
