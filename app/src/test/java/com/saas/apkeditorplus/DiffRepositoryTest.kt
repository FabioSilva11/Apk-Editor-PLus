package com.saas.apkeditorplus

import com.saas.apkeditorplus.full.DiffLineKind
import com.saas.apkeditorplus.full.DiffRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffRepositoryTest {
    @Test
    fun reportsAddedRemovedAndContextLines() {
        val diff = DiffRepository.calculateTextDiff(
            "linha 1\nlinha antiga\nlinha 3",
            "linha 1\nlinha nova\nlinha 3"
        )

        assertTrue(diff.any { it.kind == DiffLineKind.REMOVED && it.text == "linha antiga" })
        assertTrue(diff.any { it.kind == DiffLineKind.ADDED && it.text == "linha nova" })
        assertTrue(diff.any { it.kind == DiffLineKind.CONTEXT && it.text == "linha 1" })
        assertEquals("@@ -1,3 +1,3 @@", diff.first().text)
        assertEquals(DiffLineKind.SEPARATOR, diff.first().kind)
    }

    @Test
    fun identicalTextHasNoVisibleDiff() {
        assertEquals(emptyList<Any>(), DiffRepository.calculateTextDiff("igual", "igual"))
    }

    @Test
    fun trailingNewlineDoesNotCreateAnExtraAddedLine() {
        val diff = DiffRepository.calculateTextDiff("", "primeira\nsegunda\n")
        assertEquals(2, diff.count { it.kind == DiffLineKind.ADDED })
        assertEquals("@@ -0,0 +1,2 @@", diff.first().text)
    }

    @Test
    fun distantChangesCreateIndependentHunks() {
        val oldText = (1..20).joinToString("\n") { "linha $it" }
        val newText = oldText.replace("linha 2", "linha dois").replace("linha 19", "linha dezenove")

        val diff = DiffRepository.calculateTextDiff(oldText, newText)

        assertEquals(2, diff.count { it.kind == DiffLineKind.SEPARATOR })
        assertTrue(diff.filter { it.kind == DiffLineKind.SEPARATOR }.all { it.text.startsWith("@@ -") })
    }
}
