package com.retroid.translator.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM unit tests for [VoskResultParsing] - no Android framework or
 * Vosk native library involved, since this object only ever touches
 * `org.json.JSONObject` and plain strings/doubles (see that file's own doc
 * comment). Real test data below is not invented: it's the actual Vosk
 * recognizer JSON and avgWordConf numbers recorded in
 * docs/specs/fold5-adaptation.md §4 from real on-device dual-recognizer
 * runs (`vosk-model-small-en-us-0.15` / `vosk-model-small-es-0.42`).
 */
class VoskResultParsingTest {

    // --- Real captured JSON, docs/specs/fold5-adaptation.md §4 (line ~104-115):
    // the actual raw Vosk output for the Spanish recognizer decoding
    // "¿Dónde está la estación de tren?" with recognizer.setWords(true). ---
    private val realSpanishFinalResult = """
        {
          "result" : [
            {"conf": 0.618969, "end": 0.330000, "start": 0.000000, "word": "donde"},
            {"conf": 0.649093, "end": 0.600000, "start": 0.330000, "word": "esta"},
            {"conf": 1.000000, "end": 1.260000, "start": 0.720000, "word": "estación"}
          ],
          "text" : "donde esta estación"
        }
    """.trimIndent()

    @Test
    fun `extractText returns the real transcribed phrase from real Vosk JSON`() {
        assertEquals("donde esta estación", VoskResultParsing.extractText(realSpanishFinalResult))
    }

    @Test
    fun `extractWordConfStats averages the real per-word conf values`() {
        val (avgConf, wordCount) = VoskResultParsing.extractWordConfStats(realSpanishFinalResult)
        // (0.618969 + 0.649093 + 1.000000) / 3, real numbers from the doc.
        assertEquals(3, wordCount)
        assertTrue(avgConf != null)
        assertEquals(0.7560206666666667, avgConf!!, 1e-9)
    }

    @Test
    fun `extractPartialText reads the partial field`() {
        val partial = """{"partial": "donde esta"}"""
        assertEquals("donde esta", VoskResultParsing.extractPartialText(partial))
    }

    @Test
    fun `extractPartialText is blank when the field is absent`() {
        assertEquals("", VoskResultParsing.extractPartialText("""{"text": "donde esta estación"}"""))
    }

    @Test
    fun `extractText returns empty string on malformed JSON instead of throwing`() {
        assertEquals("", VoskResultParsing.extractText("not json at all"))
    }

    @Test
    fun `extractWordConfStats returns null avgConf and zero count when result array is absent`() {
        val (avgConf, wordCount) = VoskResultParsing.extractWordConfStats("""{"text": "hi"}""")
        assertNull(avgConf)
        assertEquals(0, wordCount)
    }

    @Test
    fun `extractWordConfStats returns null avgConf on malformed JSON instead of throwing`() {
        val (avgConf, wordCount) = VoskResultParsing.extractWordConfStats("{not valid")
        assertNull(avgConf)
        assertEquals(0, wordCount)
    }

    @Test
    fun `extractWordConfStats falls back to word count with no conf sum when words lack conf`() {
        val noConfJson = """
            {"result": [{"word": "hola"}, {"word": "mundo"}], "text": "hola mundo"}
        """.trimIndent()
        val (avgConf, wordCount) = VoskResultParsing.extractWordConfStats(noConfJson)
        assertNull(avgConf)
        assertEquals(2, wordCount)
    }

    // --- pickLanguage: real avgWordConf margins, docs/specs/fold5-adaptation.md
    // §4's "avgWordConf margins (English vs Spanish)" table (line ~119-128).
    // es_1 and es_3 are the two real clips the doc calls out as narrow
    // (0.02-0.05) margins that still correctly picked Spanish both runs. ---

    @Test
    fun `pickLanguage picks the correct language on a wide real margin (en_2, run 1)`() {
        val en = VoskResultParsing.Candidate("en", 0.914, wordCount = 8)
        val es = VoskResultParsing.Candidate("es", 0.625, wordCount = 8)
        val (winner, basis) = VoskResultParsing.pickLanguage(en, es)
        assertEquals("en", winner)
        assertTrue(basis.startsWith("avgWordConf"))
    }

    @Test
    fun `pickLanguage picks the correct language on a narrow real margin (es_1, run 1)`() {
        // Real numbers: en=0.736 vs es=0.756 - Spanish correctly wins by only 0.02.
        val en = VoskResultParsing.Candidate("en", 0.736, wordCount = 5)
        val es = VoskResultParsing.Candidate("es", 0.756, wordCount = 5)
        val (winner, basis) = VoskResultParsing.pickLanguage(en, es)
        assertEquals("es", winner)
        assertTrue(basis.contains("avgWordConf"))
    }

    @Test
    fun `pickLanguage picks the correct language on a narrow real margin (es_3, run 2)`() {
        // Real numbers: en=0.652 vs es=0.704 - Spanish correctly wins by ~0.05.
        val en = VoskResultParsing.Candidate("en", 0.652, wordCount = 6)
        val es = VoskResultParsing.Candidate("es", 0.704, wordCount = 6)
        val (winner, _) = VoskResultParsing.pickLanguage(en, es)
        assertEquals("es", winner)
    }

    @Test
    fun `pickLanguage falls back to word count when either side has no conf field`() {
        // Real documented failure mode (fold5-adaptation.md §4, en_r2 clip):
        // the correct-language recognizer returned an empty result (no conf
        // field at all), so the decision fell to the word-count fallback,
        // which the wrong-language recognizer won simply by hallucinating a
        // full sentence ("disco está aire won point", avgWordConf 0.703).
        val correctButEmpty = VoskResultParsing.Candidate("en", avgWordConf = null, wordCount = 0)
        val wrongButHallucinated = VoskResultParsing.Candidate("es", avgWordConf = 0.703, wordCount = 5)
        val (winner, basis) = VoskResultParsing.pickLanguage(correctButEmpty, wrongButHallucinated)
        assertEquals("es", winner)
        assertTrue(basis.contains("wordCount fallback"))
    }

    @Test
    fun `pickLanguage fallback ties go to the first candidate`() {
        val a = VoskResultParsing.Candidate("en", avgWordConf = null, wordCount = 3)
        val b = VoskResultParsing.Candidate("es", avgWordConf = null, wordCount = 3)
        val (winner, _) = VoskResultParsing.pickLanguage(a, b)
        assertEquals("en", winner)
    }
}
