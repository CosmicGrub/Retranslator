package com.retroid.translator.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LanguageCatalog.displayNameFor] only touches `java.util.Locale` (pure
 * JDK) - it deliberately never calls [LanguageCatalog.codes], which would
 * require a real ML Kit `TranslateLanguage` runtime unavailable on a plain
 * JVM unit test. Real language codes used here are the ones this app
 * actually ships natural (Piper) voices for - see README.md's license table.
 */
class LanguageCatalogTest {

    @Test
    fun `displayNameFor renders real ISO codes as their English language name`() {
        assertEquals("English", LanguageCatalog.displayNameFor("en"))
        assertEquals("German", LanguageCatalog.displayNameFor("de"))
        assertEquals("Spanish", LanguageCatalog.displayNameFor("es"))
        assertEquals("French", LanguageCatalog.displayNameFor("fr"))
    }

    @Test
    fun `displayNameFor capitalizes the first character`() {
        val name = LanguageCatalog.displayNameFor("zh")
        assertEquals(name.replaceFirstChar { it.uppercase() }, name)
    }

    @Test
    fun `displayNameFor falls back to the raw code when Locale has no display name`() {
        // A syntactically-valid but nonsense language subtag: Locale returns
        // an empty display name for it, so displayNameFor must fall back to
        // returning the code itself rather than an empty string.
        val result = LanguageCatalog.displayNameFor("zzzz")
        assertEquals("zzzz", result.lowercase())
    }
}
