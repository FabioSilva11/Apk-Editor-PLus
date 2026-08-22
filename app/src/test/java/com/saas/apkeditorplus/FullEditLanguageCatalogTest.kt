package com.saas.apkeditorplus

import com.saas.apkeditorplus.full.FullEditLanguageCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullEditLanguageCatalogTest {
    @Test
    fun existingLanguagesAreRemovedFromAddList() {
        val available = FullEditLanguageCatalog.missingLanguages(listOf("", "-en", "-pt"))

        assertFalse(available.any { it.qualifier == "-en" })
        assertFalse(available.any { it.qualifier == "-pt" })
        assertTrue(available.any { it.qualifier == "-es" })
    }

    @Test
    fun regionalLanguageUsesFlagAndGenericLanguageUsesGlobe() {
        assertEquals("🇧🇷", FullEditLanguageCatalog.entryForQualifier("-pt-rBR").symbol)
        assertEquals("🌐", FullEditLanguageCatalog.entryForQualifier("-pt").symbol)
    }
}
