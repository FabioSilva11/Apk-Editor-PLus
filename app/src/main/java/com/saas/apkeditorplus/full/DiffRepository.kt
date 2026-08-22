package com.saas.apkeditorplus.full

import com.saas.apkeditorplus.FullEditActivity
import com.saas.apkeditorplus.utils.AxmlDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

enum class DiffChangeKind { ADDED, MODIFIED, DELETED }
enum class DiffLineKind { CONTEXT, ADDED, REMOVED, SEPARATOR }

data class DiffLine(
    val oldNumber: Int?,
    val newNumber: Int?,
    val text: String,
    val kind: DiffLineKind
)

data class FileDiff(
    val entryName: String,
    val kind: DiffChangeKind,
    val summary: String,
    val lines: List<DiffLine>
)

object DiffRepository {
    private const val MAX_TEXT_BYTES = 512 * 1024
    private const val MAX_SOURCE_LINES = 2_000
    private const val MAX_LCS_CELLS = 1_000_000L
    private const val CONTEXT_LINES = 3
    private const val MAX_VISIBLE_LINES = 700

    private val textExtensions = setOf(
        "css", "gradle", "html", "java", "js", "json", "kt", "md", "properties",
        "smali", "svg", "txt", "xml", "yml", "yaml"
    )

    fun build(apkPath: String, changes: List<FullEditActivity.PendingChange>): List<FileDiff> {
        if (changes.isEmpty()) return emptyList()
        return ZipFile(apkPath).use { zip ->
            changes.map { change -> buildFileDiff(zip, change) }
        }
    }

