package com.saas.apkeditorplus

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

object ApkPatchEngine {
    data class Result(
        val replacements: Map<String, File>,
        val deletions: Set<String>,
        val appliedRules: Int
    )

    fun apply(
        context: Context,
        sourceApk: File,
        patchFile: File,
        existingReplacements: Map<String, File> = emptyMap()
    ): Result {
        require(sourceApk.isFile) { "APK original não encontrado" }
        require(patchFile.isFile) { "Patch não encontrado" }
        val outputDir = File(context.cacheDir, "applied_patch/${System.currentTimeMillis()}").apply { mkdirs() }
        val replacements = linkedMapOf<String, File>()
        val deletions = linkedSetOf<String>()
        var applied = 0
        ZipFile(patchFile).use { patchZip ->
            val scriptEntry = patchZip.getEntry("patch.txt") ?: error("O patch não contém patch.txt")
            val lines = patchZip.getInputStream(scriptEntry).bufferedReader().readLines()
            var index = 0
            while (index < lines.size) {
                when (lines[index].trim()) {
                    "[ADD_FILES]" -> {
                        val block = block(lines, index + 1, "[/ADD_FILES]")
                        val source = valueAfter(block.lines, "SOURCE:") ?: error("SOURCE ausente em ADD_FILES")
                        val target = valueAfter(block.lines, "TARGET:")?.removeSuffix("/")
                            ?: error("TARGET ausente em ADD_FILES")
                        validateEntryName(target)
                        val entry = patchZip.getEntry(source) ?: error("Arquivo $source não encontrado no patch")
                        val out = File(outputDir, "add_${applied}_${File(target).name}")
                        patchZip.getInputStream(entry).use { input -> out.outputStream().use(input::copyTo) }
                        replacements[target] = out
                        deletions.remove(target)
                        applied++
                        index = block.nextIndex
                    }
                    "[REMOVE_FILES]" -> {
                        val block = block(lines, index + 1, "[/REMOVE_FILES]")
                        val marker = block.lines.indexOfFirst { it.trim() == "TARGET:" }
                        require(marker >= 0) { "TARGET ausente em REMOVE_FILES" }
                        block.lines.drop(marker + 1).map(String::trim).filter(String::isNotBlank).forEach { target ->
                            validateEntryName(target)
                            deletions += target
                            replacements.remove(target)
                        }
                        applied++
                        index = block.nextIndex
                    }
                    "[MATCH_REPLACE]" -> {
                        val block = block(lines, index + 1, "[/MATCH_REPLACE]")
                        val targetPattern = valueAfter(block.lines, "TARGET:") ?: error("TARGET ausente em MATCH_REPLACE")
                        val regexMode = valueAfter(block.lines, "REGEX:").toBoolean()
                        val dotAll = valueAfter(block.lines, "DOTALL:").toBoolean()
                        val match = section(block.lines, "MATCH:", setOf("REPLACE:", "REGEX:", "DOTALL:", "TARGET:"))
                            .joinToString("\n")
                        val replacement = section(block.lines, "REPLACE:", setOf("MATCH:", "REGEX:", "DOTALL:", "TARGET:"))
                            .joinToString("\n")
                        require(match.isNotEmpty()) { "MATCH vazio" }
                        val targets = matchingTargets(sourceApk, targetPattern, existingReplacements.keys)
                        require(targets.isNotEmpty()) { "Nenhum arquivo corresponde a $targetPattern" }
                        targets.forEach { target ->
                            val current = replacements[target]?.readText()
                                ?: existingReplacements[target]?.takeIf(File::isFile)?.readText()
                                ?: readEntryText(sourceApk, target)
                            val changed = if (regexMode) {
                                val options = if (dotAll) setOf(RegexOption.DOT_MATCHES_ALL) else emptySet()
                                Regex(match, options).replace(current) { result ->
                                    var resolved = replacement
                                    result.groupValues.drop(1).forEachIndexed { groupIndex, value ->
                                        resolved = resolved.replace("${'$'}{GROUP${groupIndex + 1}}", value)
                                    }
                                    resolved
                                }
                            } else current.replace(match, replacement)
                            require(changed != current) { "Nenhuma ocorrência encontrada em $target" }
                            val out = File(outputDir, "replace_${applied}_${File(target).name}")
                            out.writeText(changed)
                            replacements[target] = out
                        }
                        applied++
                        index = block.nextIndex
                    }
                    else -> {
                        val line = lines[index].trim()
                        if (line.startsWith("[") && line !in setOf("[MIN_ENGINE_VER]", "[AUTHOR]", "[PACKAGE]")) {
                            error("Regra de patch ainda não suportada: $line")
                        }
                        index += if (line in setOf("[MIN_ENGINE_VER]", "[AUTHOR]", "[PACKAGE]")) 2 else 1
                    }
                }
            }
        }
        return Result(replacements, deletions, applied)
    }

    private data class Block(val lines: List<String>, val nextIndex: Int)
    private fun block(lines: List<String>, start: Int, end: String): Block {
        val endIndex = (start until lines.size).firstOrNull { lines[it].trim() == end }
            ?: error("Bloco sem fechamento $end")
        return Block(lines.subList(start, endIndex), endIndex + 1)
    }

    private fun valueAfter(lines: List<String>, marker: String): String? {
        val index = lines.indexOfFirst { it.trim() == marker }
        return lines.getOrNull(index + 1)?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun section(lines: List<String>, marker: String, otherMarkers: Set<String>): List<String> {
        val start = lines.indexOfFirst { it.trim() == marker }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { it.trim() !in otherMarkers }
    }

    private fun matchingTargets(apk: File, pattern: String, addedEntries: Set<String>): List<String> {
        validateEntryName(pattern.replace("*", "x"))
        val regex = Regex("^" + Regex.escape(pattern).replace("\\*", ".*") + "$")
        return ZipFile(apk).use { zip ->
            (zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name } + addedEntries.asSequence())
                .distinct().filter(regex::matches).toList()
        }
    }

    private fun readEntryText(apk: File, name: String): String = ZipFile(apk).use { zip ->
        val entry = zip.getEntry(name) ?: error("Arquivo $name não encontrado")
        require(entry.size in 0..4_194_304) { "Arquivo grande demais para MATCH_REPLACE: $name" }
        zip.getInputStream(entry).bufferedReader().use { it.readText() }
    }

    private fun validateEntryName(name: String) {
        require(name.isNotBlank() && !name.startsWith('/') && !name.contains("..") && !name.contains('\\')) {
            "Caminho inseguro no patch: $name"
        }
    }
}
