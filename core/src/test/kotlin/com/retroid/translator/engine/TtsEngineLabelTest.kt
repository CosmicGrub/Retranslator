package com.retroid.translator.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsEngineLabelTest {

    @Test
    fun `a downloaded natural voice wins regardless of eSpeak support`() {
        assertEquals("natural voice", TtsEngineLabel.forState(naturalVoiceDownloaded = true, espeakSupportsLanguage = true))
        assertEquals("natural voice", TtsEngineLabel.forState(naturalVoiceDownloaded = true, espeakSupportsLanguage = false))
    }

    @Test
    fun `falls back to eSpeak when no natural voice is downloaded but eSpeak supports the language`() {
        assertEquals(
            "eSpeak (built-in, robotic)",
            TtsEngineLabel.forState(naturalVoiceDownloaded = false, espeakSupportsLanguage = true)
        )
    }

    @Test
    fun `reports nothing available when neither engine can speak this language`() {
        assertEquals(
            "no voice available yet",
            TtsEngineLabel.forState(naturalVoiceDownloaded = false, espeakSupportsLanguage = false)
        )
    }
}