    private fun buildFileDiff(zip: ZipFile, change: FullEditActivity.PendingChange): FileDiff {
        val entry = zip.getEntry(change.entryName)
        val modified = change.modifiedFile
        val kind = when {
            change.deleted -> DiffChangeKind.DELETED
            entry == null -> DiffChangeKind.ADDED
            else -> DiffChangeKind.MODIFIED
        }

        if (change.entryName.endsWith('/')) {
            val originalCount = zip.entries().asSequence().count { it.name.startsWith(change.entryName) && !it.isDirectory }
            return FileDiff(change.entryName, kind, "$originalCount arquivo(s) na pasta", emptyList())
        }
        if (modified?.isDirectory == true) {
            val files = modified.walkTopDown().count(File::isFile)
            return FileDiff(change.entryName, kind, "$files arquivo(s) no espaço de trabalho recompilável", emptyList())
        }

        val oldSize = entry?.size?.coerceAtLeast(0L) ?: 0L
        val newSize = if (change.deleted) 0L else modified?.length()?.coerceAtLeast(0L) ?: 0L
        val originalBytes = entry?.takeIf { !it.isDirectory && it.size <= MAX_TEXT_BYTES }
            ?.let { zip.getInputStream(it).use(::readLimited) }
        val modifiedBytes = if (!change.deleted && modified?.isFile == true && modified.length() <= MAX_TEXT_BYTES) {
            modified.inputStream().use(::readLimited)
        } else null

        val oldText = originalBytes?.let { decodeText(change.entryName, it) }
        val newText = modifiedBytes?.let { decodeText(change.entryName, it) }
        val lines = when {
            kind == DiffChangeKind.ADDED && newText != null -> calculateTextDiff("", newText)
            kind == DiffChangeKind.DELETED && oldText != null -> calculateTextDiff(oldText, "")
            oldText != null && newText != null -> calculateTextDiff(oldText, newText)
            else -> emptyList()
        }
        val summary = if (lines.isNotEmpty()) {
            val added = lines.count { it.kind == DiffLineKind.ADDED }
            val removed = lines.count { it.kind == DiffLineKind.REMOVED }
            "+$added  −$removed"
        } else {
            "Conteúdo binário • ${formatSize(oldSize)} → ${formatSize(newSize)}"
        }
        return FileDiff(change.entryName, kind, summary, lines)
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total <= MAX_TEXT_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_TEXT_BYTES + 1 - total))
            if (count < 0) break
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray().takeIf { it.size <= MAX_TEXT_BYTES } ?: byteArrayOf()
    }

    private fun decodeText(entryName: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        if (entryName.endsWith(".xml", true) && isBinaryXml(bytes)) {
            val output = ByteArrayOutputStream()
            if (AxmlDecoder().decode(ByteArrayInputStream(bytes), output)) {
                return output.toString(Charsets.UTF_8.name())
            }
        }
        val extension = entryName.substringAfterLast('.', "").lowercase()
        if (extension !in textExtensions || bytes.any { it == 0.toByte() }) return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun isBinaryXml(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val magic = (bytes[0].toInt() and 0xff) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 24)
        return magic == 0x00080003
    }

    fun calculateTextDiff(oldText: String, newText: String): List<DiffLine> {
        val oldLines = splitLines(oldText).take(MAX_SOURCE_LINES)
        val newLines = splitLines(newText).take(MAX_SOURCE_LINES)
        val raw = if (oldLines.size.toLong() * newLines.size <= MAX_LCS_CELLS) {
            calculateLcsDiff(oldLines, newLines)
        } else {
            calculateLargeDiff(oldLines, newLines)
        }
        if (raw.none { it.kind == DiffLineKind.ADDED || it.kind == DiffLineKind.REMOVED }) return emptyList()
        return compact(raw)
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        return if (lines.lastOrNull().isNullOrEmpty() && text.endsWith('\n')) lines.dropLast(1) else lines
    }

    private fun calculateLcsDiff(old: List<String>, new: List<String>): List<DiffLine> {
        val table = Array(old.size + 1) { IntArray(new.size + 1) }
        for (i in old.indices.reversed()) {
            for (j in new.indices.reversed()) {
                table[i][j] = if (old[i] == new[j]) table[i + 1][j + 1] + 1
                else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < old.size || j < new.size) {
            when {
                i < old.size && j < new.size && old[i] == new[j] -> {
                    result += DiffLine(i + 1, j + 1, old[i], DiffLineKind.CONTEXT); i++; j++
                }
                i < old.size && (j >= new.size || table[i + 1][j] >= table[i][j + 1]) -> {
                    result += DiffLine(i + 1, null, old[i], DiffLineKind.REMOVED); i++
                }
                else -> {
                    result += DiffLine(null, j + 1, new[j], DiffLineKind.ADDED); j++
                }
            }
        }
        return result
    }

    private fun calculateLargeDiff(old: List<String>, new: List<String>): List<DiffLine> {
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix &&
            old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
        val result = mutableListOf<DiffLine>()
        for (index in maxOf(0, prefix - CONTEXT_LINES) until prefix) {
            result += DiffLine(index + 1, index + 1, old[index], DiffLineKind.CONTEXT)
        }
        old.subList(prefix, old.size - suffix).take(300).forEachIndexed { index, line ->
            result += DiffLine(prefix + index + 1, null, line, DiffLineKind.REMOVED)
        }
        new.subList(prefix, new.size - suffix).take(300).forEachIndexed { index, line ->
            result += DiffLine(null, prefix + index + 1, line, DiffLineKind.ADDED)
        }
        for (index in old.size - suffix until minOf(old.size, old.size - suffix + CONTEXT_LINES)) {
            val newIndex = new.size - suffix + (index - (old.size - suffix))
            result += DiffLine(index + 1, newIndex + 1, old[index], DiffLineKind.CONTEXT)
        }
        return result
    }

    private fun compact(lines: List<DiffLine>): List<DiffLine> {
        val keep = BooleanArray(lines.size)
        lines.indices.filter { lines[it].kind != DiffLineKind.CONTEXT }.forEach { changed ->
            for (index in maxOf(0, changed - CONTEXT_LINES)..minOf(lines.lastIndex, changed + CONTEXT_LINES)) keep[index] = true
        }
        val result = mutableListOf<DiffLine>()
        var index = 0
        while (index < lines.size && result.size < MAX_VISIBLE_LINES) {
            while (index < lines.size && !keep[index]) index++
            if (index >= lines.size) break

            val hunkStart = index
            while (index < lines.size && keep[index]) index++
            val hunk = lines.subList(hunkStart, index)
            result += DiffLine(null, null, hunkHeader(hunk), DiffLineKind.SEPARATOR)
            hunk.forEach { line ->
                if (result.size < MAX_VISIBLE_LINES) {
                    result += line.copy(text = line.text.take(600))
                }
            }
        }
        return result
    }

    private fun hunkHeader(lines: List<DiffLine>): String {
        val oldNumbers = lines.mapNotNull(DiffLine::oldNumber)
        val newNumbers = lines.mapNotNull(DiffLine::newNumber)
        val oldStart = oldNumbers.firstOrNull()
            ?: (newNumbers.firstOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0)
        val newStart = newNumbers.firstOrNull()
            ?: (oldNumbers.firstOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0)
        return "@@ -$oldStart,${oldNumbers.size} +$newStart,${newNumbers.size} @@"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }
}
