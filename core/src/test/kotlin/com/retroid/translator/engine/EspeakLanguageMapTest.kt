package com.retroid.translator.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** First test coverage for this file - previously untested only because nothing in this repo could construct a plain JVM test source set to run it from (docs/specs/engineering-systems-pitch.md system #2). */
class EspeakLanguageMapTest {

    @Test
    fun `Chinese remaps to the Mandarin espeak voice code`() {
        assertEquals("cmn", EspeakLanguageMap.toEspeakLanguage("zh"))
    }

    @Test
    fun `legacy Hebrew ML Kit code remaps to espeak's code`() {
        assertEquals("he", EspeakLanguageMap.toEspeakLanguage("iw"))
    }

    @Test
    fun `a code with no known mismatch passes through unchanged`() {
        assertEquals("es", EspeakLanguageMap.toEspeakLanguage("es"))
        assertEquals("de", EspeakLanguageMap.toEspeakLanguage("de"))
    }
}
