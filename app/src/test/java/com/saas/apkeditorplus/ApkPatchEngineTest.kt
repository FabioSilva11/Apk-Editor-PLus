package com.saas.apkeditorplus

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkPatchEngineTest {
    @Test
    fun resolvesAndroidCompatibleVariableSyntax() {
        val result = ApkPatchEngine.resolveVariables(
            "assets/${'$'}{DIRECTORY}/${'$'}{FILE}",
            mapOf("DIRECTORY" to "patch", "FILE" to "result.txt")
        )

        assertEquals("assets/patch/result.txt", result)
    }

    @Test
    fun resolvesNestedVariablesAndPreservesUnknownOnes() {
        val result = ApkPatchEngine.resolveVariables(
            "${'$'}{ROOT}/${'$'}{UNKNOWN}",
            mapOf("ROOT" to "${'$'}{BASE}/nested", "BASE" to "assets")
        )

        assertEquals("assets/nested/${'$'}{UNKNOWN}", result)
    }

    @Test
    fun doesNotTreatMalformedPlaceholdersAsVariables() {
        val source = "${'$'}{INVALID-NAME} and ${'$'}{1INVALID}"

        assertEquals(source, ApkPatchEngine.resolveVariables(source, emptyMap()))
    }

    @Test
    fun matchingIncludesFilesAddedByEarlierPatchRules() {
        val archive = File.createTempFile("patch-engine", ".zip")
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/original.txt"))
                zip.write("original".toByteArray())
                zip.closeEntry()
            }

            assertEquals(
                listOf("assets/codex_patch_test.txt"),
                ApkPatchEngine.matchingTargets(
                    archive,
                    "assets/codex_patch_test.txt",
                    setOf("assets/codex_patch_test.txt")
                )
            )
        } finally {
            archive.delete()
        }
    }
}
