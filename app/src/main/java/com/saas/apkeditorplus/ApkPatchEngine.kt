package com.saas.apkeditorplus

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

object ApkPatchEngine {
    data class Result(
        val replacements: Map<String, File>,
        val deletions: Set<String>,
        val appliedRules: Int,
        val reports: List<String> = emptyList()
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
        val variables = linkedMapOf<String, String>()
        val reports = mutableListOf<String>()
        var applied = 0
        ZipFile(patchFile).use { patchZip ->
            val scriptEntry = patchZip.getEntry("patch.txt") ?: error("O patch não contém patch.txt")
            val lines = patchZip.getInputStream(scriptEntry).bufferedReader().readLines()
            validateMetadata(context, sourceApk, lines)
            var index = 0
            var executedSteps = 0
            while (index < lines.size) {
                require(executedSteps++ < 2_000) { "O patch contém um ciclo de execução" }
                when (lines[index].trim()) {
                    "[ADD_FILES]" -> {
                        val block = block(lines, index + 1, "[/ADD_FILES]")
                        val source = resolve(valueAfter(block.lines, "SOURCE:") ?: error("SOURCE ausente em ADD_FILES"), variables)
                        val target = resolve(
                            valueAfter(block.lines, "TARGET:") ?: error("TARGET ausente em ADD_FILES"),
                            variables
                        ).removeSuffix("/")
                        validateEntryName(target)
                        val entry = patchZip.getEntry(source) ?: error("Arquivo $source não encontrado no patch")
                        val out = File(outputDir, "add_${applied}_${File(target).name}")
                        patchZip.getInputStream(entry).use { input -> out.outputStream().use(input::copyTo) }
                        replacements[target] = out
                        deletions.remove(target)
                        applied++
                        reports += "Adicionado: $target"
                        index = block.nextIndex
                    }
                    "[REMOVE_FILES]" -> {
                        val block = block(lines, index + 1, "[/REMOVE_FILES]")
                        val marker = block.lines.indexOfFirst { it.trim() == "TARGET:" }
                        require(marker >= 0) { "TARGET ausente em REMOVE_FILES" }
                        block.lines.drop(marker + 1).map(String::trim).filter(String::isNotBlank).forEach { rawTarget ->
                            val target = resolve(rawTarget, variables)
                            validateEntryName(target)
                            deletions += target
                            replacements.remove(target)
                        }
                        applied++
                        reports += "Remoção registrada"
                        index = block.nextIndex
                    }
                    "[MATCH_REPLACE]" -> {
                        val block = block(lines, index + 1, "[/MATCH_REPLACE]")
                        val targetPattern = resolve(valueAfter(block.lines, "TARGET:") ?: error("TARGET ausente em MATCH_REPLACE"), variables)
                        val regexMode = valueAfter(block.lines, "REGEX:").toBoolean()
                        val dotAll = valueAfter(block.lines, "DOTALL:").toBoolean()
                        val match = resolve(section(block.lines, "MATCH:", setOf("REPLACE:", "REGEX:", "DOTALL:", "TARGET:", "NAME:"))
                            .joinToString("\n"), variables)
                        val replacement = resolve(section(block.lines, "REPLACE:", setOf("MATCH:", "REGEX:", "DOTALL:", "TARGET:", "NAME:"))
                            .joinToString("\n"), variables)
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
                        reports += "Substituído em ${targets.size} arquivo(s): $targetPattern"
                        index = block.nextIndex
                    }
                    "[MATCH_ASSIGN]" -> {
                        val block = block(lines, index + 1, "[/MATCH_ASSIGN]")
                        val targetPattern = resolve(valueAfter(block.lines, "TARGET:") ?: error("TARGET ausente em MATCH_ASSIGN"), variables)
                        val regexMode = valueAfter(block.lines, "REGEX:").toBoolean()
                        val matchText = resolve(section(block.lines, "MATCH:", setOf("ASSIGN:", "REGEX:", "TARGET:", "NAME:"))
                            .joinToString("\n"), variables)
                        val assignments = section(block.lines, "ASSIGN:", setOf("MATCH:", "REGEX:", "TARGET:", "NAME:"))
                        val matched = findMatch(sourceApk, targetPattern, matchText, regexMode, replacements, existingReplacements)
                            ?: error("MATCH_ASSIGN não encontrou correspondência em $targetPattern")
                        assignments.filter(String::isNotBlank).forEach { assignment ->
                            val key = assignment.substringBefore('=').trim()
                            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && '=' in assignment) { "ASSIGN inválido: $assignment" }
                            var value = assignment.substringAfter('=')
                            matched.groupValues.drop(1).forEachIndexed { groupIndex, group ->
                                value = value.replace("${'$'}{GROUP${groupIndex + 1}}", group)
                            }
                            variables[key] = resolve(value, variables)
                        }
                        applied++
                        reports += "Variáveis definidas: ${assignments.size}"
                        index = block.nextIndex
                    }
                    "[MATCH_GOTO]" -> {
                        val block = block(lines, index + 1, "[/MATCH_GOTO]")
                        val targetPattern = resolve(valueAfter(block.lines, "TARGET:") ?: error("TARGET ausente em MATCH_GOTO"), variables)
                        val regexMode = valueAfter(block.lines, "REGEX:").toBoolean()
                        val matchText = resolve(section(block.lines, "MATCH:", setOf("GOTO:", "REGEX:", "TARGET:", "NAME:"))
                            .joinToString("\n"), variables)
                        val destination = resolve(valueAfter(block.lines, "GOTO:") ?: error("GOTO ausente em MATCH_GOTO"), variables)
                        val matched = findMatch(sourceApk, targetPattern, matchText, regexMode, replacements, existingReplacements) != null
                        index = if (matched) findNamedRule(lines, destination) else block.nextIndex
                        reports += if (matched) "Desvio aplicado: $destination" else "Condição não encontrada: $destination"
                        applied++
                    }
                    "[GOTO]" -> {
                        val block = block(lines, index + 1, "[/GOTO]")
                        val destination = resolve(valueAfter(block.lines, "GOTO:") ?: error("Destino ausente em GOTO"), variables)
                        index = findNamedRule(lines, destination)
                    }
                    "[DUMMY]" -> {
                        val block = block(lines, index + 1, "[/DUMMY]")
                        index = block.nextIndex
                    }
                    "[MERGE]" -> {
                        val block = block(lines, index + 1, "[/MERGE]")
                        val source = resolve(valueAfter(block.lines, "SOURCE:") ?: error("SOURCE ausente em MERGE"), variables)
                        val nestedEntry = patchZip.getEntry(source) ?: error("Arquivo $source não encontrado no patch")
                        val nestedFile = File(outputDir, "merge_${applied}.zip")
                        patchZip.getInputStream(nestedEntry).use { input -> nestedFile.outputStream().use(input::copyTo) }
                        var merged = 0
                        ZipFile(nestedFile).use { nested ->
                            nested.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                                val target = entry.name
                                validateEntryName(target)
                                require(isSafeMergeTarget(target)) {
                                    "MERGE decodificado não é seguro para $target; use ADD_FILES ou edite recursos pelo editor"
                                }
                                val out = File(outputDir, "merge_${applied}_${merged}_${File(target).name}")
                                nested.getInputStream(entry).use { input -> out.outputStream().use(input::copyTo) }
                                replacements[target] = out
                                deletions.remove(target)
                                merged++
                            }
                        }
                        require(merged > 0) { "O arquivo de MERGE está vazio" }
                        applied++
                        reports += "Mesclados $merged arquivo(s)"
                        index = block.nextIndex
                    }
                    else -> {
                        val line = lines[index].trim()
                        if (line in setOf("[EXECUTE_DEX]", "[SIGNATURE_REVISE]")) {
                            error("Regra insegura não permitida: $line")
                        }
                        if (line.startsWith("[") && line !in setOf("[MIN_ENGINE_VER]", "[AUTHOR]", "[PACKAGE]")) {
                            error("Regra de patch ainda não suportada: $line")
                        }
                        index += if (line in setOf("[MIN_ENGINE_VER]", "[AUTHOR]", "[PACKAGE]")) 2 else 1
                    }
                }
            }
        }
        return Result(replacements, deletions, applied, reports)
    }

    private fun validateMetadata(context: Context, apk: File, lines: List<String>) {
        val engineVersion = topLevelValue(lines, "[MIN_ENGINE_VER]")?.toIntOrNull() ?: 1
        require(engineVersion <= 3) { "Patch exige motor versão $engineVersion" }
        val expectedPackage = topLevelValue(lines, "[PACKAGE]").orEmpty()
        if (expectedPackage.isNotBlank() && expectedPackage != "*") {
            val actual = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName.orEmpty()
            require(actual == expectedPackage) { "Patch destinado a $expectedPackage, mas o APK é $actual" }
        }
    }

    private fun topLevelValue(lines: List<String>, marker: String): String? {
        val index = lines.indexOfFirst { it.trim() == marker }
        return lines.drop(index + 1).firstOrNull { it.trim().isNotBlank() }?.trim().takeIf { index >= 0 }
    }

    private fun resolve(value: String, variables: Map<String, String>): String {
        var resolved = value
        repeat(8) {
            val next = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").replace(resolved) { match ->
                variables[match.groupValues[1]] ?: match.value
            }
            if (next == resolved) return resolved
            resolved = next
        }
        return resolved
    }

    private fun findMatch(
        apk: File,
        pattern: String,
        match: String,
        regexMode: Boolean,
        replacements: Map<String, File>,
        existing: Map<String, File>
    ): MatchResult? {
        val matcher = if (regexMode) Regex(match) else Regex(Regex.escape(match))
        matchingTargets(apk, pattern, existing.keys + replacements.keys).forEach { target ->
            val text = replacements[target]?.takeIf(File::isFile)?.readText()
                ?: existing[target]?.takeIf(File::isFile)?.readText()
                ?: readEntryText(apk, target)
            matcher.find(text)?.let { return it }
        }
        return null
    }

    private fun findNamedRule(lines: List<String>, name: String): Int {
        for (index in lines.indices) {
            val opening = lines[index].trim()
            if (!opening.matches(Regex("\\[[A-Z_]+]")) || opening in setOf("[MIN_ENGINE_VER]", "[AUTHOR]", "[PACKAGE]")) continue
            val closing = "[/" + opening.removePrefix("[")
            val endIndex = (index + 1 until lines.size).firstOrNull { lines[it].trim() == closing } ?: continue
            val body = lines.subList(index + 1, endIndex)
            if (valueAfter(body, "NAME:") == name) return index
        }
        error("Destino de patch não encontrado: $name")
    }

    private fun isSafeMergeTarget(name: String): Boolean =
        !name.equals("AndroidManifest.xml", true) &&
            name != "resources.arsc" &&
            !name.endsWith(".dex", true) &&
            !name.startsWith("smali") &&
            !name.startsWith("res/values")

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
