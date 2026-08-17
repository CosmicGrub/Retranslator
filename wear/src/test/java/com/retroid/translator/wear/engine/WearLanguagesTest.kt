package com.retroid.translator.wear.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WearLanguages] is a plain Kotlin object/data-class list with no Android
 * dependency - real, hand-curated catalog data (see the class's own doc
 * comment for the "why these 12" rationale), not a mock.
 */
class WearLanguagesTest {

    @Test
    fun `CURATED has exactly the 12 real curated languages, no duplicate codes`() {
        assertEquals(12, WearLanguages.CURATED.size)
        val codes = WearLanguages.CURATED.map { it.code }
        assertEquals("no two CURATED entries should share a code", codes.size, codes.toSet().size)
    }

    @Test
    fun `every CURATED entry has a non-blank displayName and a real vosk model zip URL`() {
        WearLanguages.CURATED.forEach { lang ->
            assertTrue("code ${lang.code} has blank displayName", lang.displayName.isNotBlank())
            assertTrue(
                "code ${lang.code} voskUrl doesn't look like a real vosk model zip: ${lang.voskUrl}",
                lang.voskUrl.startsWith("https://alphacephei.com/vosk/models/") && lang.voskUrl.endsWith(".zip")
            )
            assertTrue("code ${lang.code} has non-positive approxVoskMiB", lang.approxVoskMiB > 0)
        }
    }

    @Test
    fun `byCode finds a real curated language by its code`() {
        val en = WearLanguages.byCode("en")
        assertNotNull(en)
        assertEquals("English", en!!.displayName)
    }

    @Test
    fun `byCode returns null for a language outside the curated 12`() {
        // "ja" and friends are curated; Vosk's own catalog also has, e.g.,
        // Swedish ("sv") on the phone side (VoskModelCatalog) but it was
        // deliberately not curated onto the watch.
        assertNull(WearLanguages.byCode("sv"))
        assertNull(WearLanguages.byCode("not-a-code"))
    }
}
