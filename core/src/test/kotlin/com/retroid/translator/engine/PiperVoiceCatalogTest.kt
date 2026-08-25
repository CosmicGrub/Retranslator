package com.retroid.translator.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** First test coverage for this file - previously untested only because nothing in this repo could construct a plain JVM test source set to run it from (docs/specs/engineering-systems-pitch.md system #2). */
class PiperVoiceCatalogTest {

    @Test
    fun `every catalog language covers both genders (this app's own male+female claim)`() {
        // PiperVoiceCatalog's own doc comment: "A male+female pair only
        // exists here for a language when Piper genuinely ships both with
        // an acceptable license (currently true for all 4 languages this
        // app covers: en/de/es/fr)."
        for (code in PiperVoiceCatalog.supportedCodes()) {
            assertTrue(
                "$code should have a male voice",
                PiperVoiceCatalog.forLanguageAndGender(code, VoiceGender.MALE) != null
            )
            assertTrue(
                "$code should have a female voice",
                PiperVoiceCatalog.forLanguageAndGender(code, VoiceGender.FEMALE) != null
            )
        }
    }

    @Test
    fun `allForLanguage returns exactly the entries for that language`() {
        val english = PiperVoiceCatalog.allForLanguage("en")
        assertEquals(2, english.size)
        assertTrue(english.all { it.mlKitCode == "en" })
    }

    @Test
    fun `an unsupported language has no voice at all`() {
        assertNull(PiperVoiceCatalog.forLanguageAndGender("xx", VoiceGender.FEMALE))
        assertTrue(PiperVoiceCatalog.allForLanguage("xx").isEmpty())
    }

    @Test
    fun `supportedCodes matches the real four languages this catalog documents covering`() {
        assertEquals(setOf("en", "de", "es", "fr"), PiperVoiceCatalog.supportedCodes())
    }
}
